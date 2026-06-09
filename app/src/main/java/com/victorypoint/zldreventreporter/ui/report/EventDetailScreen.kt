package com.victorypoint.zldreventreporter.ui.report

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorypoint.zldreventreporter.data.events.dto.EventSubgroupDto
import com.victorypoint.zldreventreporter.data.events.dto.ZwiftEventDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy · h:mm a z")

private val CATEGORY_LABELS = mapOf(1 to "A", 2 to "B", 3 to "C", 4 to "D", 5 to "E")

private fun EventSubgroupDto.categoryLabel(): String =
    subgroupLabel ?: CATEGORY_LABELS[label] ?: label?.toString() ?: ""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    viewModel: EventDetailViewModel,
    onBack: () -> Unit,
) {
    val uiState      by viewModel.uiState.collectAsState()
    val canRefetch   by viewModel.canRefetch.collectAsState()
    val refetchState by viewModel.refetchState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState) {
        if (uiState is EventDetailUiState.Error) {
            snackbarHostState.showSnackbar((uiState as EventDetailUiState.Error).message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                EventDetailUiState.Loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                is EventDetailUiState.Success -> {
                    EventDetail(
                        event         = state.event,
                        isCompleted   = state.isCompleted,
                        canRefetch    = canRefetch,
                        refetchState  = refetchState,
                        onRefetch     = viewModel::refetch,
                    )
                }
                is EventDetailUiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text  = state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = viewModel::retry) { Text("Retry") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventDetail(
    event: ZwiftEventDto,
    isCompleted: Boolean,
    canRefetch: Boolean,
    refetchState: RefetchState,
    onRefetch: () -> Unit,
) {
    val subgroups = event.eventSubgroups.orEmpty()
    val showBanner = canRefetch || refetchState !is RefetchState.Idle

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header: sport icon + name
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (event.sport?.uppercase()) {
                        "CYCLING" -> Icons.AutoMirrored.Filled.DirectionsBike
                        "RUNNING" -> Icons.AutoMirrored.Filled.DirectionsRun
                        else      -> Icons.Default.Event
                    },
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text     = event.name.orEmpty(),
                    style    = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Refetch banner — shown for completed events with all-zero counts
        if (showBanner) {
            item { RefetchBanner(state = refetchState, onRefetch = onRefetch) }
        }

        // Start / Completed date row
        event.eventStart?.let { startStr ->
            val startInstant = runCatching { Instant.parse(startStr) }
                .recoverCatching {
                    java.time.OffsetDateTime.parse(
                        startStr,
                        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]X"),
                    ).toInstant()
                }.getOrNull()
            startInstant?.let { instant ->
                item {
                    val local = instant.atZone(ZoneId.systemDefault())
                    InfoRow(
                        label = if (isCompleted) "Completed" else "Starts",
                        value = DATE_FMT.format(local),
                    )
                }
            }
        }

        // Route
        event.routeName?.let { route ->
            item { InfoRow(label = "Route", value = route) }
        }

        // Distance / Duration / Laps
        val hasDistance = (event.distanceInMeters ?: 0.0) > 0.0
        val hasDuration = (event.durationInSeconds ?: 0) > 0
        val hasLaps     = (event.laps ?: 0) > 0
        if (hasDistance || hasDuration || hasLaps) {
            item {
                val parts = buildList {
                    if (hasDistance) add("%.1f km".format(event.distanceInMeters!! / 1000.0))
                    if (hasLaps)     add("${event.laps} laps")
                    if (hasDuration) {
                        val s = event.durationInSeconds!!
                        val h = s / 3600; val m = (s % 3600) / 60
                        add(if (h > 0) "${h}h ${m}m" else "${m}m")
                    }
                }
                val label = when {
                    hasDistance && hasDuration -> "Distance / Duration"
                    hasDistance || hasLaps     -> "Distance"
                    else                       -> "Duration"
                }
                InfoRow(label = label, value = parts.joinToString(" · "))
            }
        }

        // Description / About
        val description = event.description.orEmpty().trim()
        if (description.isNotBlank()) {
            item {
                Column {
                    Text(
                        text  = "About",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(text = description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Categories
        if (subgroups.isNotEmpty()) {
            item {
                Text(
                    text  = "Categories",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(subgroups) { sg -> SubgroupCard(sg) }
        }
    }
}

@Composable
private fun RefetchBanner(state: RefetchState, onRefetch: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        ),
    ) {
        when (state) {
            is RefetchState.Idle -> {
                Row(
                    modifier          = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text     = "No attendance data was recorded for this event.",
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        color    = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onRefetch) { Text("Re-fetch") }
                }
            }
            is RefetchState.InProgress -> {
                Row(
                    modifier          = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Syncing…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            is RefetchState.Done -> {
                Row(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text     = if (state.gotData)
                            "Data updated — go back to see the new counts."
                        else
                            "Sync complete. Zwift returned no data; results may not be posted yet.",
                        fontSize = 12.sp,
                        color    = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(2.dp))
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SubgroupCard(sg: EventSubgroupDto) {
    val categoryLabel = sg.categoryLabel()
    val name = sg.name.orEmpty()

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (categoryLabel.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text     = categoryLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                if (name.isNotBlank() && name != categoryLabel) {
                    Text(name, style = MaterialTheme.typography.bodySmall)
                }
                val detail = buildList {
                    sg.distanceInMeters?.takeIf { it > 0 }?.let { add("%.1f km".format(it / 1000.0)) }
                    sg.laps?.takeIf { it > 0 }?.let { add("$it laps") }
                }.joinToString(" · ")
                if (detail.isNotBlank()) {
                    Text(
                        text  = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            val riderCount = maxOf(sg.signedUpCount ?: 0, sg.totalEntrantCount ?: 0)
            if (riderCount > 0) {
                Text(
                    text  = if (riderCount == 1) "1 rider" else "$riderCount riders",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }
}
