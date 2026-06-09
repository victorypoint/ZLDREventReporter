package com.victorypoint.zldreventreporter.ui

import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

private val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

@Composable
fun BatteryOptimizationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val samsungNote = if (isSamsung) {
        "\n\nSamsung note: also go to Settings → Battery → Background usage limits → " +
        "Sleeping apps and remove ZLDR Event Reporter from the list if it appears there."
    } else ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable Background Sync") },
        text = {
            Text(
                "ZLDR Event Reporter syncs your event data every 4 hours in the background. " +
                "To ensure this works reliably, please tap Allow on the next screen.$samsungNote"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Open Settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Skip") }
        },
    )
}
