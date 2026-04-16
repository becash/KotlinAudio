package com.becash.becashplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.pow

object PlaylistStore {

    private const val FILE_NAME = "playlist.json"
    private const val MS_PER_MONTH = 30L * 24 * 60 * 60 * 1000

    /** Returnează map: id ("/883/song.mp3") → JSONObject cu datele MySQL */
    fun loadAsMap(context: Context): Map<String, JSONObject> {
        val file = File(context.getExternalFilesDir(null), FILE_NAME)
        if (!file.exists()) return emptyMap()
        return try {
            val arr = JSONArray(file.readText())
            val result = mutableMapOf<String, JSONObject>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                result[id] = obj
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun load(context: Context, baseDir: String = ""): List<String> {
        val file = File(context.getExternalFilesDir(null), FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val id = arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (baseDir.isNotEmpty()) "${baseDir.trimEnd('/')}/${id.trimStart('/')}" else id
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Returnează map: id ("/883/song.mp3") → shuffle_weight calculat la ultima sincronizare */
    fun loadWeightsMap(context: Context): Map<String, Double> {
        val file = File(context.getExternalFilesDir(null), FILE_NAME)
        if (!file.exists()) return emptyMap()
        return try {
            val arr = JSONArray(file.readText())
            val result = mutableMapOf<String, Double>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val weight = obj.optDouble("shuffle_weight", -1.0)
                if (weight > 0) result[id] = weight
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(context: Context, paths: List<String>, baseDir: String = "") {
        val arr = JSONArray()
        val prefix = if (baseDir.isNotEmpty()) "${baseDir.trimEnd('/')}/" else ""
        paths.forEach { path ->
            val id = if (prefix.isNotEmpty()) path.removePrefix(prefix) else path
            arr.put(JSONObject().put("id", id))
        }
        File(context.getExternalFilesDir(null), FILE_NAME).writeText(arr.toString(2).replace("\\/", "/"))
    }

    /**
     * Salvează playlist-ul îmbogățit cu datele din MySQL și calculează coeficienții
     * de shuffle ponderat (câmp "shuffle_weight"):
     *   - plays=0 → weight = 1000 × (max_plays+1)
     *   - plays>0 → weight = 1000 × max_plays / plays  (proporțional invers)
     *   - greutatea se dublează pentru fiecare lună în urmă față de câmpul "updated"
     *   - se calculează și câmpul "completeness" (listen / duration×plays)
     */
    fun saveEnriched(context: Context, ids: List<String>, infoMap: Map<String, JSONObject>) {
        val now = System.currentTimeMillis()
        val normalizedIds = ids.map { if (it.startsWith("/")) it else "/$it" }
        val weights = calculateShuffleWeights(normalizedIds, infoMap, now)

        val arr = JSONArray()
        normalizedIds.forEach { id ->
            val obj = infoMap[id]?.let { info ->
                JSONObject(info.toString()).also { it.put("id", id) }
            } ?: JSONObject().put("id", id)
            obj.put("shuffle_weight", weights[id] ?: 1.0)
            // Pre-calculează completitudinea (listen / duration*plays) — evită calcul în UI
            val listen   = obj.optLong("listen", 0L)
            val duration = obj.optLong("duration", 0L)
            val plays    = obj.optInt("plays", 0)
            if (duration > 0 && plays > 0 && listen > 0) {
                obj.put("completeness", (listen.toDouble() / (duration.toDouble() * plays)).coerceIn(0.0, 1.0))
            }
            arr.put(obj)
        }
        File(context.getExternalFilesDir(null), FILE_NAME).writeText(arr.toString(2).replace("\\/", "/"))
    }

    /**
     * Calculează greutatea fiecărui cântec pentru shuffle ponderat:
     *
     * 1) Baza din numărul de redări (câmpul "plays"), formula: 1000 × max_plays / plays
     *    - plays=0 (niciodată redat): exclus din formulă, primește weight fix = 1000 × (max+1)
     *    - plays>0: weight = 1000 × max_plays / plays
     *      Exemplu max=5: plays=1→5000, plays=2→2500, plays=3→1667, plays=5→1000
     *
     * 2) Factorul de vechime din câmpul "updated":
     *    - weight × 2^luni_de_când_a_cântat_ultima_dată
     */
    private fun calculateShuffleWeights(
        ids: List<String>,
        infoMap: Map<String, JSONObject>,
        now: Long,
    ): Map<String, Double> {
        val playsValues = ids.map { id -> infoMap[id]?.optInt("plays", 0) ?: 0 }
        // maxPlays se calculează doar printre cântecele care au fost redate cel puțin o dată
        val maxPlays = playsValues.filter { it > 0 }.maxOrNull() ?: 1

        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        return ids.mapIndexed { index, id ->
            val plays = playsValues[index]
            // plays=0 → weight fix deasupra celui mai puțin redat cântec
            // plays>0 → proporțional invers cu numărul de redări
            var weight = if (plays == 0) {
                1000.0 * (maxPlays + 1)
            } else {
                1000.0 * maxPlays.toDouble() / plays.toDouble()
            }

            // Factor de vechime: dublu pentru fiecare lună de când a cântat ultima oară
            val updatedRaw = infoMap[id]?.optString("updated")
                ?.takeIf { it.isNotBlank() && it != "null" }
            if (updatedRaw != null) {
                val cleanStr = updatedRaw.substringBefore(".").trim()
                val updatedTime = try { dateFmt.parse(cleanStr)?.time } catch (_: Exception) { null }
                if (updatedTime != null && now > updatedTime) {
                    val monthsAgo = ((now - updatedTime) / MS_PER_MONTH).coerceAtLeast(0L)
                    if (monthsAgo > 0) weight *= 2.0.pow(monthsAgo.toDouble())
                }
            }

            id to weight
        }.toMap()
    }
}