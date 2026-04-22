package com.becash.becashplayer

import android.content.Context
import io.sentry.Sentry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.pow

class PlaylistStore {

    companion object {
        private const val FILE_NAME = "playlist.json"
        private const val MS_PER_MONTH = 30L * 24 * 60 * 60 * 1000
        private const val MAX_AGE_MONTHS = 12L
    }

    private fun readJsonArray(context: Context): JSONArray {
        val dir = context.getExternalFilesDir(null) ?: run {
            Sentry.captureMessage("PlaylistStore: getExternalFilesDir=null la citire playlist")
            return JSONArray()
        }
        val file = File(dir, FILE_NAME)
        if (!file.exists()) return JSONArray()
        return try {
            JSONArray(file.readText())
        } catch (e: Exception) {
            Sentry.captureException(e)
            JSONArray()
        }
    }

    private fun writeJsonArray(context: Context, arr: JSONArray) {
        val dir = context.getExternalFilesDir(null) ?: run {
            Sentry.captureMessage("PlaylistStore: getExternalFilesDir=null la scriere playlist")
            return
        }
        try {
            File(dir, FILE_NAME).writeText(arr.toString(2).replace("\\/", "/"))
        } catch (e: Exception) {
            Sentry.captureException(e)
        }
    }

    fun loadAsMap(context: Context): Map<String, JSONObject> {
        val arr = readJsonArray(context)
        val result = mutableMapOf<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            result[id] = obj
        }
        return result
    }

    fun load(context: Context, baseDir: String = ""): List<String> {
        val arr = readJsonArray(context)
        return (0 until arr.length()).mapNotNull { i ->
            val id = arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (baseDir.isNotEmpty()) "${baseDir.trimEnd('/')}/${id.trimStart('/')}" else id
        }
    }

    fun loadWeightsMap(context: Context): Map<String, Double> {
        val arr = readJsonArray(context)
        val result = mutableMapOf<String, Double>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val weight = obj.optDouble("shuffle_weight", -1.0)
            if (weight > 0) result[id] = weight
        }
        return result
    }

    fun save(context: Context, paths: List<String>, baseDir: String = "") {
        val arr = JSONArray()
        val prefix = if (baseDir.isNotEmpty()) "${baseDir.trimEnd('/')}/" else ""
        paths.forEach { path ->
            val id = if (prefix.isNotEmpty()) path.removePrefix(prefix) else path
            arr.put(JSONObject().put("id", id))
        }
        writeJsonArray(context, arr)
    }

    /**
     * Salvează playlist-ul îmbogățit cu datele din MySQL și calculează coeficienții
     * de shuffle ponderat (câmp "shuffle_weight"):
     *   - plays=0 → weight = 1000 × (max_plays+1)
     *   - plays>0 → weight = 1000 × max_plays / plays  (proporțional invers)
     *   - greutatea se dublează pentru fiecare lună (max 12) față de câmpul "updated"
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
            val listen   = obj.optLong("listen", 0L)
            val duration = obj.optLong("duration", 0L)
            val plays    = obj.optInt("plays", 0)
            if (duration > 0 && plays > 0 && listen > 0) {
                obj.put("completeness", (listen.toDouble() / (duration.toDouble() * plays)).coerceIn(0.0, 1.0))
            }
            arr.put(obj)
        }
        writeJsonArray(context, arr)
    }

    private fun calculateShuffleWeights(
        ids: List<String>,
        infoMap: Map<String, JSONObject>,
        now: Long,
    ): Map<String, Double> {
        val playsValues = ids.map { id -> infoMap[id]?.optInt("plays", 0) ?: 0 }
        val maxPlays = playsValues.filter { it > 0 }.maxOrNull() ?: 1

        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        return ids.mapIndexed { index, id ->
            val plays = playsValues[index]
            var weight = if (plays == 0) {
                1000.0 * (maxPlays + 1)
            } else {
                1000.0 * maxPlays.toDouble() / plays.toDouble()
            }

            val updatedRaw = infoMap[id]?.optString("updated")
                ?.takeIf { it.isNotBlank() && it != "null" }
            if (updatedRaw != null) {
                val cleanStr = updatedRaw.substringBefore(".").trim()
                val updatedTime = try { dateFmt.parse(cleanStr)?.time } catch (_: Exception) { null }
                if (updatedTime != null && now > updatedTime) {
                    val monthsAgo = ((now - updatedTime) / MS_PER_MONTH).coerceIn(0L, MAX_AGE_MONTHS)
                    if (monthsAgo > 0) weight *= 2.0.pow(monthsAgo.toDouble())
                }
            }

            id to weight
        }.toMap()
    }
}
