package com.becash.becashplayer

enum class RatingFilter {
    ALL, WITH_RATING, NO_RATING, TOP, BEST, DANCE, CALM;

    val label get() = when (this) {
        ALL         -> "Toate"
        WITH_RATING -> "Cu apreciere"
        NO_RATING   -> "Fără apreciere"
        TOP         -> "Top personal"
        BEST        -> "Top public"
        DANCE       -> "Dans"
        CALM        -> "Liniștit"
    }
}

enum class PlaylistMode {
    SHUFFLE, NORMAL, PLAY_ONE, MANUAL;

    fun next() = when (this) {
        SHUFFLE  -> NORMAL
        NORMAL   -> PLAY_ONE
        PLAY_ONE -> MANUAL
        MANUAL   -> SHUFFLE
    }

    val label get() = when (this) {
        SHUFFLE  -> "Shuffle"
        NORMAL   -> "Normal"
        PLAY_ONE -> "Play One"
        MANUAL   -> "Manual"
    }
}
