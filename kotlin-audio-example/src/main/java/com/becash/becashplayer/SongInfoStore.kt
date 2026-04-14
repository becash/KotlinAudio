package com.becash.becashplayer

import android.content.Context
import org.json.JSONObject
import java.io.File

object SongInfoStore {
    private const val FILE_NAME = "song_info.json"

    /** Returnează map: cheie relativă ("883/song.mp3") → tot documentul MongoDB ca JSONObject */
    fun load(context: Context): Map<String, JSONObject> {
        val file = File(context.getExternalFilesDir(null), FILE_NAME)
        if (!file.exists()) return emptyMap()
        return try {
            val obj = JSONObject(file.readText())
            val result = mutableMapOf<String, JSONObject>()
            for (key in obj.keys()) {
                result[key] = obj.optJSONObject(key) ?: continue
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Salvează map: cheie relativă → document MongoDB complet */
    fun save(context: Context, data: Map<String, JSONObject>) {
        val obj = JSONObject()
        data.forEach { (key, doc) -> obj.put(key, doc) }
        File(context.getExternalFilesDir(null), FILE_NAME).writeText(obj.toString(2))
    }
}