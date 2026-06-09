package com.victorypoint.zldreventreporter.ui.report

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.victorypoint.zldreventreporter.data.db.EventStatEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventPeriodDetailScreen(
    viewModel: EventPeriodDetailViewModel,
    onBack: () -> Unit,
) {
    val events by viewModel.events.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Detail") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No events found for this period.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(events, key = { it.eventId }) { event ->
                    EventDetailCard(event)
                }
            }
        }
    }
}

@Composable
private fun EventDetailCard(event: EventStatEntity) {
    val dateStr = SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault())
        .format(Date(event.eventDate))
    val sportIcon = if (event.sport == "RUNNING") Icons.AutoMirrored.Filled.DirectionsRun
                    else Icons.AutoMirrored.Filled.DirectionsBike

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = sportIcon,
                contentDescription = event.sport,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(event.eventName, style = MaterialTheme.typography.titleSmall)
                Text(
                    dateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                StatLine(label = "Signed up", value = event.countSignedUp)
                StatLine(label = "Showed up", value = event.countShowedUp)
                StatLine(label = "Completed", value = event.countCompleted)
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Text(value.toString(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
