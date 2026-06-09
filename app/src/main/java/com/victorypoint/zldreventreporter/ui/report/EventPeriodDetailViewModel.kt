package com.victorypoint.zldreventreporter.ui.report

import androidx.lifecycle.ViewModel
import com.victorypoint.zldreventreporter.data.db.EventStatEntity
import com.victorypoint.zldreventreporter.data.db.EventStatsRepository
import kotlinx.coroutines.flow.Flow

class EventPeriodDetailViewModel(
    private val statsRepository: EventStatsRepository,
    val fromMillis: Long,
    val toMillis: Long,
    val sport: String,
) : ViewModel() {

    val events: Flow<List<EventStatEntity>> =
        statsRepository.getStatsForPeriod(fromMillis, toMillis - 1, sport)
}
