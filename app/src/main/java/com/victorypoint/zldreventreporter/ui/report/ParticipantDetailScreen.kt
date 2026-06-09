package com.victorypoint.zldreventreporter.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorypoint.zldreventreporter.data.events.dto.RaceResultEntryDto

private fun formatDuration(ms: Long): String {
    val totalSecs = ms / 1000
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    return if (h > 0) "${h}h ${"%02d".format(m)}m"
    else "${m}m ${"%02d".format(s)}s"
}

private fun formatDistance(meters: Int): String =
    if (meters >= 1000) "${"%.1f".format(meters / 1000f)} km"
    else "$meters m"

private fun displayName(entry: RaceResultEntryDto): String {
    val first = entry.profileData?.firstName?.trim().orEmpty()
    val last  = entry.profileData?.lastName?.trim().orEmpty()
    return when {
        first.isNotEmpty() && last.isNotEmpty() -> "$first $last"
        last.isNotEmpty()  -> last
        first.isNotEmpty() -> first
        else -> "—"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParticipantDetailScreen(
    viewModel: ParticipantDetailViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    val title = when (val s = uiState) {
        is ParticipantUiState.Success -> s.eventName
        else -> "Finishers"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text     = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val state = uiState) {
            ParticipantUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            is ParticipantUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            is ParticipantUiState.Success -> {
                val entries    = state.entries
                val lateCount  = entries.count { it.lateJoin == true }

                Column(modifier = Modifier.padding(padding)) {
                    // Summary bar
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            SummaryChip(label = "Finishers", value = entries.size.toString())
                            SummaryChip(label = "Late joins", value = lateCount.toString())
                        }
                    }
                    HorizontalDivider()

                    if (entries.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text  = if (state.resultsConfirmed)
                                            "No one has completed this event."
                                        else
                                            "Results not yet available for this event.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    } else {
                        LazyColumn {
                            item { ParticipantHeader() }
                            itemsIndexed(entries) { index, entry ->
                                ParticipantRow(entry = entry, isEven = index % 2 == 1)
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun ParticipantHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#",     modifier = Modifier.weight(0.4f), textAlign = TextAlign.End,    fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Text("Name",  modifier = Modifier.weight(2.5f), textAlign = TextAlign.Start,  fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("Late",  modifier = Modifier.weight(0.7f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("Time",  modifier = Modifier.weight(1.1f), textAlign = TextAlign.End,    fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("Dist",  modifier = Modifier.weight(1.0f), textAlign = TextAlign.End,    fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun ParticipantRow(entry: RaceResultEntryDto, isEven: Boolean) {
    val dimColor  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val lateColor = MaterialTheme.colorScheme.tertiary
    val bgColor   = if (isEven) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text      = entry.rank?.toString() ?: "—",
            modifier  = Modifier.weight(0.4f),
            textAlign = TextAlign.End,
            fontSize  = 12.sp,
            fontWeight = FontWeight.Medium,
            color     = if (entry.rank == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text     = displayName(entry),
            modifier = Modifier.weight(2.5f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text      = if (entry.lateJoin == true) "True" else "—",
            modifier  = Modifier.weight(0.7f),
            textAlign = TextAlign.Center,
            fontSize  = 11.sp,
            color     = if (entry.lateJoin == true) lateColor else dimColor,
            fontWeight = if (entry.lateJoin == true) FontWeight.Medium else FontWeight.Normal,
        )
        Text(
            text      = entry.activityData?.durationInMilliseconds
                            ?.let { formatDuration(it) } ?: "—",
            modifier  = Modifier.weight(1.1f),
            textAlign = TextAlign.End,
            fontSize  = 12.sp,
        )
        Text(
            text      = entry.activityData?.segmentDistanceInMeters
                            ?.let { formatDistance(it) } ?: "—",
            modifier  = Modifier.weight(1.0f),
            textAlign = TextAlign.End,
            fontSize  = 12.sp,
        )
    }
}
