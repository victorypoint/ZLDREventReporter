package com.victorypoint.zldreventreporter.data

import android.content.Context

private const val PREFS_FILE = "sync_metadata"
private const val KEY_LAST_SYNC_AT        = "last_sync_at"
private const val KEY_LAST_NAME_SCAN_AT   = "last_name_scan_at"
private const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"

class SyncMetadataStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_AT, value).apply()

    var lastNameScanAt: Long
        get() = prefs.getLong(KEY_LAST_NAME_SCAN_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_NAME_SCAN_AT, value).apply()

    var batteryPromptShown: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PROMPT_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_PROMPT_SHOWN, value).apply()
}
