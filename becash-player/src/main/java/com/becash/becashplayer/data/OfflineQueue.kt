package com.becash.becashplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OfflineQueue(private val context: Context) {

    companion object {
        private const val FILE_NAME = "offline_queue.json"
        const val TYPE_INCREMENT_PLAYS = "incrementPlays"
        const val TYPE_ADD_LISTEN      = "addListen"
        const val TYPE_SET_RATE_DANCE  = "setRateDance"

        // Formatul operațiilor e contractul cu serverul (POST /ops) — aceleași
        // obiecte se scriu în coadă și se trimit live prin ApiSync.
        fun opIncrementPlays(songId: String) = JSONObject().apply {
            put("type", TYPE_INCREMENT_PLAYS)
            put("songId", songId)
        }

        fun opAddListen(songId: String, milliseconds: Long, duration: Long) = JSONObject().apply {
            put("type", TYPE_ADD_LISTEN)
            put("songId", songId)
            put("milliseconds", milliseconds)
            put("duration", duration)
        }

        fun opSetRateDance(songId: String, rate: Int, dance: Boolean, calm: Boolean) = JSONObject().apply {
            put("type", TYPE_SET_RATE_DANCE)
            put("songId", songId)
            put("rate", rate)
            put("dance", if (dance) 1 else 0)
            put("calm", if (calm) 1 else 0)
        }
    }

    private fun file(): File? = context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

    private fun readArray(): JSONArray {
        val f = file() ?: return JSONArray()
        if (!f.exists()) return JSONArray()
        return try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }
    }

    private fun writeArray(arr: JSONArray) {
        val f = file() ?: return
        runCatching { f.writeText(arr.toString(2)) }
    }

    fun count(): Int = readArray().length()

    private fun enqueue(op: JSONObject) {
        val arr = readArray()
        op.put("ts", System.currentTimeMillis())
        arr.put(op)
        writeArray(arr)
    }

    fun enqueueIncrementPlays(songId: String) = enqueue(opIncrementPlays(songId))

    fun enqueueAddListen(songId: String, milliseconds: Long, duration: Long) =
        enqueue(opAddListen(songId, milliseconds, duration))

    fun enqueueSetRateDance(songId: String, rate: Int, dance: Boolean, calm: Boolean) =
        enqueue(opSetRateDance(songId, rate, dance, calm))

    fun clear() = writeArray(JSONArray())

    /**
     * Trimite toată coada la server într-o singură cerere (POST /ops), aplicată
     * acolo într-o singură tranzacție. La eșec coada rămâne neatinsă pe disc
     * și se aruncă excepția. Returnează numărul de operații aplicate.
     */
    suspend fun flush(apiSync: ApiSync, settings: AppSettings): Int {
        val arr = readArray()
        if (arr.length() == 0) return 0
        val sent = apiSync.sendOps(settings, arr)
        clear()
        return sent
    }
}
