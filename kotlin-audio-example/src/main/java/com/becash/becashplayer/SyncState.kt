package com.becash.becashplayer

sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val current: Int, val total: Int, val currentFile: String) : SyncState()
    data class Done(val downloaded: Int, val skipped: Int) : SyncState()
    data class Error(val message: String) : SyncState()
}
