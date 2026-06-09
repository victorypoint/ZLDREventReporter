package com.victorypoint.zldreventreporter.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorypoint.zldreventreporter.BuildConfig
import com.victorypoint.zldreventreporter.ui.theme.ZldrRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val context          = LocalContext.current
    val cachedCount      by viewModel.cachedEventCount.collectAsState()
    val clearConfirm     by viewModel.clearConfirmVisible.collectAsState()
    val logoutConfirm    by viewModel.logoutConfirmVisible.collectAsState()
    val exportError      by viewModel.exportError.collectAsState()
    val importResult     by viewModel.importResult.collectAsState()
    val reportConfig     by viewModel.reportConfig.collectAsState()
    val isGenerating     by viewModel.isGeneratingReport.collectAsState()
    val reportError      by viewModel.reportError.collectAsState()
    var aboutVisible     by remember { mutableStateOf(false) }
    var helpVisible      by remember { mutableStateOf(false) }

    // Share sheet after export backup
    LaunchedEffect(Unit) {
        viewModel.exportShareUri.collect { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ZLDR Event Reporter Backup")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share backup"))
        }
    }

    // Share sheet after report generation
    LaunchedEffect(Unit) {
        viewModel.reportSharePayload.collect { payload ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = payload.mimeType
                putExtra(Intent.EXTRA_STREAM, payload.uri)
                putExtra(Intent.EXTRA_SUBJECT, "ZLDR Event Report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share report"))
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importData(it) } }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearConfirm,
            title = { Text("Clear database?") },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WARNING!", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Text("This will permanently delete all $cachedCount events from the database and reset the sync history. All historical data will be lost and cannot be recovered.")
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearCache) { Text("Clear database", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearConfirm) { Text("Cancel") }
            },
        )
    }

    if (logoutConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissLogoutConfirm,
            title = { Text("Log out?") },
            text  = { Text("You will need to sign in again to sync event data.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissLogoutConfirm()
                    viewModel.logout()
                    onLoggedOut()
                }) { Text("Log out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissLogoutConfirm) { Text("Cancel") }
            },
        )
    }

    if (helpVisible) {
        AlertDialog(
            onDismissRequest = { helpVisible = false },
            title = { Text("Help") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HelpSection("Reports screen") {
                        HelpItem("Reg / Shwd / Fin", "Registered (signed up), Showed Up (joined in-game), Finished (race results)")
                        HelpItem("Att%", "Showed Up ÷ Registered. Colour: green ≥70%, amber ≥40%, red <40%")
                        HelpItem("Cmp%", "Finished ÷ Showed Up")
                        HelpItem("Date view", "Events grouped by day, week, month, or year — toggle the filter in the top bar")
                        HelpItem("Series view", "Events grouped by recurring series and day of week")
                        HelpItem("Upcoming section", "Collapsed by default; Reg count shown, attendance stats not yet available")
                    }
                    HelpSection("Gestures") {
                        HelpItem("Tap", "Open event detail — route, distance, description, category breakdown")
                        HelpItem("Double-tap", "Open participant list for completed events — rank, name, time, distance")
                        HelpItem("Long press", "Open the event on zwift.com in your browser, or the Zwift Companion app if installed on the same device")
                        HelpItem("Re-fetch banner", "Appears on events with all-zero counts; resets the event so the next sync re-fetches it")
                    }
                    HelpSection("Sync") {
                        HelpItem("Auto-sync", "Runs on launch and each time the app returns to the foreground")
                        HelpItem("Background sync", "Re-syncs every 4 hours while the app is not in use")
                        HelpItem("In-progress polling", "While an event is live, sign-up counts refresh every 60 seconds")
                    }
                    HelpSection("Data") {
                        HelpItem("Export", "Saves all events to a JSON file you can share or back up")
                        HelpItem("Import", "Merges a JSON backup — imported record wins if the same event exists on this device")
                        HelpItem("Clear database", "Permanently deletes all events and resets sync history")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { helpVisible = false }) { Text("OK") }
            },
        )
    }

    if (aboutVisible) {
        AlertDialog(
            onDismissRequest = { aboutVisible = false },
            title = { Text("ZLDR Event Reporter") },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsRow(label = "Version",      value = BuildConfig.VERSION_NAME)
                    SettingsRow(label = "Build date",   value = BuildConfig.BUILD_DATE)
                    Text(
                        text  = "Developed by Alan Udell for the ZLDR community",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    Text(
                        text  = "This app relies on unofficial, unpublished Zwift APIs that are not officially supported. Zwift may change or remove them at any time without notice.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { aboutVisible = false }) { Text("OK") }
            },
        )
    }

    exportError?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::dismissExportError,
            title = { Text("Export error") },
            text  = { Text(msg) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissExportError) { Text("OK") }
            },
        )
    }

    importResult?.let { result ->
        AlertDialog(
            onDismissRequest = viewModel::dismissImportResult,
            title = {
                Text(if (result is ImportResult.Success) "Import complete" else "Import error")
            },
            text = {
                Text(
                    when (result) {
                        is ImportResult.Success -> "Imported ${result.count} events. Existing records were updated."
                        is ImportResult.Error   -> result.message
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissImportResult) { Text("OK") }
            },
        )
    }

    reportError?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::dismissReportError,
            title = { Text("Report error") },
            text  = { Text(msg) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissReportError) { Text("OK") }
            },
        )
    }

    reportConfig?.let { config ->
        ReportGeneratorDialog(
            config         = config,
            isGenerating   = isGenerating,
            onConfigChange = viewModel::updateReportConfig,
            onGenerate     = viewModel::generateReport,
            onDismiss      = viewModel::dismissReportGenerator,
        )
    }

    // ── Main content ─────────────────────────────────────────────────────────

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionHeader("Sync History")
            val lastSync = viewModel.lastSyncAt
            val lastSyncStr = if (lastSync == 0L) "Never" else
                SimpleDateFormat("EEE, MMM d yyyy  h:mm a", Locale.getDefault()).format(Date(lastSync))
            SettingsRow(label = "Last sync",      value = lastSyncStr)
            SettingsRow(label = "Cached events",  value = cachedCount.toString())

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionHeader("Reports")
            OutlinedButton(
                onClick  = viewModel::openReportGenerator,
                modifier = Modifier.fillMaxWidth(),
                enabled  = cachedCount > 0,
            ) {
                Text("Generate Report")
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionHeader("Data")
            OutlinedButton(
                onClick  = viewModel::exportData,
                modifier = Modifier.fillMaxWidth(),
                enabled  = cachedCount > 0,
            ) {
                Text("Export Data")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick  = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Import Data")
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionHeader("About")
            OutlinedButton(
                onClick  = { helpVisible = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Help")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick  = { aboutVisible = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("About")
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionHeader("Zwift Account")
            SettingsRow(label = "Signed in as", value = viewModel.username ?: "Unknown")
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick  = viewModel::requestLogout,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Log Out")
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionHeader("Database")
            OutlinedButton(
                onClick  = viewModel::requestClearCache,
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = ZldrRed),
                border   = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(ZldrRed)),
            ) {
                Text("Clear Database")
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelMedium,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier             = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HelpSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text  = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun HelpItem(label: String, description: String) {
    Column(modifier = Modifier.padding(start = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), lineHeight = 16.sp)
    }
}
