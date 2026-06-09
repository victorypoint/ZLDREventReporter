package com.victorypoint.zldreventreporter.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorypoint.zldreventreporter.data.db.EventStatsRepository
import com.victorypoint.zldreventreporter.data.events.EventSyncRepository
import com.victorypoint.zldreventreporter.data.events.EventsApi
import com.victorypoint.zldreventreporter.data.events.SyncProgress
import com.victorypoint.zldreventreporter.data.events.dto.ZwiftEventDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val ZWIFT_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]X")

private fun parseEventStart(text: String): Instant? =
    runCatching { Instant.parse(text) }
        .recoverCatching { OffsetDateTime.parse(text, ZWIFT_DATE_FMT).toInstant() }
        .getOrNull()

sealed interface EventDetailUiState {
    data object Loading : EventDetailUiState
    data class Success(val event: ZwiftEventDto, val isCompleted: Boolean) : EventDetailUiState
    data class Error(val message: String) : EventDetailUiState
}

sealed interface RefetchState {
    data object Idle : RefetchState
    data object InProgress : RefetchState
    data class Done(val gotData: Boolean) : RefetchState
}

class EventDetailViewModel(
    private val eventsApi: EventsApi,
    private val statsRepository: EventStatsRepository,
    private val syncRepository: EventSyncRepository,
    private val eventId: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow<EventDetailUiState>(EventDetailUiState.Loading)
    val uiState: StateFlow<EventDetailUiState> = _uiState.asStateFlow()

    private val _canRefetch = MutableStateFlow(false)
    val canRefetch: StateFlow<Boolean> = _canRefetch.asStateFlow()

    private val _refetchState = MutableStateFlow<RefetchState>(RefetchState.Idle)
    val refetchState: StateFlow<RefetchState> = _refetchState.asStateFlow()

    init { load() }

    fun retry() = load()

    private fun load() {
        _uiState.value = EventDetailUiState.Loading
        viewModelScope.launch {
            runCatching {
                val dto = withContext(Dispatchers.IO) { eventsApi.getEventWithCounts(eventId) }
                val startMs = dto.eventStart?.let { parseEventStart(it) }?.toEpochMilli() ?: 0L
                val isCompleted = startMs > 0L && startMs <= System.currentTimeMillis()
                _uiState.value = EventDetailUiState.Success(dto, isCompleted)
                if (isCompleted) {
                    val stat = withContext(Dispatchers.IO) { statsRepository.getById(eventId) }
                    _canRefetch.value = stat != null &&
                            stat.countSignedUp == 0 &&
                            stat.countShowedUp == 0 &&
                            stat.countCompleted == 0
                }
            }.onFailure { e ->
                _uiState.value = EventDetailUiState.Error(e.message ?: "Failed to load event")
            }
        }
    }

    fun refetch() {
        if (_refetchState.value is RefetchState.InProgress) return
        _refetchState.value = RefetchState.InProgress
        viewModelScope.launch {
            withContext(Dispatchers.IO) { statsRepository.resetFetchedAt(eventId) }
            runCatching {
                syncRepository.syncNow().collect { progress ->
                    if (progress is SyncProgress.Error) error(progress.message)
                }
            }
            val stat = withContext(Dispatchers.IO) { statsRepository.getById(eventId) }
            val gotData = stat != null &&
                    (stat.countSignedUp > 0 || stat.countShowedUp > 0 || stat.countCompleted > 0)
            _canRefetch.value = !gotData
            _refetchState.value = RefetchState.Done(gotData)
        }
    }
}
