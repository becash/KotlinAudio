package com.becash.becashplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client pentru serverul API (server/main.py) care stă în fața MySQL.
 * Înlocuiește vechiul DbSync (JDBC direct): aceleași operații, dar prin HTTPS
 * cu antetul X-Api-Key, iar coada offline se golește într-o singură cerere.
 *
 * Spre deosebire de DbSync, mutațiile ARUNCĂ excepție la eșec — recordStat
 * din PlayerViewModel prinde și pune operația în OfflineQueue, deci nu se
 * mai pierd statistici când serverul e inaccesibil.
 */
class ApiSync {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Doar erorile de rețea (IOException) sunt tranzitorii și merită retry;
    // un răspuns HTTP de eroare (401, 500) se repetă identic, deci aruncăm direct.
    private suspend fun <T> withRetry(maxAttempts: Int = 5, delayMs: Long = 3000L, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                if (++attempt >= maxAttempts) throw e
                Timber.w("ApiSync: connection failed (attempt $attempt/$maxAttempts), retry in ${delayMs}ms")
                delay(delayMs)
            }
        }
    }

    private fun request(settings: AppSettings, path: String): Request.Builder =
        Request.Builder()
            .url(settings.apiUrl.trimEnd('/') + path)
            .header("X-Api-Key", settings.apiKey)

    private suspend fun execute(req: Request): String = withContext(Dispatchers.IO) {
        withRetry {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("API ${resp.code}: ${body.take(200)}")
                body
            }
        }
    }

    // Întoarce null dacă serverul e inaccesibil (după retry-uri), ca apelantul să NU
    // suprascrie playlist-ul îmbogățit cu ID-uri goale. Un map gol înseamnă tabel "played" gol legitim.
    suspend fun sync(settings: AppSettings): Map<String, JSONObject>? = try {
        val body = execute(request(settings, "/played").get().build())
        val songs = JSONObject(body).getJSONObject("songs")
        val result = mutableMapOf<String, JSONObject>()
        for (id in songs.keys()) result[id] = songs.getJSONObject(id)
        Timber.i("ApiSync: ${result.size} cântece descărcate de pe server")
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w("ApiSync: sync eșuat — ${e.message}")
        null
    }

    /** Trimite un lot de operații (formatul OfflineQueue) și întoarce câte a aplicat serverul. */
    suspend fun sendOps(settings: AppSettings, ops: JSONArray): Int {
        if (ops.length() == 0) return 0
        val payload = JSONObject().put("ops", ops).toString()
            .toRequestBody("application/json".toMediaType())
        val body = execute(request(settings, "/ops").post(payload).build())
        return JSONObject(body).optInt("applied", ops.length())
    }

    private suspend fun sendOp(settings: AppSettings, op: JSONObject) {
        sendOps(settings, JSONArray().put(op))
    }

    suspend fun incrementPlays(settings: AppSettings, songId: String) {
        sendOp(settings, OfflineQueue.opIncrementPlays(songId))
        Timber.i("ApiSync: plays++ pentru $songId")
    }

    suspend fun addListen(settings: AppSettings, songId: String, milliseconds: Long, duration: Long) {
        sendOp(settings, OfflineQueue.opAddListen(songId, milliseconds, duration))
        Timber.i("ApiSync: listen += ${milliseconds}ms pentru $songId")
    }

    suspend fun setRateDance(settings: AppSettings, songId: String, rate: Int, dance: Boolean, calm: Boolean) {
        sendOp(settings, OfflineQueue.opSetRateDance(songId, rate, dance, calm))
        Timber.i("ApiSync: rate=$rate dance=$dance calm=$calm pentru $songId")
    }
}
