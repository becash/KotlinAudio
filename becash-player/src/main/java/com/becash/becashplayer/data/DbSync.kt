package com.becash.becashplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.SocketException
import java.net.SocketTimeoutException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

class DbSync {

    init {
        Class.forName("com.mysql.jdbc.Driver")
    }

    private suspend fun <T> withRetry(maxAttempts: Int = 5, delayMs: Long = 3000L, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (!e.isNetworkError() || ++attempt >= maxAttempts) throw e
                Timber.w("DbSync: connection failed (attempt $attempt/$maxAttempts), retry in ${delayMs}ms")
                delay(delayMs)
            }
        }
    }

    // O eroare de rețea (timeout, socket abort, "Communications link failure") e tranzitorie și merită retry.
    private fun Throwable.isNetworkError(): Boolean {
        var t: Throwable? = this
        while (t != null) {
            if (t is SocketTimeoutException || t is SocketException) return true
            if (t is SQLException && t.message?.contains("Communications link failure") == true) return true
            t = t.cause
        }
        return false
    }

    private fun buildConnectionUrl(host: String, port: Int, database: String = ""): String {
        val dbPart = if (database.isNotEmpty()) "/$database" else ""
        return "jdbc:mysql://$host:$port$dbPart" +
                "?useSSL=false&connectTimeout=5000&socketTimeout=10000" +
                "&useUnicode=true&characterEncoding=UTF-8"
    }

    // Rulează o operație MySQL (conectare + execuție) cu retry pe erori de rețea.
    // Întreaga operație e protejată: orice eșec întoarce fallback în loc să arunce, ca să nu crape aplicația.
    private suspend fun <T> runOp(
        settings: AppSettings,
        useDatabase: Boolean,
        op: String,
        fallback: T,
        block: (Connection) -> T,
    ): T = withContext(Dispatchers.IO) {
        try {
            withRetry {
                DriverManager.getConnection(
                    buildConnectionUrl(settings.mysqlHost, settings.mysqlPort, if (useDatabase) settings.mysqlDatabase else ""),
                    settings.mysqlUser, settings.mysqlPassword
                ).use { conn -> block(conn) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("DbSync: $op skipped — ${e.message}")
            fallback
        }
    }

    // Întoarce null dacă MySQL e inaccesibil (eroare de rețea după retry-uri), ca apelantul să NU
    // suprascrie playlist-ul îmbogățit cu ID-uri goale. Un map gol înseamnă tabel "played" gol legitim.
    suspend fun sync(settings: AppSettings): Map<String, JSONObject>? =
        runOp<Map<String, JSONObject>?>(settings, useDatabase = false, op = "sync", fallback = null) { c ->
            val database = settings.mysqlDatabase
            c.createStatement().use { stmt ->
                stmt.execute("CREATE DATABASE IF NOT EXISTS `$database` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
                stmt.execute("USE `$database`")
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS played (
                        id       VARCHAR(500) PRIMARY KEY,
                        plays    INT        DEFAULT 0,
                        rate     TINYINT    DEFAULT 0,
                        dance    TINYINT(1) DEFAULT 0,
                        calm     TINYINT(1) DEFAULT 0,
                        listen   BIGINT     DEFAULT 0,
                        duration INT        DEFAULT 0,
                        updated  DATETIME   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent())

                stmt.executeQuery("SELECT * FROM played").use { rs ->
                    val meta = rs.metaData
                    val result = mutableMapOf<String, JSONObject>()
                    while (rs.next()) {
                        val obj = JSONObject()
                        for (col in 1..meta.columnCount) {
                            val name = meta.getColumnName(col)
                            if (name != "id") obj.put(name, rs.getObject(col))
                        }
                        result[rs.getString("id")] = obj
                    }
                    Timber.i("DbSync: ${result.size} cântece descărcate din MySQL")
                    result
                }
            }
        }

    suspend fun incrementPlays(settings: AppSettings, songId: String) =
        runOp(settings, useDatabase = true, op = "incrementPlays", fallback = Unit) { conn ->
            conn.prepareStatement(
                "INSERT INTO played (id, plays) VALUES (?, 1) ON DUPLICATE KEY UPDATE plays = plays + 1"
            ).use { ps ->
                ps.setString(1, songId)
                ps.executeUpdate()
            }
            Timber.i("DbSync: plays++ pentru $songId")
        }

    suspend fun addListen(
        settings: AppSettings,
        songId: String,
        milliseconds: Long,
        duration: Long,
    ) = runOp(settings, useDatabase = true, op = "addListen", fallback = Unit) { conn ->
        conn.prepareStatement(
            "INSERT INTO played (id, listen, duration) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE listen = listen + ?, duration = IF(duration = 0, VALUES(duration), duration)"
        ).use { ps ->
            ps.setString(1, songId)
            ps.setLong(2, milliseconds)
            ps.setLong(3, duration)
            ps.setLong(4, milliseconds)
            ps.executeUpdate()
        }
        Timber.i("DbSync: listen += ${milliseconds}ms pentru $songId")
    }

    suspend fun setRateDance(
        settings: AppSettings,
        songId: String,
        rate: Int,
        dance: Boolean,
        calm: Boolean,
    ) = runOp(settings, useDatabase = true, op = "setRateDance", fallback = Unit) { conn ->
        conn.prepareStatement(
            "INSERT INTO played (id, rate, dance, calm) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE rate = ?, dance = ?, calm = ?"
        ).use { ps ->
            ps.setString(1, songId)
            ps.setInt(2, rate)
            ps.setInt(3, if (dance) 1 else 0)
            ps.setInt(4, if (calm) 1 else 0)
            ps.setInt(5, rate)
            ps.setInt(6, if (dance) 1 else 0)
            ps.setInt(7, if (calm) 1 else 0)
            ps.executeUpdate()
        }
        Timber.i("DbSync: rate=$rate dance=$dance calm=$calm pentru $songId")
    }
}
