package com.victorypoint.zldreventreporter.ui.settings

import android.app.Application
import android.graphics.Color
import android.util.Log
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.squareup.moshi.Types
import com.victorypoint.zldreventreporter.ZldrReporterApplication
import com.victorypoint.zldreventreporter.data.SyncMetadataStore
import com.victorypoint.zldreventreporter.data.auth.AuthRepository
import com.victorypoint.zldreventreporter.data.db.EventStatEntity
import com.victorypoint.zldreventreporter.data.db.EventStatsRepository
import com.victorypoint.zldreventreporter.ui.report.SportFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

sealed interface ImportResult {
    data class Success(val count: Int) : ImportResult
    data class Error(val message: String) : ImportResult
}

data class SharePayload(val uri: Uri, val mimeType: String)

private const val TAG = "SettingsViewModel"

class SettingsViewModel(
    private val application: Application,
    private val authRepository: AuthRepository,
    private val statsRepository: EventStatsRepository,
    private val syncMetadata: SyncMetadataStore,
) : ViewModel() {

    val username: String? get() = authRepository.tokenStore.username

    val cachedEventCount = statsRepository.getCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val lastSyncAt: Long get() = syncMetadata.lastSyncAt

    // ── Clear cache ──────────────────────────────────────────────────────────

    private val _clearConfirmVisible = MutableStateFlow(false)
    val clearConfirmVisible = _clearConfirmVisible.asStateFlow()

    fun requestClearCache() { _clearConfirmVisible.value = true }
    fun dismissClearConfirm() { _clearConfirmVisible.value = false }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { statsRepository.deleteAll() }
            syncMetadata.lastSyncAt     = 0L
            syncMetadata.lastNameScanAt = 0L
            _clearConfirmVisible.value  = false
        }
    }

    // ── Logout ───────────────────────────────────────────────────────────────

    private val _logoutConfirmVisible = MutableStateFlow(false)
    val logoutConfirmVisible = _logoutConfirmVisible.asStateFlow()

    fun requestLogout() { _logoutConfirmVisible.value = true }
    fun dismissLogoutConfirm() { _logoutConfirmVisible.value = false }

    fun logout() { authRepository.logout() }

    // ── Export ───────────────────────────────────────────────────────────────

    private val _exportShareUri = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val exportShareUri = _exportShareUri.asSharedFlow()

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError = _exportError.asStateFlow()

    fun dismissExportError() { _exportError.value = null }

    fun exportData() {
        viewModelScope.launch {
            try {
                val shareUri = withContext(Dispatchers.IO) { buildExportFile() }
                _exportShareUri.emit(shareUri)
            } catch (e: Exception) {
                _exportError.value = "Export failed: ${e.message}"
            }
        }
    }

    private suspend fun buildExportFile(): Uri {
        val records = statsRepository.getAllForExport()
        val moshi = (application as ZldrReporterApplication).moshi
        val type = Types.newParameterizedType(List::class.java, EventStatEntity::class.java)
        val adapter = moshi.adapter<List<EventStatEntity>>(type)
        val json = adapter.toJson(records)

        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val file = File(application.cacheDir, "zldr_events_backup_$dateStr.json")
        file.writeText(json)

        return fileProviderUri(file)
    }

    // ── Import ───────────────────────────────────────────────────────────────

    private val _importResult = MutableStateFlow<ImportResult?>(null)
    val importResult = _importResult.asStateFlow()

    fun dismissImportResult() { _importResult.value = null }

    fun importData(uri: Uri) {
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val json = application.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("Could not open file")

                    val moshi = (application as ZldrReporterApplication).moshi
                    val type = Types.newParameterizedType(List::class.java, EventStatEntity::class.java)
                    val adapter = moshi.adapter<List<EventStatEntity>>(type)
                    val records = adapter.fromJson(json) ?: error("Invalid backup file")
                    if (records.isEmpty()) error("Backup file contains no events")

                    records.forEach { statsRepository.upsert(it) }
                    records.size
                }
                _importResult.value = ImportResult.Success(count)
            } catch (e: Exception) {
                _importResult.value = ImportResult.Error("Import failed: ${e.message}")
            }
        }
    }

    // ── Report generation ────────────────────────────────────────────────────

    private val _reportConfig = MutableStateFlow<ReportConfig?>(null)
    val reportConfig = _reportConfig.asStateFlow()

    private val _isGeneratingReport = MutableStateFlow(false)
    val isGeneratingReport = _isGeneratingReport.asStateFlow()

    private val _reportSharePayload = MutableSharedFlow<SharePayload>(extraBufferCapacity = 1)
    val reportSharePayload = _reportSharePayload.asSharedFlow()

    private val _reportError = MutableStateFlow<String?>(null)
    val reportError = _reportError.asStateFlow()

    fun openReportGenerator() { _reportConfig.value = ReportConfig() }
    fun updateReportConfig(config: ReportConfig) { _reportConfig.value = config }
    fun dismissReportError() { _reportError.value = null }

    fun dismissReportGenerator() {
        if (!_isGeneratingReport.value) _reportConfig.value = null
    }

    fun generateReport() {
        val config = _reportConfig.value ?: return
        _isGeneratingReport.value = true
        viewModelScope.launch {
            try {
                val payload = withContext(Dispatchers.IO) { buildReportFile(config) }
                _reportConfig.value = null
                _reportSharePayload.emit(payload)
            } catch (e: Exception) {
                _reportError.value = e.message ?: "Report generation failed"
            } finally {
                _isGeneratingReport.value = false
            }
        }
    }

    private suspend fun buildReportFile(config: ReportConfig): SharePayload {
        val events = fetchFilteredEvents(config)
        if (events.isEmpty()) error("No events match the selected filters")

        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val file = File(application.cacheDir, "zldr_report_$dateStr.${config.format.extension}")

        val isSeries = config.reportView == ReportView.SERIES
        when (config.format) {
            ReportFormat.CSV        -> file.writeText(
                if (isSeries) generateCsvSeries(events) else generateCsv(events), Charsets.UTF_8)
            ReportFormat.PLAIN_TEXT -> file.writeText(
                if (isSeries) generatePlainTextSeries(events, config) else generatePlainText(events, config), Charsets.UTF_8)
            ReportFormat.HTML       -> file.writeText(
                if (isSeries) generateHtmlSeries(events, config) else generateHtml(events, config), Charsets.UTF_8)
            ReportFormat.PDF        ->
                if (isSeries) generatePdfSeries(events, config, file) else generatePdf(events, config, file)
        }

        return SharePayload(fileProviderUri(file), config.format.mimeType)
    }

    private suspend fun fetchFilteredEvents(config: ReportConfig): List<EventStatEntity> {
        val all  = statsRepository.getAllForExport()
        val now  = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        val fromMs: Long = when (config.dateRange) {
            DateRangeOption.ALL_TIME    -> 0L
            DateRangeOption.THIS_YEAR   -> LocalDate.now().withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
            DateRangeOption.THIS_MONTH  -> LocalDate.now().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
            DateRangeOption.LAST_30_DAYS -> now - 30L * 24 * 60 * 60 * 1000
            DateRangeOption.CUSTOM      -> config.customFromMs
        }
        val toMs: Long = when (config.dateRange) {
            DateRangeOption.ALL_TIME    -> Long.MAX_VALUE
            DateRangeOption.THIS_YEAR   -> LocalDate.now().withDayOfYear(1).plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()
            DateRangeOption.THIS_MONTH  -> LocalDate.now().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
            DateRangeOption.LAST_30_DAYS -> now
            DateRangeOption.CUSTOM      -> config.customToMs
        }

        return all.filter { e ->
            val inRange = e.eventDate in fromMs..toMs
            val inSport = when (config.sport) {
                SportFilter.ALL     -> true
                SportFilter.CYCLING -> e.sport == "CYCLING"
                SportFilter.RUNNING -> e.sport == "RUNNING"
            }
            val inScope = when (config.eventScope) {
                EventScope.COMPLETED -> e.eventDate <= now
                EventScope.UPCOMING  -> e.fetchedAt == 0L && e.eventDate > now
                EventScope.BOTH      -> true
            }
            inRange && inSport && inScope
        }
    }

    private fun generateCsv(events: List<EventStatEntity>): String {
        val sb        = StringBuilder()
        val fmt       = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val now       = System.currentTimeMillis()
        val completed = events.filter { it.eventDate <= now }.sortedByDescending { it.eventDate }
        val upcoming  = events.filter { it.fetchedAt == 0L && it.eventDate > now }.sortedBy { it.eventDate }

        sb.appendLine("Event Name,Date,Sport,Signed Up,Showed Up,Finished,Att %,Cmp %")
        completed.forEach { e ->
            val att  = if (e.countSignedUp > 0) "%.1f".format(e.countShowedUp.toFloat()  / e.countSignedUp * 100f) else "0.0"
            val cmp  = if (e.countShowedUp > 0) "%.1f".format(e.countCompleted.toFloat() / e.countShowedUp * 100f) else "0.0"
            val name = "\"${e.eventName.replace("\"", "\"\"")}\""
            sb.appendLine("$name,${fmt.format(Date(e.eventDate))},${e.sport},${e.countSignedUp},${e.countShowedUp},${e.countCompleted},$att,$cmp")
        }
        upcoming.forEach { e ->
            val name = "\"${e.eventName.replace("\"", "\"\"")}\""
            sb.appendLine("$name,${fmt.format(Date(e.eventDate))},${e.sport},${e.countSignedUp},—,—,—,—")
        }
        return sb.toString()
    }

    private fun generatePlainText(events: List<EventStatEntity>, config: ReportConfig): String {
        val sb        = StringBuilder()
        val hdrFmt    = SimpleDateFormat("MMM d, yyyy", Locale.US)
        val evtFmt    = SimpleDateFormat("MMM d, yyyy", Locale.US)
        val now       = System.currentTimeMillis()
        val completed = events.filter { it.eventDate <= now }.sortedByDescending { it.eventDate }
        val upcoming  = events.filter { it.fetchedAt == 0L && it.eventDate > now }.sortedBy { it.eventDate }

        val totalSigned   = completed.sumOf { it.countSignedUp }
        val totalShowedUp = completed.sumOf { it.countShowedUp }
        val totalFinished = completed.sumOf { it.countCompleted }
        val avgAtt        = if (totalSigned   > 0) totalShowedUp.toFloat()  / totalSigned   * 100f else 0f
        val avgCmp        = if (totalShowedUp > 0) totalFinished.toFloat()  / totalShowedUp * 100f else 0f

        sb.appendLine("ZLDR Event Report")
        sb.appendLine("Generated : ${hdrFmt.format(Date())}")
        sb.appendLine("Sport     : ${sportLabel(config.sport)}")
        sb.appendLine("Period    : ${dateRangeLabel(config)}")
        sb.appendLine("Scope     : ${config.eventScope.label}")
        sb.appendLine()
        sb.appendLine("Summary")
        sb.appendLine("───────────────────────────────")
        if (completed.isNotEmpty()) {
            sb.appendLine("Completed   : ${completed.size}")
            sb.appendLine("Signed Up   : $totalSigned")
            sb.appendLine("Showed Up   : $totalShowedUp")
            sb.appendLine("Finished    : $totalFinished")
            sb.appendLine("Avg Att     : ${"%.1f".format(avgAtt)}%")
            sb.appendLine("Avg Cmp     : ${"%.1f".format(avgCmp)}%")
        }
        if (upcoming.isNotEmpty()) {
            if (completed.isNotEmpty()) sb.appendLine()
            sb.appendLine("Upcoming    : ${upcoming.size}")
            sb.appendLine("Signed Up   : ${upcoming.sumOf { it.countSignedUp }}")
        }
        sb.appendLine()

        if (completed.isNotEmpty()) {
            sb.appendLine("Completed Events")
            sb.appendLine("───────────────────────────────")
            completed.forEach { e ->
                val att = if (e.countSignedUp > 0) "%.1f".format(e.countShowedUp.toFloat()  / e.countSignedUp * 100f) else "0.0"
                val cmp = if (e.countShowedUp > 0) "%.1f".format(e.countCompleted.toFloat() / e.countShowedUp * 100f) else "0.0"
                sb.appendLine("${evtFmt.format(Date(e.eventDate))}  ${e.eventName}")
                sb.appendLine("  Sport: ${e.sport.lowercase().replaceFirstChar { it.uppercase() }}   Signed Up: ${e.countSignedUp}   Showed Up: ${e.countShowedUp}   Finished: ${e.countCompleted}   Att: $att%   Cmp: $cmp%")
                sb.appendLine()
            }
        }
        if (upcoming.isNotEmpty()) {
            sb.appendLine("Upcoming Events")
            sb.appendLine("───────────────────────────────")
            upcoming.forEach { e ->
                sb.appendLine("${evtFmt.format(Date(e.eventDate))}  ${e.eventName}")
                sb.appendLine("  Sport: ${e.sport.lowercase().replaceFirstChar { it.uppercase() }}   Signed Up: ${e.countSignedUp}")
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    private fun generateHtml(events: List<EventStatEntity>, config: ReportConfig): String {
        val hdrFmt    = SimpleDateFormat("MMM d, yyyy", Locale.US)
        val evtFmt    = SimpleDateFormat("MMM d, yyyy", Locale.US)
        val now       = System.currentTimeMillis()
        val completed = events.filter { it.eventDate <= now }.sortedByDescending { it.eventDate }
        val upcoming  = events.filter { it.fetchedAt == 0L && it.eventDate > now }.sortedBy { it.eventDate }
        val bothExist = completed.isNotEmpty() && upcoming.isNotEmpty()

        val totalSigned   = completed.sumOf { it.countSignedUp }
        val totalShowedUp = completed.sumOf { it.countShowedUp }
        val totalFinished = completed.sumOf { it.countCompleted }
        val avgAtt        = if (totalSigned   > 0) totalShowedUp.toFloat()  / totalSigned   * 100f else 0f
        val avgCmp        = if (totalShowedUp > 0) totalFinished.toFloat()  / totalShowedUp * 100f else 0f

        val colHeaders = "<tr><th>Date</th><th>Event</th><th>Sport</th><th>Signed Up</th><th>Showed Up</th><th>Finished</th><th>Att %</th><th>Cmp %</th></tr>"

        val completedRows = completed.joinToString("\n") { e ->
            val att   = if (e.countSignedUp > 0) "%.1f".format(e.countShowedUp.toFloat()  / e.countSignedUp * 100f) else "0.0"
            val cmp   = if (e.countShowedUp > 0) "%.1f".format(e.countCompleted.toFloat() / e.countShowedUp * 100f) else "0.0"
            val sport = e.sport.lowercase().replaceFirstChar { it.uppercase() }
            "<tr><td>${evtFmt.format(Date(e.eventDate))}</td><td>${he(e.eventName)}</td>" +
                "<td>$sport</td><td>${e.countSignedUp}</td><td>${e.countShowedUp}</td>" +
                "<td>${e.countCompleted}</td><td>$att%</td><td>$cmp%</td></tr>"
        }
        val upcomingRows = upcoming.joinToString("\n") { e ->
            val sport = e.sport.lowercase().replaceFirstChar { it.uppercase() }
            "<tr><td>${evtFmt.format(Date(e.eventDate))}</td><td>${he(e.eventName)}</td>" +
                "<td>$sport</td><td>${e.countSignedUp}</td><td>—</td><td>—</td><td>—</td><td>—</td></tr>"
        }

        val completedSection = if (completed.isNotEmpty()) buildString {
            if (bothExist) appendLine("<h2>Completed Events</h2>")
            appendLine("<table class=\"ev\">$colHeaders")
            appendLine(completedRows)
            append("</table>")
        } else ""

        val upcomingSection = if (upcoming.isNotEmpty()) buildString {
            if (bothExist) appendLine("<h2>Upcoming Events</h2>")
            appendLine("<table class=\"ev\">$colHeaders")
            appendLine(upcomingRows)
            append("</table>")
        } else ""

        val summaryRows = buildString {
            if (completed.isNotEmpty()) {
                appendLine("<tr><td>Completed Events</td><td><strong>${completed.size}</strong></td></tr>")
                appendLine("<tr><td>Total Signed Up</td><td><strong>$totalSigned</strong></td></tr>")
                appendLine("<tr><td>Total Showed Up</td><td><strong>$totalShowedUp</strong></td></tr>")
                appendLine("<tr><td>Total Finished</td><td><strong>$totalFinished</strong></td></tr>")
                appendLine("<tr><td>Avg Attendance</td><td><strong>${"%.1f".format(avgAtt)}%</strong></td></tr>")
                append("<tr><td>Avg Completion</td><td><strong>${"%.1f".format(avgCmp)}%</strong></td></tr>")
            }
            if (upcoming.isNotEmpty()) {
                if (completed.isNotEmpty()) appendLine()
                appendLine("<tr><td>Upcoming Events</td><td><strong>${upcoming.size}</strong></td></tr>")
                append("<tr><td>Upcoming Signed Up</td><td><strong>${upcoming.sumOf { it.countSignedUp }}</strong></td></tr>")
            }
        }

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZLDR Event Report</title>
<style>
body{font-family:Arial,sans-serif;margin:24px;color:#222}
h1{color:#1565C0;margin-bottom:4px}
h2{color:#1565C0;font-size:15px;margin:20px 0 8px 0}
.meta{color:#666;font-size:13px;margin-bottom:20px}
.summary{background:#f0f4ff;padding:14px 18px;border-radius:8px;margin-bottom:20px;display:inline-block}
.summary td{padding:3px 20px 3px 0;font-size:14px}
.summary td:first-child{color:#555}
table.ev{width:100%;border-collapse:collapse;font-size:13px}
table.ev th{background:#1565C0;color:#fff;padding:8px 10px;text-align:left;white-space:nowrap}
table.ev td{padding:7px 10px;border-bottom:1px solid #eee;vertical-align:top}
table.ev tr:nth-child(even){background:#f9f9f9}
@media print{.meta{color:#333}}
</style>
</head>
<body>
<h1>ZLDR Event Report</h1>
<p class="meta">
  Generated: ${hdrFmt.format(Date())} &nbsp;·&nbsp;
  Sport: ${sportLabel(config.sport)} &nbsp;·&nbsp;
  Period: ${he(dateRangeLabel(config))} &nbsp;·&nbsp;
  ${config.eventScope.label}
</p>
<div class="summary">
<table>
$summaryRows
</table>
</div>
$completedSection
$upcomingSection
</body>
</html>"""
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun fileProviderUri(file: File): Uri =
        FileProvider.getUriForFile(application, "${application.packageName}.fileprovider", file)

    private fun sportLabel(sport: SportFilter) = when (sport) {
        SportFilter.ALL     -> "All sports"
        SportFilter.CYCLING -> "Cycling"
        SportFilter.RUNNING -> "Running"
    }

    private fun dateRangeLabel(config: ReportConfig): String {
        if (config.dateRange != DateRangeOption.CUSTOM) return config.dateRange.label
        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.US)
        return "${fmt.format(Date(config.customFromMs))} – ${fmt.format(Date(config.customToMs))}"
    }

    private fun he(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ── PDF generation ───────────────────────────────────────────────────────

    private fun generatePdf(events: List<EventStatEntity>, config: ReportConfig, outFile: File) {
        val pdfDoc = PdfDocument()

        // A4 at 72 DPI
        val PW = 595f; val PH = 842f
        val ML = 40f;  val MR = 40f; val MT = 40f; val MB = 50f
        val TABLE_RIGHT = PW - MR   // 555

        val blue      = Color.rgb(0x15, 0x65, 0xC0)
        val lightBlue = Color.rgb(0xF0, 0xF4, 0xFF)
        val altRow    = Color.rgb(0xF9, 0xF9, 0xF9)
        val divClr    = Color.rgb(0xE0, 0xE0, 0xE0)

        fun p(size: Float, clr: Int = Color.BLACK, bold: Boolean = false) = Paint().apply {
            color = clr; textSize = size; isAntiAlias = true
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val titleP   = p(18f, blue, bold = true)
        val metaP    = p(8f,  Color.GRAY)
        val labelP   = p(9f,  Color.BLACK, bold = true)
        val sumKeyP  = p(9f,  Color.GRAY)
        val bodyP    = p(8f,  Color.BLACK)
        val colHdrP  = p(8f,  Color.WHITE, bold = true)
        val pageNP   = p(7f,  Color.GRAY)
        val divP     = Paint().apply { color = divClr; strokeWidth = 0.5f; style = Paint.Style.STROKE }
        val blueFill = Paint().apply { color = blue;      style = Paint.Style.FILL }
        val sumFill  = Paint().apply { color = lightBlue; style = Paint.Style.FILL }
        val altFill  = Paint().apply { color = altRow;    style = Paint.Style.FILL }

        // Column layout: absolute x, width, header label, right-align numbers
        // Total table width = TABLE_RIGHT - ML = 555 - 40 = 515pt
        data class Col(val x: Float, val w: Float, val label: String, val right: Boolean = false)
        val cols = listOf(
            Col(ML +   0f,  65f, "Date"),
            Col(ML +  65f, 153f, "Event Name"),
            Col(ML + 218f,  48f, "Sport"),
            Col(ML + 266f,  55f, "Signed Up",  right = true),
            Col(ML + 321f,  45f, "Showed",     right = true),
            Col(ML + 366f,  49f, "Finished",   right = true),
            Col(ML + 415f,  45f, "Att %",      right = true),
            Col(ML + 460f,  55f, "Cmp %",      right = true),
        )

        val ROW_H = 14f
        val COL_H = 15f

        val evtFmt    = SimpleDateFormat("MMM d, yyyy", Locale.US)
        val now       = System.currentTimeMillis()
        val completed = events.filter { it.eventDate <= now }.sortedByDescending { it.eventDate }
        val upcoming  = events.filter { it.fetchedAt == 0L && it.eventDate > now }.sortedBy { it.eventDate }

        val totalSigned   = completed.sumOf { it.countSignedUp }
        val totalShowedUp = completed.sumOf { it.countShowedUp }
        val totalFinished = completed.sumOf { it.countCompleted }
        val avgAtt        = if (totalSigned   > 0) totalShowedUp.toFloat()  / totalSigned   * 100f else 0f
        val avgCmp        = if (totalShowedUp > 0) totalFinished.toFloat()  / totalShowedUp * 100f else 0f

        val upcomingBg = Color.rgb(0xE8, 0xF0, 0xFF)
        val upcomingFill = Paint().apply { color = upcomingBg; style = Paint.Style.FILL }
        val secHdrP = p(9f, blue, bold = true)

        // Mutable page state — captured by local functions below
        var pageNum = 1
        var page    = pdfDoc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), pageNum).create())
        var canvas  = page.canvas
        var y       = MT
        var inTable = false

        fun finishPage() {
            val pg = "Page $pageNum"
            canvas.drawText(pg, (PW - pageNP.measureText(pg)) / 2f, PH - 16f, pageNP)
            pdfDoc.finishPage(page)
        }

        fun newPage() {
            finishPage()
            pageNum++
            page   = pdfDoc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), pageNum).create())
            canvas = page.canvas
            y      = MT
        }

        fun drawColHeaders() {
            canvas.drawRect(ML, y, TABLE_RIGHT, y + COL_H, blueFill)
            cols.forEach { col ->
                val tx = if (col.right) col.x + col.w - colHdrP.measureText(col.label) - 2f
                         else col.x + 2f
                canvas.drawText(col.label, tx, y + COL_H - 4f, colHdrP)
            }
            y += COL_H
            inTable = true
        }

        fun checkBreak(needed: Float) {
            if (y + needed > PH - MB) {
                val wasTable = inTable
                newPage()
                if (wasTable) drawColHeaders()
            }
        }

        // ── Title & meta
        canvas.drawText("ZLDR Event Report", ML, y + titleP.textSize, titleP)
        y += titleP.textSize + 5f

        val metaLine = "Generated: ${evtFmt.format(Date())}   |   " +
                "Sport: ${sportLabel(config.sport)}   |   " +
                "Period: ${dateRangeLabel(config)}   |   " +
                config.eventScope.label
        canvas.drawText(metaLine.ellipsize(metaP, PW - ML - MR), ML, y + metaP.textSize, metaP)
        y += metaP.textSize + 14f

        // ── Summary box
        val summaryEntries = buildList {
            if (completed.isNotEmpty()) {
                add("Completed Events" to completed.size.toString())
                add("Total Signed Up"  to totalSigned.toString())
                add("Total Showed Up"  to totalShowedUp.toString())
                add("Total Finished"   to totalFinished.toString())
                add("Avg Attendance"   to "${"%.1f".format(avgAtt)}%")
                add("Avg Completion"   to "${"%.1f".format(avgCmp)}%")
            }
            if (upcoming.isNotEmpty()) {
                add("Upcoming Events"    to upcoming.size.toString())
                add("Upcoming Signed Up" to upcoming.sumOf { it.countSignedUp }.toString())
            }
        }
        val lineH    = labelP.textSize + 6f
        val sumBoxH  = summaryEntries.size * lineH + 10f * 2
        canvas.drawRoundRect(RectF(ML, y, ML + 230f, y + sumBoxH), 4f, 4f, sumFill)
        var sy = y + 10f + labelP.textSize
        summaryEntries.forEach { (lbl, v) ->
            canvas.drawText(lbl, ML + 6f, sy, sumKeyP)
            canvas.drawText(v,   ML + 145f, sy, labelP)
            sy += lineH
        }
        y += sumBoxH + 18f

        // ── Table
        drawColHeaders()

        fun drawEventRow(e: EventStatEntity, idx: Int, isUpcoming: Boolean) {
            checkBreak(ROW_H)
            if (idx % 2 == 1) canvas.drawRect(ML, y, TABLE_RIGHT, y + ROW_H, altFill)
            val sport = e.sport.lowercase().replaceFirstChar { it.uppercase() }
            val cells = if (isUpcoming) listOf(
                evtFmt.format(Date(e.eventDate)),
                e.eventName.ellipsize(bodyP, cols[1].w - 4f),
                sport,
                e.countSignedUp.toString(), "—", "—", "—", "—",
            ) else {
                val att = if (e.countSignedUp > 0) "${"%.1f".format(e.countShowedUp.toFloat()  / e.countSignedUp * 100f)}%" else "—"
                val cmp = if (e.countShowedUp > 0) "${"%.1f".format(e.countCompleted.toFloat() / e.countShowedUp * 100f)}%" else "—"
                listOf(
                    evtFmt.format(Date(e.eventDate)),
                    e.eventName.ellipsize(bodyP, cols[1].w - 4f),
                    sport,
                    e.countSignedUp.toString(), e.countShowedUp.toString(), e.countCompleted.toString(), att, cmp,
                )
            }
            cols.forEachIndexed { ci, col ->
                val tx = if (col.right) col.x + col.w - bodyP.measureText(cells[ci]) - 2f else col.x + 2f
                canvas.drawText(cells[ci], tx, y + ROW_H - 3f, bodyP)
            }
            canvas.drawLine(ML, y + ROW_H, TABLE_RIGHT, y + ROW_H, divP)
            y += ROW_H
        }

        completed.forEachIndexed { idx, e -> drawEventRow(e, idx, false) }

        if (upcoming.isNotEmpty()) {
            checkBreak(COL_H + ROW_H)
            canvas.drawRect(ML, y, TABLE_RIGHT, y + COL_H, upcomingFill)
            canvas.drawText("Upcoming Events", ML + 4f, y + COL_H - 4f, secHdrP)
            y += COL_H
            upcoming.forEachIndexed { idx, e -> drawEventRow(e, idx, true) }
        }

        finishPage()
        FileOutputStream(outFile).use { pdfDoc.writeTo(it) }
        pdfDoc.close()
    }

    // ── Series report generation ─────────────────────────────────────────────

    private data class SeriesGroup(
        val seriesName: String,
        val dayOfWeek: String,
        val events: List<EventStatEntity>,
        val totalSignedUp: Int,
        val totalShowedUp: Int,
        val totalFinished: Int,
        val attRate: Float,
        val cmpRate: Float,
    )

    private fun groupBySeries(events: List<EventStatEntity>): List<SeriesGroup> {
        val zone = ZoneId.systemDefault()
        return events
            .groupBy { e ->
                val dow = Instant.ofEpochMilli(e.eventDate).atZone(zone).dayOfWeek
                Pair(e.eventName, dow)
            }
            .entries
            .sortedByDescending { (_, evts) -> evts.maxOf { it.eventDate } }
            .map { (key, evts) ->
                val (name, dow) = key
                val su = evts.sumOf { it.countSignedUp }
                val so = evts.sumOf { it.countShowedUp }
                val fi = evts.sumOf { it.countCompleted }
                SeriesGroup(
                    seriesName    = name,
                    dayOfWeek     = dow.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                    events        = evts.sortedByDescending { it.eventDate },
                    totalSignedUp = su,
                    totalShowedUp = so,
                    totalFinished = fi,
                    attRate       = if (su > 0) so.toFloat() / su * 100f else 0f,
                    cmpRate       = if (so > 0) fi.toFloat() / so * 100f else 0f,
                )
            }
    }

    private fun groupUpcomingBySeries(events: List<EventStatEntity>): List<SeriesGroup> {
        val zone = ZoneId.systemDefault()
        return events
            .groupBy { e ->
                val dow = Instant.ofEpochMilli(e.eventDate).atZone(zone).dayOfWeek
                Pair(e.eventName, dow)
            }
            .entries
            .sortedBy { (_, evts) -> evts.minOf { it.eventDate } }
            .map { (key, evts) ->
                val (name, dow) = key
                val su = evts.sumOf { it.countSignedUp }
                SeriesGroup(
                    seriesName    = name,
                    dayOfWeek     = dow.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                    events        = evts.sortedBy { it.eventDate },
                    totalSignedUp = su,
                    totalShowedUp = 0,
                    totalFinished = 0,
                    attRate       = 0f,
                    cmpRate       = 0f,
                )
            }
    }

    private fun generateCsvSeries(events: List<EventStatEntity>): String {
        val sb        = StringBuilder()
        val fmt       = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val now       = System.currentTimeMillis()
        val completed = events.filter { it.eventDate <= now }
        val upcoming  = events.filter { it.fetchedAt == 0L && it.eventDate > now }

        sb.appendLine("Series Name,Day of Week,Date,Sport,Signed Up,Showed Up,Finished,Att %,Cmp %")
        groupBySeries(completed).forEach { group ->
            group.events.forEach { e ->
                val att  = if (e.countSignedUp > 0) "%.1f".format(e.countShowedUp.toFloat()  / e.countSignedUp * 100f) else "0.0"
                val cmp  = if (e.countShowedUp > 0) "%.1f".format(e.countCompleted.toFloat() / e.countShowedUp * 100f) else "0.0"
                val name = "\"${group.seriesName.replace("\"", "\"\"")}\""
                val dow  = "\"${group.dayOfWeek}\""
                sb.appendLine("$name,$dow,${fmt.format(Date(e.eventDate))},${e.sport},${e.countSignedUp},${e.countShowedUp},${e.countCompleted},$att,$cmp")
            }
        }
        groupUpcomingBySeries(upcoming).forEach { group ->
            group.events.forEach { e ->
                val name = "\"${group.seriesName.replace("\"", "\"\"")}\""
                val dow  = "\"${group.dayOfWeek}\""
                sb.appendLine("$name,$dow,${fmt.format(Date(e.eventDate))},${e.sport},${e.countSignedUp},—,—,—,—")
            }
        }
        return sb.toString()
    }

    private fun generatePlainTextSeries(events: List<EventStatEntity>, config: ReportConfig): String {
        val sb            = StringBuilder()
        val hdrFmt        = SimpleDateFormat("MMM d, yyyy", Locale.US)
        val evtFmt        = SimpleDateFormat("MMM d, yyyy", Locale.US)
        val now           = System.currentTimeMillis()
        val completedEvts = events.filter { it.eventDate <= now }
        val upcomingEvts  = events.filter { it.fetchedAt == 0L && it.eventDate > now }
        val compGroups    = groupBySeries(completedEvts)
        val upcomGroups   = groupUpcomingBySeries(upcomingEvts)

        val totalSigned   = completedEvts.sumOf { it.countSignedUp }
        val totalShowedUp = completedEvts.sumOf { it.countShowedUp }
        val totalFinished = completedEvts.sumOf { it.countCompleted }
        val avgAtt        = if (totalSigned   > 0) totalShowedUp.toFloat()  / totalSigned   * 100f else 0f
        val avgCmp        = if (totalShowedUp > 0) totalFinished.toFloat()  / totalShowedUp * 100f else 0f

        sb.appendLine("ZLDR Event Report (Series View)")
        sb.appendLine("Generated : ${hdrFmt.format(Date())}")
        sb.appendLine("Sport     : ${sportLabel(config.sport)}")
        sb.appendLine("Period    : ${dateRangeLabel(config)}")
        sb.appendLine("Scope     : ${config.eventScope.label}")
        sb.appendLine()
        sb.appendLine("Summary")
        sb.appendLine("───────────────────────────────")
        if (completedEvts.isNotEmpty()) {
            sb.appendLine("Completed Series : ${compGroups.size}")
            sb.appendLine("Completed Events : ${completedEvts.size}")
            sb.appendLine("Signed Up        : $totalSigned")
            sb.appendLine("Showed Up        : $totalShowedUp")
            sb.appendLine("Finished         : $totalFinished")
            sb.appendLine("Avg Att          : ${"%.1f".format(avgAtt)}%")
            sb.appendLine("Avg Cmp          : ${"%.1f".format(avgCmp)}%")
        }
        if (upcomingEvts.isNotEmpty()) {
            if (completedEvts.isNotEmpty()) sb.appendLine()
            sb.appendLine("Upcoming Series  : ${upcomGroups.size}")
            sb.appendLine("Upcoming Events  : ${upcomingEvts.size}")
            sb.appendLine("Signed Up        : ${upcomingEvts.sumOf { it.countSignedUp }}")
        }
        sb.appendLine()

        if (compGroups.isNotEmpty()) {
            sb.appendLine("Completed Events by Series")
            sb.appendLine("───────────────────────────────")
            compGroups.forEach { group ->
                val occ = group.events.size
                sb.appendLine("${group.seriesName} (${group.dayOfWeek}) — $occ occurrence${if (occ != 1) "s" else ""}")
                sb.appendLine("  Total: Reg ${group.totalSignedUp}  Shwd ${group.totalShowedUp}  Fin ${group.totalFinished}  Att ${"%.1f".format(group.attRate)}%  Cmp ${"%.1f".format(group.cmpRate)}%")
                group.events.forEach { e ->
                    val att = if (e.countSignedUp > 0) "%.1f".format(e.countShowedUp.toFloat()  / e.countSignedUp * 100f) else "0.0"
                    val cmp = if (e.countShowedUp > 0) "%.1f".format(e.countCompleted.toFloat() / e.countShowedUp * 100f) else "0.0"
                    sb.appendLine("  ${evtFmt.format(Date(e.eventDate))}   Reg: ${e.countSignedUp}  Shwd: ${e.countShowedUp}  Fin: ${e.countCompleted}  Att: $att%  Cmp: $cmp%")
                }
                sb.appendLine()
            }
        }
        if (upcomGroups.isNotEmpty()) {
            sb.appendLine("Upcoming Events by Series")
            sb.appendLine("───────────────────────────────")
            upcomGroups.forEach { group ->
                val occ = group.events.size
                sb.appendLine("${group.seriesName} (${group.dayOfWeek}) — $occ upcoming occurrence${if (occ != 1) "s" else ""}")
                sb.appendLine("  Signed Up: ${group.totalSignedUp}")
                group.events.forEach { e ->
                    sb.appendLine("  ${evtFmt.format(Date(e.eventDate))}   Reg: ${e.countSignedUp}")
                }
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    private fun generateHtmlSeries(events: List<EventStatEntity>, config: ReportConfig): String {
        val hdrFmt        = SimpleDateFormat("MMM d, yyyy", Locale.US)
        val evtFmt        = SimpleDateFormat("MMM d, yyyy", Locale.US)
        val now           = System.currentTimeMillis()
        val completedEvts = events.filter { it.eventDate <= now }
        val upcomingEvts  = events.filter { it.fetchedAt == 0L && it.eventDate > now }
        val compGroups    = groupBySeries(completedEvts)
        val upcomGroups   = groupUpcomingBySeries(upcomingEvts)
        val bothExist     = compGroups.isNotEmpty() && upcomGroups.isNotEmpty()

        val totalSigned   = completedEvts.sumOf { it.countSignedUp }
        val totalShowedUp = completedEvts.sumOf { it.countShowedUp }
        val totalFinished = completedEvts.sumOf { it.countCompleted }
        val avgAtt        = if (totalSigned   > 0) totalShowedUp.toFloat()  / totalSigned   * 100f else 0f
        val avgCmp        = if (totalShowedUp > 0) totalFinished.toFloat()  / totalShowedUp * 100f else 0f

        fun seriesBlock(group: SeriesGroup, isUpcoming: Boolean): String {
            val occ = group.events.size
            val cols = "<tr><th>Date</th><th>Sport</th><th>Signed Up</th><th>Showed Up</th><th>Finished</th><th>Att %</th><th>Cmp %</th></tr>"
            val occRows = group.events.joinToString("\n") { e ->
                val sport = e.sport.lowercase().replaceFirstChar { it.uppercase() }
                if (isUpcoming) {
                    "<tr><td>${evtFmt.format(Date(e.eventDate))}</td><td>$sport</td>" +
                        "<td>${e.countSignedUp}</td><td>—</td><td>—</td><td>—</td><td>—</td></tr>"
                } else {
                    val att = if (e.countSignedUp > 0) "%.1f".format(e.countShowedUp.toFloat()  / e.countSignedUp * 100f) else "0.0"
                    val cmp = if (e.countShowedUp > 0) "%.1f".format(e.countCompleted.toFloat() / e.countShowedUp * 100f) else "0.0"
                    "<tr><td>${evtFmt.format(Date(e.eventDate))}</td><td>$sport</td>" +
                        "<td>${e.countSignedUp}</td><td>${e.countShowedUp}</td>" +
                        "<td>${e.countCompleted}</td><td>$att%</td><td>$cmp%</td></tr>"
                }
            }
            val sumLine = if (isUpcoming)
                "$occ upcoming occurrence${if (occ != 1) "s" else ""} &nbsp;·&nbsp; Signed Up: ${group.totalSignedUp}"
            else
                "$occ occurrence${if (occ != 1) "s" else ""} &nbsp;·&nbsp; Signed Up: ${group.totalSignedUp} &nbsp;·&nbsp; Showed Up: ${group.totalShowedUp} &nbsp;·&nbsp; Finished: ${group.totalFinished} &nbsp;·&nbsp; Att: ${"%.1f".format(group.attRate)}% &nbsp;·&nbsp; Cmp: ${"%.1f".format(group.cmpRate)}%"
            return """<div class="series">
<h3>${he(group.seriesName)} <span class="dow">(${group.dayOfWeek})</span></h3>
<p class="series-sum">$sumLine</p>
<table class="ev">$cols
$occRows
</table>
</div>"""
        }

        val completedSection = if (compGroups.isNotEmpty()) buildString {
            if (bothExist) appendLine("<h2>Completed Events</h2>")
            compGroups.forEach { appendLine(seriesBlock(it, false)) }
        } else ""
        val upcomingSection = if (upcomGroups.isNotEmpty()) buildString {
            if (bothExist) appendLine("<h2>Upcoming Events</h2>")
            upcomGroups.forEach { appendLine(seriesBlock(it, true)) }
        } else ""

        val summaryRows = buildString {
            if (completedEvts.isNotEmpty()) {
                appendLine("<tr><td>Completed Series</td><td><strong>${compGroups.size}</strong></td></tr>")
                appendLine("<tr><td>Completed Events</td><td><strong>${completedEvts.size}</strong></td></tr>")
                appendLine("<tr><td>Total Signed Up</td><td><strong>$totalSigned</strong></td></tr>")
                appendLine("<tr><td>Total Showed Up</td><td><strong>$totalShowedUp</strong></td></tr>")
                appendLine("<tr><td>Total Finished</td><td><strong>$totalFinished</strong></td></tr>")
                appendLine("<tr><td>Avg Attendance</td><td><strong>${"%.1f".format(avgAtt)}%</strong></td></tr>")
                append("<tr><td>Avg Completion</td><td><strong>${"%.1f".format(avgCmp)}%</strong></td></tr>")
            }
            if (upcomingEvts.isNotEmpty()) {
                if (completedEvts.isNotEmpty()) appendLine()
                appendLine("<tr><td>Upcoming Series</td><td><strong>${upcomGroups.size}</strong></td></tr>")
                appendLine("<tr><td>Upcoming Events</td><td><strong>${upcomingEvts.size}</strong></td></tr>")
                append("<tr><td>Upcoming Signed Up</td><td><strong>${upcomingEvts.sumOf { it.countSignedUp }}</strong></td></tr>")
            }
        }

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZLDR Event Report (Series)</title>
<style>
body{font-family:Arial,sans-serif;margin:24px;color:#222}
h1{color:#1565C0;margin-bottom:4px}
h2{color:#1565C0;font-size:16px;margin:24px 0 12px 0;padding-bottom:4px;border-bottom:2px solid #1565C0}
h3{color:#1565C0;margin:0 0 4px 0;font-size:14px}
.dow{color:#555;font-weight:normal}
.meta{color:#666;font-size:13px;margin-bottom:20px}
.summary{background:#f0f4ff;padding:14px 18px;border-radius:8px;margin-bottom:24px;display:inline-block}
.summary td{padding:3px 20px 3px 0;font-size:14px}
.summary td:first-child{color:#555}
.series{margin-bottom:24px;padding-bottom:16px;border-bottom:1px solid #e0e0e0}
.series-sum{font-size:12px;color:#555;margin:0 0 8px 0}
table.ev{width:100%;border-collapse:collapse;font-size:13px}
table.ev th{background:#1565C0;color:#fff;padding:8px 10px;text-align:left;white-space:nowrap}
table.ev td{padding:7px 10px;border-bottom:1px solid #eee;vertical-align:top}
table.ev tr:nth-child(even){background:#f9f9f9}
@media print{.meta{color:#333}}
</style>
</head>
<body>
<h1>ZLDR Event Report (Series View)</h1>
<p class="meta">
  Generated: ${hdrFmt.format(Date())} &nbsp;·&nbsp;
  Sport: ${sportLabel(config.sport)} &nbsp;·&nbsp;
  Period: ${he(dateRangeLabel(config))} &nbsp;·&nbsp;
  ${config.eventScope.label}
</p>
<div class="summary">
<table>
$summaryRows
</table>
</div>
$completedSection
$upcomingSection
</body>
</html>"""
    }

    private fun generatePdfSeries(events: List<EventStatEntity>, config: ReportConfig, outFile: File) {
        val pdfDoc    = PdfDocument()
        val now       = System.currentTimeMillis()
        val compEvts  = events.filter { it.eventDate <= now }
        val upcomEvts = events.filter { it.fetchedAt == 0L && it.eventDate > now }
        val compGroups  = groupBySeries(compEvts)
        val upcomGroups = groupUpcomingBySeries(upcomEvts)

        val PW = 595f; val PH = 842f
        val ML = 40f;  val MR = 40f; val MT = 40f; val MB = 50f
        val TABLE_RIGHT = PW - MR

        val blue      = Color.rgb(0x15, 0x65, 0xC0)
        val lightBlue = Color.rgb(0xF0, 0xF4, 0xFF)
        val groupBg   = Color.rgb(0xE8, 0xF0, 0xFF)
        val altRow    = Color.rgb(0xF9, 0xF9, 0xF9)
        val divClr    = Color.rgb(0xE0, 0xE0, 0xE0)

        fun p(size: Float, clr: Int = Color.BLACK, bold: Boolean = false) = Paint().apply {
            color = clr; textSize = size; isAntiAlias = true
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val titleP  = p(18f, blue, bold = true)
        val metaP   = p(8f,  Color.GRAY)
        val labelP  = p(9f,  Color.BLACK, bold = true)
        val sumKeyP = p(9f,  Color.GRAY)
        val bodyP   = p(8f,  Color.BLACK)
        val colHdrP = p(8f,  Color.WHITE, bold = true)
        val grpHdrP = p(9f,  blue, bold = true)
        val grpMetP = p(8f,  Color.rgb(0x30, 0x30, 0x80))
        val pageNP  = p(7f,  Color.GRAY)
        val divP    = Paint().apply { color = divClr;     strokeWidth = 0.5f; style = Paint.Style.STROKE }
        val blueFill = Paint().apply { color = blue;      style = Paint.Style.FILL }
        val grpFill  = Paint().apply { color = groupBg;   style = Paint.Style.FILL }
        val sumFill  = Paint().apply { color = lightBlue; style = Paint.Style.FILL }
        val altFill  = Paint().apply { color = altRow;    style = Paint.Style.FILL }

        data class Col(val x: Float, val w: Float, val label: String, val right: Boolean = false)
        val cols = listOf(
            Col(ML +   0f,  65f, "Date"),
            Col(ML +  65f, 153f, "Series (Day)"),
            Col(ML + 218f,  48f, "Sport"),
            Col(ML + 266f,  55f, "Signed Up", right = true),
            Col(ML + 321f,  45f, "Showed",    right = true),
            Col(ML + 366f,  49f, "Finished",  right = true),
            Col(ML + 415f,  45f, "Att %",     right = true),
            Col(ML + 460f,  55f, "Cmp %",     right = true),
        )

        val ROW_H   = 14f
        val COL_H   = 15f
        val GROUP_H = 16f

        val evtFmt = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US)

        val totalSigned   = compEvts.sumOf { it.countSignedUp }
        val totalShowedUp = compEvts.sumOf { it.countShowedUp }
        val totalFinished = compEvts.sumOf { it.countCompleted }
        val avgAtt        = if (totalSigned   > 0) totalShowedUp.toFloat()  / totalSigned   * 100f else 0f
        val avgCmp        = if (totalShowedUp > 0) totalFinished.toFloat()  / totalShowedUp * 100f else 0f

        var pageNum = 1
        var page    = pdfDoc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), pageNum).create())
        var canvas  = page.canvas
        var y       = MT
        var inTable = false

        fun finishPage() {
            val pg = "Page $pageNum"
            canvas.drawText(pg, (PW - pageNP.measureText(pg)) / 2f, PH - 16f, pageNP)
            pdfDoc.finishPage(page)
        }

        fun newPage() {
            finishPage()
            pageNum++
            page   = pdfDoc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), pageNum).create())
            canvas = page.canvas
            y      = MT
        }

        fun drawColHeaders() {
            canvas.drawRect(ML, y, TABLE_RIGHT, y + COL_H, blueFill)
            cols.forEach { col ->
                val tx = if (col.right) col.x + col.w - colHdrP.measureText(col.label) - 2f
                         else col.x + 2f
                canvas.drawText(col.label, tx, y + COL_H - 4f, colHdrP)
            }
            y += COL_H
            inTable = true
        }

        fun checkBreak(needed: Float) {
            if (y + needed > PH - MB) {
                val wasTable = inTable
                newPage()
                if (wasTable) drawColHeaders()
            }
        }

        // Title & meta
        canvas.drawText("ZLDR Event Report (Series View)", ML, y + titleP.textSize, titleP)
        y += titleP.textSize + 5f
        val metaLine = "Generated: ${evtFmt.format(Date())}   |   " +
                "Sport: ${sportLabel(config.sport)}   |   " +
                "Period: ${dateRangeLabel(config)}   |   " +
                config.eventScope.label
        canvas.drawText(metaLine.ellipsize(metaP, PW - ML - MR), ML, y + metaP.textSize, metaP)
        y += metaP.textSize + 14f

        // Summary box
        val summaryEntries = buildList {
            if (compEvts.isNotEmpty()) {
                add("Completed Series" to compGroups.size.toString())
                add("Completed Events" to compEvts.size.toString())
                add("Total Signed Up"  to totalSigned.toString())
                add("Total Showed Up"  to totalShowedUp.toString())
                add("Total Finished"   to totalFinished.toString())
                add("Avg Attendance"   to "${"%.1f".format(avgAtt)}%")
                add("Avg Completion"   to "${"%.1f".format(avgCmp)}%")
            }
            if (upcomEvts.isNotEmpty()) {
                add("Upcoming Series"    to upcomGroups.size.toString())
                add("Upcoming Events"    to upcomEvts.size.toString())
                add("Upcoming Signed Up" to upcomEvts.sumOf { it.countSignedUp }.toString())
            }
        }
        val lineH   = labelP.textSize + 6f
        val sumBoxH = summaryEntries.size * lineH + 10f * 2
        canvas.drawRoundRect(RectF(ML, y, ML + 230f, y + sumBoxH), 4f, 4f, sumFill)
        var sy = y + 10f + labelP.textSize
        summaryEntries.forEach { (lbl, v) ->
            canvas.drawText(lbl, ML + 6f, sy, sumKeyP)
            canvas.drawText(v, ML + 145f, sy, labelP)
            sy += lineH
        }
        y += sumBoxH + 18f

        // Table
        drawColHeaders()

        fun drawGroup(group: SeriesGroup, isUpcoming: Boolean) {
            checkBreak(GROUP_H + ROW_H)
            canvas.drawRect(ML, y, TABLE_RIGHT, y + GROUP_H, grpFill)
            val groupLabel = "${group.seriesName} (${group.dayOfWeek})".ellipsize(grpHdrP, cols[0].w + cols[1].w - 8f)
            canvas.drawText(groupLabel, ML + 4f, y + GROUP_H - 4f, grpHdrP)
            val occLabel = "${group.events.size} occ."
            canvas.drawText(occLabel, cols[2].x + 2f, y + GROUP_H - 4f, grpMetP)
            if (!isUpcoming) {
                val groupStats = listOf(
                    group.totalSignedUp.toString(),
                    group.totalShowedUp.toString(),
                    group.totalFinished.toString(),
                    "${"%.1f".format(group.attRate)}%",
                    "${"%.1f".format(group.cmpRate)}%",
                )
                groupStats.forEachIndexed { i, cell ->
                    val col = cols[3 + i]
                    canvas.drawText(cell, col.x + col.w - grpMetP.measureText(cell) - 2f, y + GROUP_H - 4f, grpMetP)
                }
            } else {
                val suCell = group.totalSignedUp.toString()
                canvas.drawText(suCell, cols[3].x + cols[3].w - grpMetP.measureText(suCell) - 2f, y + GROUP_H - 4f, grpMetP)
            }
            canvas.drawLine(ML, y + GROUP_H, TABLE_RIGHT, y + GROUP_H, divP)
            y += GROUP_H

            group.events.forEachIndexed { idx, e ->
                checkBreak(ROW_H)
                if (idx % 2 == 1) canvas.drawRect(ML, y, TABLE_RIGHT, y + ROW_H, altFill)
                val sport = e.sport.lowercase().replaceFirstChar { it.uppercase() }
                val cells = if (isUpcoming) listOf(
                    evtFmt.format(Date(e.eventDate)), "", sport, e.countSignedUp.toString(), "—", "—", "—", "—",
                ) else {
                    val att = if (e.countSignedUp > 0) "${"%.1f".format(e.countShowedUp.toFloat()  / e.countSignedUp * 100f)}%" else "—"
                    val cmp = if (e.countShowedUp > 0) "${"%.1f".format(e.countCompleted.toFloat() / e.countShowedUp * 100f)}%" else "—"
                    listOf(evtFmt.format(Date(e.eventDate)), "", sport,
                        e.countSignedUp.toString(), e.countShowedUp.toString(), e.countCompleted.toString(), att, cmp)
                }
                cols.forEachIndexed { ci, col ->
                    if (cells[ci].isEmpty()) return@forEachIndexed
                    val tx = if (col.right) col.x + col.w - bodyP.measureText(cells[ci]) - 2f else col.x + 2f
                    canvas.drawText(cells[ci], tx, y + ROW_H - 3f, bodyP)
                }
                canvas.drawLine(ML, y + ROW_H, TABLE_RIGHT, y + ROW_H, divP)
                y += ROW_H
            }
            y += 4f
        }

        compGroups.forEach { drawGroup(it, false) }

        if (upcomGroups.isNotEmpty()) {
            checkBreak(COL_H + GROUP_H + ROW_H)
            canvas.drawRect(ML, y, TABLE_RIGHT, y + COL_H, blueFill)
            canvas.drawText("Upcoming Events", ML + 4f, y + COL_H - 4f, colHdrP)
            y += COL_H
            upcomGroups.forEach { drawGroup(it, true) }
        }

        finishPage()
        FileOutputStream(outFile).use { pdfDoc.writeTo(it) }
        pdfDoc.close()
    }

    private fun String.ellipsize(paint: Paint, maxWidth: Float): String {
        if (paint.measureText(this) <= maxWidth) return this
        val ellipsis = "…"
        var end = length
        while (end > 0 && paint.measureText(substring(0, end) + ellipsis) > maxWidth) end--
        return if (end <= 0) ellipsis else substring(0, end) + ellipsis
    }
}
