package com.becash.becashplayer

enum class PlaylistMode {
    SHUFFLE, NORMAL, PLAY_ONE, MANUAL;

    val label get() = when (this) {
        SHUFFLE  -> "Shuffle"
        NORMAL   -> "Normal"
        PLAY_ONE -> "Play One"
        MANUAL   -> "Manual"
    }
}
