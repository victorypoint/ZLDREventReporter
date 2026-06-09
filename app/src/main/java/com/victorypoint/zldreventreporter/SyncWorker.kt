package com.victorypoint.zldreventreporter

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.victorypoint.zldreventreporter.data.events.SyncProgress

private const val TAG = "SyncWorker"

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ZldrReporterApplication
        return try {
            app.eventSyncRepository.syncNow().collect { progress ->
                if (progress is SyncProgress.Error) {
                    Log.w(TAG, "Background sync error: ${progress.message}")
                }
            }
            Log.d(TAG, "Background sync completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Background sync failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "zldr_daily_sync"
    }
}
