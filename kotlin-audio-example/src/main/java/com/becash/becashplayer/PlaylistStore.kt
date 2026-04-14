package com.becash.becashplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PlaylistStore {

    private const val FILE_NAME = "playlist.json"

    fun load(context: Context, baseDir: String = ""): List<String> {
        val file = File(context.getExternalFilesDir(null), FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val id = arr.optJSONObject(i)?.optString("_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (baseDir.isNotEmpty() && !id.startsWith("/")) "$baseDir/$id" else id
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, paths: List<String>, baseDir: String = "") {
        val arr = JSONArray()
        val prefix = if (baseDir.isNotEmpty()) "${baseDir.trimEnd('/')}/" else ""
        paths.forEach { path ->
            val id = if (prefix.isNotEmpty()) path.removePrefix(prefix) else path
            arr.put(JSONObject().put("_id", id))
        }
        File(context.getExternalFilesDir(null), FILE_NAME).writeText(arr.toString(2).replace("\\/", "/"))
    }
}