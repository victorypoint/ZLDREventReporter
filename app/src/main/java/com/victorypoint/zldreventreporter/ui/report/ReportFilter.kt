package com.victorypoint.zldreventreporter.ui.report

enum class PeriodFilter { DAY, WEEK, MONTH, YEAR }
enum class SportFilter  { ALL, RUNNING, CYCLING }

data class ReportFilter(
    val period: PeriodFilter = PeriodFilter.WEEK,
    val sport: SportFilter   = SportFilter.ALL,
)
