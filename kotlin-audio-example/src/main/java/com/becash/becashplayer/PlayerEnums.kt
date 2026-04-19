package com.becash.becashplayer

enum class RatingFilter {
    ALL, WITH_RATING, NO_RATING, TOP, BEST, DANCE, CALM, RATE2;

    val label get() = when (this) {
        ALL         -> "Toate"
        WITH_RATING -> "Cu apreciere"
        NO_RATING   -> "Fără apreciere"
        TOP         -> "Top personal"
        BEST        -> "Top public"
        DANCE       -> "Dans"
        CALM        -> "Liniștit"
        RATE2       -> "Apreciere 2"
    }
}

enum class PlaylistMode {
    SHUFFLE, NORMAL, PLAY_ONE;

    fun next() = when (this) {
        SHUFFLE  -> NORMAL
        NORMAL   -> PLAY_ONE
        PLAY_ONE -> SHUFFLE
    }

    val label get() = when (this) {
        SHUFFLE  -> "Shuffle"
        NORMAL   -> "Normal"
        PLAY_ONE -> "Play One"
    }
}
