package com.victorypoint.zldreventreporter.data.events

sealed interface SyncProgress {
    data class Loading(val fetched: Int, val total: Int) : SyncProgress
    data class Success(val newRecords: Int) : SyncProgress
    data class Error(val message: String) : SyncProgress
}
