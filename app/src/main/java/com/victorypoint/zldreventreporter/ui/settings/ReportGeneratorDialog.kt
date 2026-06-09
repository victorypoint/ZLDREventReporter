package com.victorypoint.zldreventreporter.ui.settings

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.victorypoint.zldreventreporter.ui.report.SportFilter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ReportGeneratorDialog(
    config: ReportConfig,
    isGenerating: Boolean,
    onConfigChange: (ReportConfig) -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    AlertDialog(
        onDismissRequest = { if (!isGenerating) onDismiss() },
        title = { Text("Generate Report") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // --- Sport ---
                DialogSectionLabel("Sport")
                SportFilter.entries.forEach { sport ->
                    RadioRow(
                        label = when (sport) {
                            SportFilter.ALL     -> "All sports"
                            SportFilter.CYCLING -> "Cycling"
                            SportFilter.RUNNING -> "Running"
                        },
                        selected = config.sport == sport,
                        onClick  = { onConfigChange(config.copy(sport = sport)) },
                    )
                }

                Spacer(Modifier.height(12.dp))

                // --- Date Range ---
                DialogSectionLabel("Date range")
                DateRangeOption.entries.forEach { option ->
                    RadioRow(
                        label    = option.label,
                        selected = config.dateRange == option,
                        onClick  = { onConfigChange(config.copy(dateRange = option)) },
                    )
                }

                if (config.dateRange == DateRangeOption.CUSTOM) {
                    Spacer(Modifier.height(4.dp))
                    // From
                    Surface(
                        shape  = MaterialTheme.shapes.small,
                        color  = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 0.dp, bottom = 4.dp)
                            .clickable {
                                val cal = Calendar.getInstance().apply { timeInMillis = config.customFromMs }
                                DatePickerDialog(
                                    context,
                                    { _, y, m, d ->
                                        val ms = Calendar.getInstance().apply {
                                            set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
                                        }.timeInMillis
                                        onConfigChange(config.copy(customFromMs = ms))
                                    },
                                    cal.get(Calendar.YEAR),
                                    cal.get(Calendar.MONTH),
                                    cal.get(Calendar.DAY_OF_MONTH),
                                ).show()
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("From", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(dateFmt.format(Date(config.customFromMs)),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // To
                    Surface(
                        shape  = MaterialTheme.shapes.small,
                        color  = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 0.dp)
                            .clickable {
                                val cal = Calendar.getInstance().apply { timeInMillis = config.customToMs }
                                DatePickerDialog(
                                    context,
                                    { _, y, m, d ->
                                        val ms = Calendar.getInstance().apply {
                                            set(y, m, d, 23, 59, 59); set(Calendar.MILLISECOND, 999)
                                        }.timeInMillis
                                        onConfigChange(config.copy(customToMs = ms))
                                    },
                                    cal.get(Calendar.YEAR),
                                    cal.get(Calendar.MONTH),
                                    cal.get(Calendar.DAY_OF_MONTH),
                                ).show()
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("To", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(dateFmt.format(Date(config.customToMs)),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // --- Events ---
                DialogSectionLabel("Include")
                EventScope.entries.forEach { scope ->
                    RadioRow(
                        label    = scope.label,
                        selected = config.eventScope == scope,
                        onClick  = { onConfigChange(config.copy(eventScope = scope)) },
                    )
                }

                Spacer(Modifier.height(12.dp))

                // --- View ---
                DialogSectionLabel("View")
                ReportView.entries.forEach { view ->
                    RadioRow(
                        label    = view.label,
                        selected = config.reportView == view,
                        onClick  = { onConfigChange(config.copy(reportView = view)) },
                    )
                }

                Spacer(Modifier.height(12.dp))

                // --- Format ---
                DialogSectionLabel("Format")
                ReportFormat.entries.forEach { fmt ->
                    RadioRow(
                        label    = fmt.label,
                        selected = config.format == fmt,
                        onClick  = { onConfigChange(config.copy(format = fmt)) },
                    )
                }

                if (isGenerating) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onGenerate, enabled = !isGenerating) {
                Text("Generate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isGenerating) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun DialogSectionLabel(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelMedium,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick  = onClick,
            modifier = Modifier.size(36.dp),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
