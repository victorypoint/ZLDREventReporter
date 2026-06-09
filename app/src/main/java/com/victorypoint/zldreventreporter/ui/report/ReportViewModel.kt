package com.victorypoint.zldreventreporter.ui.report

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorypoint.zldreventreporter.data.SyncMetadataStore
import com.victorypoint.zldreventreporter.data.db.EventStatEntity
import com.victorypoint.zldreventreporter.data.db.EventStatsRepository
import com.victorypoint.zldreventreporter.data.events.EventSyncRepository
import com.victorypoint.zldreventreporter.data.events.SyncProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

enum class ViewMode { DATE, SERIES }

sealed interface ReportUiState {
    data object Loading : ReportUiState
    data object Success : ReportUiState
    data class Error(val message: String) : ReportUiState
    data object Empty : ReportUiState
}

class ReportViewModel(
    private val statsRepository: EventStatsRepository,
    private val syncRepository: EventSyncRepository,
    private val syncMetadata: SyncMetadataStore,
) : ViewModel(), DefaultLifecycleObserver {

    private val _filter = MutableStateFlow(ReportFilter())
    val filter: StateFlow<ReportFilter> = _filter.asStateFlow()

    private val _uiState = MutableStateFlow<ReportUiState>(ReportUiState.Loading)
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()

    val reportRows: StateFlow<List<ReportRow>> = combine(
        statsRepository.getAllStats(),
        _filter,
    ) { stats, filter ->
        _uiState.value = when {
            stats.isEmpty() -> ReportUiState.Empty
            else -> ReportUiState.Success
        }
        buildReport(stats, filter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val upcomingRows: StateFlow<List<ReportRow>> = combine(
        statsRepository.getAllStats(),
        _filter,
    ) { stats, filter ->
        buildUpcomingReport(stats, filter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val seriesRows: StateFlow<List<SeriesRow>> = combine(
        statsRepository.getAllStats(),
        _filter,
    ) { stats, filter ->
        buildSeriesReport(stats, filter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val upcomingSeriesRows: StateFlow<List<SeriesRow>> = combine(
        statsRepository.getAllStats(),
        _filter,
    ) { stats, filter ->
        buildUpcomingSeriesReport(stats, filter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _viewMode = MutableStateFlow(ViewMode.DATE)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    fun toggleViewMode() {
        _viewMode.value = if (_viewMode.value == ViewMode.DATE) ViewMode.SERIES else ViewMode.DATE
    }

    fun setPeriod(period: PeriodFilter) {
        _filter.value = _filter.value.copy(period = period)
    }

    fun setSport(sport: SportFilter) {
        _filter.value = _filter.value.copy(sport = sport)
    }

    private var syncJob: Job? = null
    private var inProgressJob: Job? = null

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        startSync()
    }

    // Fires when the app returns to the foreground — keeps sign-up counts fresh
    // between sessions without requiring a full app restart.
    override fun onStart(owner: LifecycleOwner) = startSync()

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        super.onCleared()
    }

    fun startSync() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            syncRepository.syncNow().collect { progress ->
                _syncProgress.value = progress
                if (progress is SyncProgress.Error) {
                    _uiState.value = ReportUiState.Error(progress.message)
                }
                if (progress is SyncProgress.Success) {
                    startInProgressPolling()
                }
            }
        }
    }

    private fun startInProgressPolling() {
        if (inProgressJob?.isActive == true) return
        inProgressJob = viewModelScope.launch {
            while (true) {
                val remaining = syncRepository.refreshInProgressCounts()
                if (remaining == 0) break
                if (syncRepository.anyReadyForPass2()) {
                    startSync()
                    break
                }
                delay(60_000L)
            }
        }
    }

    private val _expandedKeys = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val expandedKeys: StateFlow<Map<String, Boolean>> = _expandedKeys.asStateFlow()

    fun setExpanded(key: String, value: Boolean) {
        _expandedKeys.value = _expandedKeys.value + (key to value)
    }

    fun clearSyncError() {
        if (_uiState.value is ReportUiState.Error) _uiState.value = ReportUiState.Success
        if (_syncProgress.value is SyncProgress.Error) _syncProgress.value = null
    }

    private fun buildUpcomingSeriesReport(stats: List<EventStatEntity>, filter: ReportFilter): List<SeriesRow> {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        return stats.filter { it.fetchedAt == 0L && it.eventDate > now }
            .let { upcoming ->
                when (filter.sport) {
                    SportFilter.ALL     -> upcoming
                    SportFilter.CYCLING -> upcoming.filter { it.sport == "CYCLING" }
                    SportFilter.RUNNING -> upcoming.filter { it.sport == "RUNNING" }
                }
            }
            .groupBy { entity ->
                val dow = Instant.ofEpochMilli(entity.eventDate).atZone(zone).dayOfWeek
                Pair(entity.eventName, dow)
            }
            .entries
            .sortedBy { (_, events) -> events.minOf { it.eventDate } }
            .map { (key, events) ->
                val (name, dow) = key
                val sorted = events.sortedBy { it.eventDate }
                SeriesRow(
                    seriesName    = name,
                    dayOfWeek     = dow.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                    sport         = sorted.first().sport,
                    events        = sorted,
                    totalSignedUp = events.sumOf { it.countSignedUp },
                    totalShowedUp = 0,
                    totalFinished = 0,
                    attRate       = 0f,
                    cmpRate       = 0f,
                )
            }
    }

    private fun buildSeriesReport(stats: List<EventStatEntity>, filter: ReportFilter): List<SeriesRow> {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        return stats.filter { it.eventDate <= now }
            .let { completed ->
                when (filter.sport) {
                    SportFilter.ALL     -> completed
                    SportFilter.CYCLING -> completed.filter { it.sport == "CYCLING" }
                    SportFilter.RUNNING -> completed.filter { it.sport == "RUNNING" }
                }
            }
            .groupBy { entity ->
                val dow = Instant.ofEpochMilli(entity.eventDate).atZone(zone).dayOfWeek
                Pair(entity.eventName, dow)
            }
            .entries
            .sortedByDescending { (_, events) -> events.maxOf { it.eventDate } }
            .map { (key, events) ->
                val (name, dow) = key
                val sorted   = events.sortedByDescending { it.eventDate }
                val signedUp = events.sumOf { it.countSignedUp }
                val showedUp = events.sumOf { it.countShowedUp }
                val finished = events.sumOf { it.countCompleted }
                SeriesRow(
                    seriesName    = name,
                    dayOfWeek     = dow.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                    sport         = sorted.first().sport,
                    events        = sorted,
                    totalSignedUp = signedUp,
                    totalShowedUp = showedUp,
                    totalFinished = finished,
                    attRate       = if (signedUp > 0) showedUp.toFloat()  / signedUp * 100f else 0f,
                    cmpRate       = if (showedUp > 0) finished.toFloat() / showedUp * 100f else 0f,
                )
            }
    }

    private fun buildUpcomingReport(stats: List<EventStatEntity>, filter: ReportFilter): List<ReportRow> {
        val now = System.currentTimeMillis()
        val filtered = stats.filter { it.fetchedAt == 0L && it.eventDate > now }.let { upcoming ->
            when (filter.sport) {
                SportFilter.ALL     -> upcoming
                SportFilter.CYCLING -> upcoming.filter { it.sport == "CYCLING" }
                SportFilter.RUNNING -> upcoming.filter { it.sport == "RUNNING" }
            }
        }
        return filtered
            .groupBy { toBucketKey(it.eventDate, filter.period) }
            .entries
            .sortedBy { (key, _) -> key.sortDate }
            .map { (key, events) ->
                ReportRow(
                    periodLabel    = key.label,
                    totalSignedUp  = events.sumOf { it.countSignedUp },
                    totalShowedUp  = 0,
                    totalCompleted = 0,
                    showUpRate     = 0f,
                    completionRate = 0f,
                    fromMillis     = key.fromMillis,
                    toMillis       = key.toMillis,
                    events         = events.sortedBy { it.eventDate },
                )
            }
    }

    private fun buildReport(stats: List<EventStatEntity>, filter: ReportFilter): List<ReportRow> {
        val now = System.currentTimeMillis()
        val sportFiltered = stats.filter { it.eventDate <= now }.let { completed ->
            when (filter.sport) {
                SportFilter.ALL      -> completed
                SportFilter.CYCLING  -> completed.filter { it.sport == "CYCLING" }
                SportFilter.RUNNING  -> completed.filter { it.sport == "RUNNING" }
            }
        }

        return sportFiltered
            .groupBy { toBucketKey(it.eventDate, filter.period) }
            .entries
            .sortedByDescending { (key, _) -> key.sortDate }
            .map { (key, rows) ->
                val signedUp  = rows.sumOf { it.countSignedUp }
                val showedUp  = rows.sumOf { it.countShowedUp }
                val completed = rows.sumOf { it.countCompleted }
                ReportRow(
                    periodLabel    = key.label,
                    totalSignedUp  = signedUp,
                    totalShowedUp  = showedUp,
                    totalCompleted = completed,
                    showUpRate     = if (signedUp  > 0) showedUp.toFloat()  / signedUp  * 100f else 0f,
                    completionRate = if (showedUp  > 0) completed.toFloat() / showedUp  * 100f else 0f,
                    fromMillis     = key.fromMillis,
                    toMillis       = key.toMillis,
                    events         = rows.sortedByDescending { it.eventDate },
                )
            }
    }

    private data class BucketKey(
        val label: String,
        val sortDate: LocalDate,
        val fromMillis: Long,
        val toMillis: Long,
    )

    private fun toBucketKey(epochMillis: Long, period: PeriodFilter): BucketKey {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return when (period) {
            PeriodFilter.DAY -> {
                val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val end   = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                BucketKey(
                    label      = date.format(DateTimeFormatter.ofPattern("MMM d")),
                    sortDate   = date,
                    fromMillis = start,
                    toMillis   = end,
                )
            }
            PeriodFilter.WEEK -> {
                val wf = WeekFields.of(Locale.getDefault())
                val weekStart = date.with(wf.dayOfWeek(), 1)
                val start = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
                val end   = weekStart.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
                BucketKey(
                    label      = "Week of " + weekStart.format(DateTimeFormatter.ofPattern("MMM d")),
                    sortDate   = weekStart,
                    fromMillis = start,
                    toMillis   = end,
                )
            }
            PeriodFilter.MONTH -> {
                val monthStart = date.withDayOfMonth(1)
                val start = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()
                val end   = monthStart.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
                BucketKey(
                    label      = date.format(DateTimeFormatter.ofPattern("MMM yyyy")),
                    sortDate   = monthStart,
                    fromMillis = start,
                    toMillis   = end,
                )
            }
            PeriodFilter.YEAR -> {
                val yearStart = date.withDayOfYear(1)
                val start = yearStart.atStartOfDay(zone).toInstant().toEpochMilli()
                val end   = yearStart.plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()
                BucketKey(
                    label      = date.year.toString(),
                    sortDate   = yearStart,
                    fromMillis = start,
                    toMillis   = end,
                )
            }
        }
    }
}
