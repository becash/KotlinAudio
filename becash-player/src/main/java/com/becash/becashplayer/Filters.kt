package com.becash.becashplayer

import com.doublesymmetry.kotlinaudio.models.AudioItem
import org.json.JSONObject

// -------------------------------------------------------------------------
// Filtrarea și ordonarea cântecelor — sursă unică pentru ViewModel și UI
// -------------------------------------------------------------------------

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

/** Ordinea de afișare a playlistului: artist, apoi titlu. */
val byArtistTitle = compareBy<AudioItem>({ it.artist ?: "" }, { it.title ?: "" })

/** Câmpurile boolean din MySQL/playlist.json pot veni ca true, 1 sau "1". */
fun JSONObject?.optFlag(key: String): Boolean =
    this?.opt(key).let { it == true || it == 1 || it?.toString() == "1" }

fun matchesTextFilter(item: AudioItem, query: String, inverted: Boolean): Boolean {
    val q = query.trim().lowercase()
    val matches = (item.title ?: "").lowercase().contains(q) ||
                  (item.artist ?: "").lowercase().contains(q)
    return if (inverted) !matches else matches
}

fun passesRatingFilter(songId: String, songInfoMap: Map<String, JSONObject>, filter: RatingFilter): Boolean {
    val info = songInfoMap[songId]
    val rate = info?.optInt("rate", 0) ?: 0
    return when (filter) {
        RatingFilter.WITH_RATING -> rate > 0
        RatingFilter.NO_RATING   -> rate == 0
        RatingFilter.TOP         -> rate >= 4
        RatingFilter.BEST        -> rate == 5
        RatingFilter.DANCE       -> info.optFlag("dance")
        RatingFilter.CALM        -> info.optFlag("calm")
        RatingFilter.ALL         -> true
    }
}
