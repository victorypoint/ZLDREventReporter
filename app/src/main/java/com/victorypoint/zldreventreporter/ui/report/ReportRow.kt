package com.victorypoint.zldreventreporter.ui.report

import com.victorypoint.zldreventreporter.data.db.EventStatEntity

data class ReportRow(
    val periodLabel: String,
    val totalSignedUp: Int,
    val totalShowedUp: Int,
    val totalCompleted: Int,
    val showUpRate: Float,
    val completionRate: Float,
    val fromMillis: Long,
    val toMillis: Long,
    val events: List<EventStatEntity> = emptyList(),
)

data class SeriesRow(
    val seriesName: String,
    val dayOfWeek: String,               // e.g. "Sunday", "Tuesday"
    val sport: String,
    val events: List<EventStatEntity>,   // sorted descending by date
    val totalSignedUp: Int,
    val totalShowedUp: Int,
    val totalFinished: Int,
    val attRate: Float,
    val cmpRate: Float,
)
