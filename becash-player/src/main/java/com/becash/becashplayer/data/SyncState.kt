package com.becash.becashplayer.data

sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val current: Int, val total: Int, val currentFile: String) : SyncState()
    data class Done(val downloaded: Int, val skipped: Int, val deleted: Int = 0) : SyncState()
    data class Error(val message: String) : SyncState()
}
