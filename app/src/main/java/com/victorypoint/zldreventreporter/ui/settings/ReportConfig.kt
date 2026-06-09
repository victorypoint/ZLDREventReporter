package com.victorypoint.zldreventreporter.ui.settings

import com.victorypoint.zldreventreporter.ui.report.SportFilter

enum class DateRangeOption(val label: String) {
    ALL_TIME("All time"),
    THIS_YEAR("This year"),
    THIS_MONTH("This month"),
    LAST_30_DAYS("Last 30 days"),
    CUSTOM("Custom range"),
}

enum class ReportFormat(val label: String, val mimeType: String, val extension: String) {
    CSV("CSV", "text/csv", "csv"),
    PLAIN_TEXT("Plain text", "text/plain", "txt"),
    HTML("HTML (printable)", "text/html", "html"),
    PDF("PDF", "application/pdf", "pdf"),
}

enum class EventScope(val label: String) {
    COMPLETED("Completed events"),
    UPCOMING("Upcoming events"),
    BOTH("Completed & upcoming"),
}

enum class ReportView(val label: String) {
    DATE("By date"),
    SERIES("By series"),
}

data class ReportConfig(
    val sport: SportFilter = SportFilter.ALL,
    val dateRange: DateRangeOption = DateRangeOption.ALL_TIME,
    val customFromMs: Long = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000,
    val customToMs: Long = System.currentTimeMillis(),
    val eventScope: EventScope = EventScope.COMPLETED,
    val reportView: ReportView = ReportView.DATE,
    val format: ReportFormat = ReportFormat.CSV,
)
