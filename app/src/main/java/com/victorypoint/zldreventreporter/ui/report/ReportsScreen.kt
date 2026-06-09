package com.victorypoint.zldreventreporter.ui.report

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.util.Log
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorypoint.zldreventreporter.data.db.EventStatEntity
import com.victorypoint.zldreventreporter.data.events.SyncProgress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

private val EVENT_DATE_FMT = DateTimeFormatterBuilder()
    .appendPattern("EEE, MMM d h:mm")
    .appendText(ChronoField.AMPM_OF_DAY, mapOf(0L to "am", 1L to "pm"))
    .toFormatter()

private data class ColumnInfo(val abbrev: String, val title: String, val description: String)

private val COLUMN_INFOS = listOf(
    ColumnInfo(
        abbrev      = "Reg",
        title       = "Registered",
        description = "Riders who signed up for the event.",
    ),
    ColumnInfo(
        abbrev      = "Shwd",
        title       = "Showed Up",
        description = "Riders who joined the event in-game.",
    ),
    ColumnInfo(
        abbrev      = "Fin",
        title       = "Finished",
        description = "Riders who completed the event.\n\nFalls back to Showed Up if results are not yet posted.",
    ),
    ColumnInfo(
        abbrev      = "Att%",
        title       = "Attendance %",
        description = "Showed Up ÷ Registered × 100%.\n\nThe share of registered riders who actually joined in-game.",
    ),
    ColumnInfo(
        abbrev      = "Cmp%",
        title       = "Completion %",
        description = "Finished ÷ Showed Up × 100%.\n\nThe share of riders who showed up and went on to complete the full event.",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    viewModel: ReportViewModel,
    onSettingsClick: () -> Unit,
    onEventClick: (Long) -> Unit = {},
    onEventDoubleClick: (Long) -> Unit = {},
) {
    val filter               by viewModel.filter.collectAsState()
    val rows                 by viewModel.reportRows.collectAsState()
    val upcomingRows         by viewModel.upcomingRows.collectAsState()
    val seriesRows           by viewModel.seriesRows.collectAsState()
    val upcomingSeriesRows   by viewModel.upcomingSeriesRows.collectAsState()
    val viewMode             by viewModel.viewMode.collectAsState()
    val uiState       by viewModel.uiState.collectAsState()
    val syncProgress  by viewModel.syncProgress.collectAsState()
    val expandedKeys  by viewModel.expandedKeys.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(uiState) {
        if (uiState is ReportUiState.Error) {
            val result = snackbarHost.showSnackbar(
                message = (uiState as ReportUiState.Error).message,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.clearSyncError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("ZLDR Event Reporter") },
                actions = {
                    if (syncProgress is SyncProgress.Loading) {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier  = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        IconButton(onClick = { viewModel.startSync() }) {
                            Icon(Icons.Default.Sync, contentDescription = "Sync")
                        }
                    }
                    IconButton(onClick = viewModel::toggleViewMode) {
                        Icon(
                            imageVector = if (viewMode == ViewMode.DATE) Icons.Default.BarChart
                                          else Icons.Default.ViewList,
                            contentDescription = if (viewMode == ViewMode.DATE) "Switch to series view"
                                                 else "Switch to date view",
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            FilterRow(
                filter            = filter,
                onPeriodChange    = viewModel::setPeriod,
                onSportChange     = viewModel::setSport,
                showPeriodFilter  = viewMode == ViewMode.DATE,
            )

            when (uiState) {
                ReportUiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                ReportUiState.Empty -> {
                    EmptyState(onSyncClick = { viewModel.startSync() })
                }
                is ReportUiState.Error, ReportUiState.Success -> {
                    if (viewMode == ViewMode.DATE) {
                        ReportTable(
                            rows               = rows,
                            upcomingRows       = upcomingRows,
                            expandedKeys       = expandedKeys,
                            onSetExpanded      = viewModel::setExpanded,
                            onEventClick       = onEventClick,
                            onEventDoubleClick = onEventDoubleClick,
                        )
                    } else {
                        SeriesTable(
                            rows               = seriesRows,
                            upcomingSeriesRows = upcomingSeriesRows,
                            expandedKeys       = expandedKeys,
                            onSetExpanded      = viewModel::setExpanded,
                            onEventClick       = onEventClick,
                            onEventDoubleClick = onEventDoubleClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    filter: ReportFilter,
    onPeriodChange: (PeriodFilter) -> Unit,
    onSportChange: (SportFilter) -> Unit,
    showPeriodFilter: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showPeriodFilter) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                PeriodFilter.entries.forEachIndexed { index, period ->
                    SegmentedButton(
                        selected = filter.period == period,
                        onClick = { onPeriodChange(period) },
                        shape = SegmentedButtonDefaults.itemShape(index, PeriodFilter.entries.size),
                        label = { Text(period.name, fontSize = 12.sp) },
                    )
                }
            }
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SportFilter.entries.forEachIndexed { index, sport ->
                SegmentedButton(
                    selected = filter.sport == sport,
                    onClick = { onSportChange(sport) },
                    shape = SegmentedButtonDefaults.itemShape(index, SportFilter.entries.size),
                    label = { Text(sport.name, fontSize = 12.sp) },
                )
            }
        }
    }
}

@Composable
private fun ReportTable(
    rows: List<ReportRow>,
    upcomingRows: List<ReportRow>,
    expandedKeys: Map<String, Boolean>,
    onSetExpanded: (String, Boolean) -> Unit,
    onEventClick: (Long) -> Unit,
    onEventDoubleClick: (Long) -> Unit,
) {
    val now = remember { System.currentTimeMillis() }

    Column {
        TableHeader()
        HorizontalDivider()
    }

    LazyColumn {
        if (upcomingRows.isNotEmpty()) {
            val isSectionExpanded = expandedKeys["upcoming"] == true
            item(key = "upcoming_header") {
                UpcomingSectionHeader(
                    isExpanded = isSectionExpanded,
                    onToggle   = { onSetExpanded("upcoming", !isSectionExpanded) },
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            }
            item(key = "upcoming_content") {
                AnimatedVisibility(visible = isSectionExpanded) {
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        upcomingRows.forEach { row ->
                            val isRowExpanded = expandedKeys["up_${row.periodLabel}"]
                                ?: (row.fromMillis <= now && now < row.toMillis)
                            UpcomingPeriodRow(
                                row        = row,
                                isExpanded = isRowExpanded,
                                onToggle   = { onSetExpanded("up_${row.periodLabel}", !isRowExpanded) },
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            AnimatedVisibility(visible = isRowExpanded) {
                                Column {
                                    row.events.forEachIndexed { index, event ->
                                        UpcomingEventRow(event, isEven = index % 2 == 1, onClick = { onEventClick(event.eventId) })
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color     = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                                            modifier  = Modifier.padding(start = 40.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (rows.isNotEmpty()) {
            val isCompletedExpanded = expandedKeys["completed"] != false
            item(key = "completed_header") {
                CompletedSectionHeader(
                    isExpanded = isCompletedExpanded,
                    onToggle   = { onSetExpanded("completed", !isCompletedExpanded) },
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            }
            item(key = "completed_content") {
                AnimatedVisibility(visible = isCompletedExpanded) {
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        rows.forEach { row ->
                            val isRowExpanded = expandedKeys[row.periodLabel]
                                ?: (row.fromMillis <= now && now < row.toMillis)
                            PeriodRow(
                                row        = row,
                                isExpanded = isRowExpanded,
                                onToggle   = { onSetExpanded(row.periodLabel, !isRowExpanded) },
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            AnimatedVisibility(visible = isRowExpanded) {
                                Column {
                                    row.events.forEachIndexed { index, event ->
                                        val isInProgress = System.currentTimeMillis() < event.eventDate + event.durationInSeconds * 1000L
                                        EventRow(event, isEven = index % 2 == 1, isInProgress = isInProgress, onClick = { onEventClick(event.eventId) }, onDoubleClick = { onEventDoubleClick(event.eventId) })
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color     = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                                            modifier  = Modifier.padding(start = 40.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableHeader() {
    var infoCol by remember { mutableStateOf<ColumnInfo?>(null) }

    infoCol?.let { col ->
        AlertDialog(
            onDismissRequest = { infoCol = null },
            title = { Text(col.title, fontWeight = FontWeight.Bold) },
            text  = { Text(col.description) },
            confirmButton = {
                TextButton(onClick = { infoCol = null }) { Text("OK") }
            },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(8.dp))
        Text(
            text       = "Event",
            modifier   = Modifier.weight(2.0f),
            fontWeight = FontWeight.Bold,
            fontSize   = 10.sp,
        )
        COLUMN_INFOS.forEachIndexed { i, col ->
            val align = if (i < 3) TextAlign.Center else TextAlign.End
            Text(
                text       = col.abbrev,
                modifier   = Modifier
                    .weight(0.5f)
                    .then(if (i == 3) Modifier.padding(end = 6.dp) else Modifier)
                    .clickable { infoCol = col },
                textAlign  = align,
                fontWeight = FontWeight.Bold,
                fontSize   = 10.sp,
                color      = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PeriodRow(row: ReportRow, isExpanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(4.dp))
        Text(row.periodLabel,          modifier = Modifier.weight(2.0f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(row.totalSignedUp.toString(),  modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 11.sp)
        Text(row.totalShowedUp.toString(),  modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 11.sp)
        Text(row.totalCompleted.toString(), modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 11.sp)
        RateCell(value = row.showUpRate,    modifier = Modifier.weight(0.5f).padding(end = 6.dp))
        RateCell(value = row.completionRate, modifier = Modifier.weight(0.5f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventRow(event: EventStatEntity, isEven: Boolean = false, isInProgress: Boolean = false, onClick: () -> Unit = {}, onDoubleClick: () -> Unit = {}) {
    val uriHandler = LocalUriHandler.current
    val attPct = if (event.countSignedUp > 0)
        event.countShowedUp.toFloat() / event.countSignedUp * 100f else 0f
    val cmpPct = if (event.countShowedUp > 0)
        event.countCompleted.toFloat() / event.countShowedUp * 100f else 0f
    val localDt = Instant.ofEpochMilli(event.eventDate).atZone(ZoneId.systemDefault())
    val (timeIcon, timeTint) = when {
        localDt.hour < 12 -> Icons.Default.WbTwilight to Color(0xFFFFAB40)
        localDt.hour < 18 -> Icons.Default.WbSunny    to Color(0xFFFFD600)
        else              -> Icons.Default.Bedtime     to Color(0xFF90CAF9)
    }
    val bgColor = if (isEven) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                  else MaterialTheme.colorScheme.surface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                onLongClick = {
                    val url = "https://www.zwift.com/events/view/${event.eventId}"
                    Log.d("ReportsScreen", "Opening: $url")
                    uriHandler.openUri(url)
                },
            )
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (event.sport == "RUNNING") Icons.AutoMirrored.Filled.DirectionsRun
                              else Icons.AutoMirrored.Filled.DirectionsBike,
                contentDescription = event.sport,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(event.eventName, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (isInProgress) {
                Spacer(Modifier.width(6.dp))
                InProgressBadge()
            }
        }
        Row(
            modifier = Modifier.padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.weight(2.0f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = timeIcon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = timeTint,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    EVENT_DATE_FMT.format(localDt),
                    fontSize = 11.sp,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(event.countSignedUp.toString(),  modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 12.sp)
            Text(event.countShowedUp.toString(),  modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 12.sp)
            Text(event.countCompleted.toString(), modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 12.sp)
            RateCell(value = attPct, modifier = Modifier.weight(0.5f).padding(end = 6.dp))
            RateCell(value = cmpPct, modifier = Modifier.weight(0.5f))
        }
    }
}

@Composable
private fun UpcomingSectionHeader(isExpanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(4.dp))
        Text("Upcoming Events", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun UpcomingPeriodRow(row: ReportRow, isExpanded: Boolean, onToggle: () -> Unit) {
    val dimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(4.dp))
        Text(row.periodLabel,              modifier = Modifier.weight(2.0f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(row.totalSignedUp.toString(), modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 11.sp)
        Text("—", modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 11.sp, color = dimColor)
        Text("—", modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 11.sp, color = dimColor)
        Text("—", modifier = Modifier.weight(0.5f).padding(end = 6.dp), textAlign = TextAlign.End, fontSize = 11.sp, color = dimColor)
        Text("—", modifier = Modifier.weight(0.5f), textAlign = TextAlign.End,    fontSize = 11.sp, color = dimColor)
    }
}

@Composable
private fun CompletedSectionHeader(isExpanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(4.dp))
        Text("Completed Events", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpcomingEventRow(event: EventStatEntity, isEven: Boolean = false, onClick: () -> Unit = {}) {
    val uriHandler = LocalUriHandler.current
    val localDt = Instant.ofEpochMilli(event.eventDate).atZone(ZoneId.systemDefault())
    val (timeIcon, timeTint) = when {
        localDt.hour < 12 -> Icons.Default.WbTwilight to Color(0xFFFFAB40)
        localDt.hour < 18 -> Icons.Default.WbSunny    to Color(0xFFFFD600)
        else              -> Icons.Default.Bedtime     to Color(0xFF90CAF9)
    }
    val dimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val bgColor = if (isEven) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                  else MaterialTheme.colorScheme.surface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = {},
                onLongClick = {
                    val url = "https://www.zwift.com/events/view/${event.eventId}"
                    Log.d("ReportsScreen", "Opening: $url")
                    uriHandler.openUri(url)
                },
            )
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (event.sport == "RUNNING") Icons.AutoMirrored.Filled.DirectionsRun
                              else Icons.AutoMirrored.Filled.DirectionsBike,
                contentDescription = event.sport,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(event.eventName, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Row(
            modifier = Modifier.padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.weight(2.0f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = timeIcon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = timeTint,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    EVENT_DATE_FMT.format(localDt),
                    fontSize = 11.sp,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(event.countSignedUp.toString(), modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 12.sp)
            Text("—", modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 12.sp, color = dimColor)
            Text("—", modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 12.sp, color = dimColor)
            Text("—", modifier = Modifier.weight(0.5f).padding(end = 6.dp), textAlign = TextAlign.End, fontSize = 12.sp, color = dimColor)
            Text("—", modifier = Modifier.weight(0.5f), textAlign = TextAlign.End,   fontSize = 12.sp, color = dimColor)
        }
    }
}

@Composable
private fun SeriesTable(
    rows: List<SeriesRow>,
    upcomingSeriesRows: List<SeriesRow>,
    expandedKeys: Map<String, Boolean>,
    onSetExpanded: (String, Boolean) -> Unit,
    onEventClick: (Long) -> Unit,
    onEventDoubleClick: (Long) -> Unit,
) {
    Column {
        TableHeader()
        HorizontalDivider()
    }
    if (upcomingSeriesRows.isEmpty() && rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text      = "No events match the current filter.",
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier  = Modifier.padding(32.dp),
            )
        }
        return
    }
    val isUpcomingExpanded  = expandedKeys["series_upcoming"] == true
    val isCompletedExpanded = expandedKeys["series_completed"] != false
    LazyColumn {
        if (upcomingSeriesRows.isNotEmpty()) {
            item(key = "series_upcoming_header") {
                UpcomingSectionHeader(
                    isExpanded = isUpcomingExpanded,
                    onToggle   = { onSetExpanded("series_upcoming", !isUpcomingExpanded) },
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            }
            item(key = "series_upcoming_content") {
                AnimatedVisibility(visible = isUpcomingExpanded) {
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        upcomingSeriesRows.forEachIndexed { index, row ->
                            UpcomingSeriesRowItem(
                                row          = row,
                                isEven       = index % 2 == 1,
                                onEventClick = onEventClick,
                            )
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color     = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            )
                        }
                    }
                }
            }
        }
        if (rows.isNotEmpty()) {
            item(key = "series_completed_header") {
                CompletedSectionHeader(
                    isExpanded = isCompletedExpanded,
                    onToggle   = { onSetExpanded("series_completed", !isCompletedExpanded) },
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            }
            item(key = "series_completed_content") {
                AnimatedVisibility(visible = isCompletedExpanded) {
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        rows.forEachIndexed { index, row ->
                            SeriesRowItem(
                                row                = row,
                                isEven             = index % 2 == 1,
                                onEventClick       = onEventClick,
                                onEventDoubleClick = onEventDoubleClick,
                            )
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color     = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingSeriesRowItem(
    row: SeriesRow,
    isEven: Boolean,
    onEventClick: (Long) -> Unit,
) {
    val bgColor = if (isEven) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                  else MaterialTheme.colorScheme.surface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (row.sport == "RUNNING") Icons.AutoMirrored.Filled.DirectionsRun
                              else Icons.AutoMirrored.Filled.DirectionsBike,
                contentDescription = row.sport,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text("${row.seriesName} (${row.dayOfWeek})", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        row.events.forEach { event ->
            UpcomingSeriesEventLine(
                event   = event,
                onClick = { onEventClick(event.eventId) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpcomingSeriesEventLine(
    event: EventStatEntity,
    onClick: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val localDt = Instant.ofEpochMilli(event.eventDate).atZone(ZoneId.systemDefault())
    val (timeIcon, timeTint) = when {
        localDt.hour < 12 -> Icons.Default.WbTwilight to Color(0xFFFFAB40)
        localDt.hour < 18 -> Icons.Default.WbSunny    to Color(0xFFFFD600)
        else              -> Icons.Default.Bedtime     to Color(0xFF90CAF9)
    }
    val dimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick       = onClick,
                onDoubleClick = {},
                onLongClick   = { uriHandler.openUri("https://www.zwift.com/events/view/${event.eventId}") },
            )
            .padding(top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.weight(2.0f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = timeIcon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = timeTint,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                EVENT_DATE_FMT.format(localDt),
                fontSize = 11.sp,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(event.countSignedUp.toString(), modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 12.sp)
        Text("—", modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 12.sp, color = dimColor)
        Text("—", modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 12.sp, color = dimColor)
        Text("—", modifier = Modifier.weight(0.5f).padding(end = 6.dp), textAlign = TextAlign.End, fontSize = 12.sp, color = dimColor)
        Text("—", modifier = Modifier.weight(0.5f), textAlign = TextAlign.End,   fontSize = 12.sp, color = dimColor)
    }
}

@Composable
private fun SeriesRowItem(
    row: SeriesRow,
    isEven: Boolean,
    onEventClick: (Long) -> Unit,
    onEventDoubleClick: (Long) -> Unit,
) {
    val bgColor = if (isEven) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                  else MaterialTheme.colorScheme.surface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
    ) {
        // Series name header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (row.sport == "RUNNING") Icons.AutoMirrored.Filled.DirectionsRun
                              else Icons.AutoMirrored.Filled.DirectionsBike,
                contentDescription = row.sport,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text("${row.seriesName} (${row.dayOfWeek})", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        // One date/stats line per occurrence
        row.events.forEach { event ->
            val isInProgress = System.currentTimeMillis() < event.eventDate + event.durationInSeconds * 1000L
            SeriesEventLine(
                event         = event,
                isInProgress  = isInProgress,
                onClick       = { onEventClick(event.eventId) },
                onDoubleClick = { onEventDoubleClick(event.eventId) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesEventLine(
    event: EventStatEntity,
    isInProgress: Boolean = false,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val attPct = if (event.countSignedUp > 0)
        event.countShowedUp.toFloat() / event.countSignedUp * 100f else 0f
    val cmpPct = if (event.countShowedUp > 0)
        event.countCompleted.toFloat() / event.countShowedUp * 100f else 0f
    val localDt = Instant.ofEpochMilli(event.eventDate).atZone(ZoneId.systemDefault())
    val (timeIcon, timeTint) = when {
        localDt.hour < 12 -> Icons.Default.WbTwilight to Color(0xFFFFAB40)
        localDt.hour < 18 -> Icons.Default.WbSunny    to Color(0xFFFFD600)
        else              -> Icons.Default.Bedtime     to Color(0xFF90CAF9)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick       = onClick,
                onDoubleClick = onDoubleClick,
                onLongClick   = {
                    uriHandler.openUri("https://www.zwift.com/events/view/${event.eventId}")
                },
            )
            .padding(top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.weight(2.0f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = timeIcon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = timeTint,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                EVENT_DATE_FMT.format(localDt),
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isInProgress) {
                Spacer(Modifier.width(4.dp))
                InProgressBadge()
            }
        }
        Text(event.countSignedUp.toString(),  modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 12.sp)
        Text(event.countShowedUp.toString(),  modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 12.sp)
        Text(event.countCompleted.toString(), modifier = Modifier.weight(0.5f), textAlign = TextAlign.Center, fontSize = 12.sp)
        RateCell(value = attPct, modifier = Modifier.weight(0.5f).padding(end = 6.dp))
        RateCell(value = cmpPct, modifier = Modifier.weight(0.5f))
    }
}

@Composable
private fun RateCell(value: Float, modifier: Modifier) {
    val color = when {
        value >= 70f -> Color(0xFF4CAF50)
        value >= 40f -> Color(0xFFFFC107)
        else         -> Color(0xFFF44336)
    }
    Text(
        text      = "%.0f%%".format(value),
        color     = color,
        modifier  = modifier,
        textAlign = TextAlign.End,
        fontSize  = 11.sp,
        maxLines  = 1,
    )
}

@Composable
private fun InProgressBadge() {
    val transition = rememberInfiniteTransition(label = "inProgress")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "inProgressAlpha",
    )
    Text(
        text = "In progress",
        fontSize = 9.sp,
        color = Color(0xFF4CAF50).copy(alpha = alpha),
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun EmptyState(onSyncClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text  = "No data yet — tap sync to fetch events",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onSyncClick) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sync now")
            }
        }
    }
}
