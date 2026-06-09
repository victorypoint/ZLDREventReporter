package com.victorypoint.zldreventreporter.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorypoint.zldreventreporter.data.db.EventStatsRepository
import com.victorypoint.zldreventreporter.data.events.EventsApi
import com.victorypoint.zldreventreporter.data.events.dto.RaceResultEntryDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ParticipantUiState {
    data object Loading : ParticipantUiState
    data class Success(
        val eventName: String,
        val entries: List<RaceResultEntryDto>,
        // true when the empty entry list is a confirmed result (not a pending/unavailable one):
        // either results are non-empty, nobody showed up, or the posting window has closed.
        val resultsConfirmed: Boolean,
    ) : ParticipantUiState
    data class Error(val message: String) : ParticipantUiState
}

class ParticipantDetailViewModel(
    private val eventsApi: EventsApi,
    private val statsRepository: EventStatsRepository,
    private val eventId: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ParticipantUiState>(ParticipantUiState.Loading)
    val uiState: StateFlow<ParticipantUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            runCatching {
                val detail = withContext(Dispatchers.IO) { eventsApi.getEventWithCounts(eventId) }
                val subgroups = detail.eventSubgroups.orEmpty()
                val showedUpCount = detail.totalJoinedCount ?: 0

                // Collect per-subgroup results and the DB entity concurrently.
                val subgroupResults: List<Pair<Boolean, List<RaceResultEntryDto>>>
                val eventDateMs: Long
                coroutineScope {
                    val resultsDeferred = subgroups.map { sg ->
                        async(Dispatchers.IO) {
                            val response = runCatching { eventsApi.getRaceResultsEntries(sg.id) }.getOrNull()
                            (response != null) to (response?.entries.orEmpty())
                        }
                    }
                    val entityDeferred = async(Dispatchers.IO) { statsRepository.getById(eventId) }
                    subgroupResults = resultsDeferred.map { it.await() }
                    eventDateMs = entityDeferred.await()?.eventDate ?: 0L
                }

                val entries = subgroupResults.flatMap { it.second }
                    .sortedWith(compareBy(nullsLast()) { it.rank })

                // True while we're still within the window where Zwift may not have published
                // results yet: event start + duration + 30-minute posting grace.
                // eventDateMs is the start time; durationInSeconds covers the run time itself.
                // Guard is skipped (false) when eventDateMs is unknown (0).
                val durationMs = (detail.durationInSeconds ?: 0) * 1000L
                val withinPostingWindow = eventDateMs > 0 &&
                    System.currentTimeMillis() < eventDateMs + durationMs + RESULTS_POSTING_GRACE_MS

                // Results are confirmed (empty list is real, not pending) when:
                //   - there are actual entries (non-empty result list), or
                //   - nobody showed up (event definitively over with no participants), or
                //   - we are past the posting window AND at least one subgroup call succeeded —
                //     meaning Zwift responded but genuinely returned zero finishers.
                val resultsConfirmed = when {
                    entries.isNotEmpty() -> true
                    showedUpCount == 0   -> true
                    withinPostingWindow  -> false
                    else -> subgroups.isNotEmpty() && subgroupResults.any { it.first }
                }

                // Keep DB in sync with the freshest count from the API.
                withContext(Dispatchers.IO) { statsRepository.updateCompleted(eventId, entries.size) }

                _uiState.value = ParticipantUiState.Success(
                    eventName        = detail.name ?: "Event",
                    entries          = entries,
                    resultsConfirmed = resultsConfirmed,
                )
            }.onFailure { e ->
                _uiState.value = ParticipantUiState.Error(e.message ?: "Failed to load participants")
            }
        }
    }

    companion object {
        // Zwift typically publishes race results within minutes of event end; 30 minutes is
        // a generous buffer that prevents a successful-but-empty API response from being
        // misread as "no finishers" while results are still being processed.
        private const val RESULTS_POSTING_GRACE_MS = 30 * 60 * 1000L
    }
}
