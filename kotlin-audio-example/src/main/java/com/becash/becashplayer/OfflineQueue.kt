package com.becash.becashplayer

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
    }

    private fun file(): File? = context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

    fun readArray(): JSONArray {
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

    fun enqueueIncrementPlays(songId: String) = enqueue(JSONObject().apply {
        put("type", TYPE_INCREMENT_PLAYS)
        put("songId", songId)
    })

    fun enqueueAddListen(songId: String, milliseconds: Long, duration: Long) = enqueue(JSONObject().apply {
        put("type", TYPE_ADD_LISTEN)
        put("songId", songId)
        put("milliseconds", milliseconds)
        put("duration", duration)
    })

    fun enqueueSetRateDance(songId: String, rate: Int, dance: Boolean, calm: Boolean) = enqueue(JSONObject().apply {
        put("type", TYPE_SET_RATE_DANCE)
        put("songId", songId)
        put("rate", rate)
        put("dance", if (dance) 1 else 0)
        put("calm", if (calm) 1 else 0)
    })

    fun clear() = writeArray(JSONArray())

    /**
     * Trimite toate operațiile din coadă la MySQL, una câte una.
     * Dacă o operație eșuează, restul rămân în fișier și se aruncă excepția.
     * Returnează numărul de operații trimise cu succes.
     */
    suspend fun flushToDb(dbSync: DbSync, settings: AppSettings): Int {
        val arr = readArray()
        val total = arr.length()
        if (total == 0) return 0
        var sent = 0
        for (i in 0 until total) {
            val op = arr.optJSONObject(i) ?: continue
            try {
                when (op.optString("type")) {
                    TYPE_INCREMENT_PLAYS -> dbSync.incrementPlays(
                        host = settings.mysqlHost, port = settings.mysqlPort,
                        user = settings.mysqlUser, password = settings.mysqlPassword,
                        database = settings.mysqlDatabase,
                        songId = op.optString("songId"),
                    )
                    TYPE_ADD_LISTEN -> dbSync.addListen(
                        host = settings.mysqlHost, port = settings.mysqlPort,
                        user = settings.mysqlUser, password = settings.mysqlPassword,
                        database = settings.mysqlDatabase,
                        songId = op.optString("songId"),
                        milliseconds = op.optLong("milliseconds"),
                        duration = op.optLong("duration"),
                    )
                    TYPE_SET_RATE_DANCE -> dbSync.setRateDance(
                        host = settings.mysqlHost, port = settings.mysqlPort,
                        user = settings.mysqlUser, password = settings.mysqlPassword,
                        database = settings.mysqlDatabase,
                        songId = op.optString("songId"),
                        rate = op.optInt("rate"),
                        dance = op.optInt("dance") == 1,
                        calm = op.optInt("calm") == 1,
                    )
                }
                sent++
            } catch (e: Exception) {
                val remaining = JSONArray()
                for (j in i until total) arr.optJSONObject(j)?.let { remaining.put(it) }
                writeArray(remaining)
                throw e
            }
        }
        clear()
        return sent
    }
}
