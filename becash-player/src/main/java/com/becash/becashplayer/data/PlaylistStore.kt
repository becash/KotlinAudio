package com.becash.becashplayer.data

import android.content.Context
import android.os.Build
import android.os.Environment
import io.sentry.Sentry
import io.sentry.SentryLevel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PlaylistStore {

    companion object {
        private const val FILE_NAME = "playlist.json"
        private val FILE_LOCK = Any()
    }

    private fun readJsonArray(context: Context): JSONArray {
        val dir = context.getExternalFilesDir(null) ?: run {
            Sentry.captureMessage("PlaylistStore: getExternalFilesDir=null la citire playlist")
            return JSONArray()
        }
        val file = File(dir, FILE_NAME)
        if (!file.exists()) {
            Sentry.captureMessage(
                "PlaylistStore: $FILE_NAME lipsă | cale=${file.absolutePath} | isExternalStorageManager=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else "n/a"}",
                SentryLevel.WARNING
            )
            return JSONArray()
        }
        val fileSize = file.length()
        if (fileSize == 0L) {
            Sentry.captureMessage(
                "PlaylistStore: $FILE_NAME gol (0 bytes) | cale=${file.absolutePath}",
                SentryLevel.WARNING
            )
            return JSONArray()
        }
        return try {
            val arr = JSONArray(file.readText())
            val bareCount = (0 until arr.length()).count { i ->
                (arr.optJSONObject(i)?.length() ?: 0) <= 1
            }
            if (arr.length() > 0 && bareCount == arr.length()) {
                Sentry.captureMessage(
                    "PlaylistStore: playlist conține DOAR ID-uri (${arr.length()} intrări fără date îmbogățite) | fișier=${fileSize}B",
                    SentryLevel.WARNING
                )
            }
            arr
        } catch (e: Exception) {
            Sentry.withScope { scope ->
                scope.setExtra("file_size_bytes", fileSize.toString())
                scope.setExtra("file_path", file.absolutePath)
                Sentry.captureException(e)
            }
            JSONArray()
        }
    }

    // Scriere atomică: mai întâi în fișier temporar, apoi rename (atomic pe același filesystem).
    // Previne citirea unui fișier parțial scris în caz de acces concurent sau crash.
    private fun writeJsonArray(context: Context, arr: JSONArray) {
        val dir = context.getExternalFilesDir(null) ?: run {
            Sentry.captureMessage("PlaylistStore: getExternalFilesDir=null la scriere playlist")
            return
        }
        val bareCount = (0 until arr.length()).count { i ->
            (arr.optJSONObject(i)?.length() ?: 0) <= 1
        }
        val tmp = File(dir, "$FILE_NAME.tmp")
        val target = File(dir, FILE_NAME)
        val text = arr.toString(2).replace("\\/", "/")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                runCatching { tmp.delete() }
            }
            if (arr.length() > 0 && bareCount == arr.length()) {
                Sentry.captureMessage(
                    "PlaylistStore: scris playlist cu DOAR ID-uri (${arr.length()} intrări, fără date îmbogățite)",
                    SentryLevel.WARNING
                )
            }
        } catch (e: Exception) {
            Sentry.captureException(e)
            runCatching { tmp.delete() }
        }
    }

    fun loadAsMap(context: Context): Map<String, JSONObject> = synchronized(FILE_LOCK) {
        val arr = readJsonArray(context)
        val result = mutableMapOf<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            result[id] = obj
        }
        result
    }

    fun load(context: Context, baseDir: String = ""): List<String> = synchronized(FILE_LOCK) {
        val arr = readJsonArray(context)
        (0 until arr.length()).mapNotNull { i ->
            val id = arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (baseDir.isNotEmpty()) "${baseDir.trimEnd('/')}/${id.trimStart('/')}" else id
        }
    }

    /**
     * Persistă starea curentă (din memorie) ca atare, FĂRĂ recalcularea greutăților.
     * Folosit de flush-ul debounced după zeroizarea in-memory a `shuffle_weight`:
     * sursa de adevăr e harta din memorie (`songInfoMap` în PlayerViewModel), iar
     * fișierul e doar proiecția ei — scrisă rar, pe triggere, nu la fiecare tranziție.
     */
    fun persist(context: Context, infoMap: Map<String, JSONObject>) = synchronized(FILE_LOCK) {
        val arr = JSONArray()
        infoMap.forEach { (id, info) ->
            val obj = JSONObject(info.toString())
            if (obj.optString("id").isBlank()) obj.put("id", id)
            arr.put(obj)
        }
        writeJsonArray(context, arr)
    }

    /**
     * Salvează playlist-ul îmbogățit cu datele din MySQL și calculează coeficienții
     * de shuffle ponderat (câmp "shuffle_weight"):
     *   - plays=0 → weight = 1.0           (neascultată — cea mai grea)
     *   - plays>0 → weight = 1 / plays²    (invers pătratic; doar raportul contează în ruletă)
     *   - se calculează și câmpul "completeness" (listen / duration×plays)
     */
    fun saveEnriched(context: Context, ids: List<String>, infoMap: Map<String, JSONObject>) = synchronized(FILE_LOCK) {
        if (ids.isNotEmpty() && infoMap.isEmpty()) {
            Sentry.captureMessage(
                "PlaylistStore.saveEnriched: infoMap gol → playlist se va salva cu DOAR ID-uri | ids.size=${ids.size}",
                SentryLevel.WARNING
            )
        }
        val normalizedIds = ids.map { if (it.startsWith("/")) it else "/$it" }
        val weights = calculateShuffleWeights(normalizedIds, infoMap)

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
    ): Map<String, Double> = ids.associateWith { id ->
        val plays = infoMap[id]?.optInt("plays", 0) ?: 0
        // Doar raportul dintre greutăți contează în ruletă, nu mărimea absolută → scară mică:
        //   neascultată (plays=0) → 1.0;  ascultată → 1/plays² (2 redări → 0.25, 10 → 0.01).
        if (plays == 0) 1.0 else 1.0 / (plays.toDouble() * plays)
    }
}
