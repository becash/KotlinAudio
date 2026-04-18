package com.becash.becashplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.sql.DriverManager

object DbSync {

    suspend fun sync(
        host: String,
        port: Int,
        user: String,
        password: String,
        database: String,
    ): Map<String, JSONObject> = withContext(Dispatchers.IO) {
        Class.forName("com.mysql.jdbc.Driver")
        val baseUrl = "jdbc:mysql://$host:$port" +
                "?useSSL=false&connectTimeout=5000&socketTimeout=10000" +
                "&useUnicode=true&characterEncoding=UTF-8"

        // Conectare fără bază de date pentru a o crea dacă nu există
        DriverManager.getConnection(baseUrl, user, password).use { conn ->
            conn.createStatement().use { stmt ->
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
    }

    suspend fun incrementPlays(
        host: String,
        port: Int,
        user: String,
        password: String,
        database: String,
        songId: String,
    ) = withContext(Dispatchers.IO) {
        Class.forName("com.mysql.jdbc.Driver")
        val url = "jdbc:mysql://$host:$port/$database" +
                "?useSSL=false&connectTimeout=5000&socketTimeout=10000" +
                "&useUnicode=true&characterEncoding=UTF-8"
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO played (id, plays) VALUES (?, 1) ON DUPLICATE KEY UPDATE plays = plays + 1"
            ).use { ps ->
                ps.setString(1, songId)
                ps.executeUpdate()
            }
        }
        Timber.i("DbSync: plays++ pentru $songId")
    }

    suspend fun addListen(
        host: String,
        port: Int,
        user: String,
        password: String,
        database: String,
        songId: String,
        milliseconds: Long,
        duration: Long,
    ) = withContext(Dispatchers.IO) {
        Class.forName("com.mysql.jdbc.Driver")
        val url = "jdbc:mysql://$host:$port/$database" +
                "?useSSL=false&connectTimeout=5000&socketTimeout=10000" +
                "&useUnicode=true&characterEncoding=UTF-8"
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO played (id, listen, duration) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE listen = listen + ?, duration = IF(duration = 0, VALUES(duration), duration)"
            ).use { ps ->
                ps.setString(1, songId)
                ps.setLong(2, milliseconds)
                ps.setLong(3, duration)
                ps.setLong(4, milliseconds)
                ps.executeUpdate()
            }
        }
        Timber.i("DbSync: listen += ${milliseconds}ms pentru $songId")
    }

    suspend fun setRateDance(
        host: String,
        port: Int,
        user: String,
        password: String,
        database: String,
        songId: String,
        rate: Int,
        dance: Boolean,
        calm: Boolean,
    ) = withContext(Dispatchers.IO) {
        Class.forName("com.mysql.jdbc.Driver")
        val url = "jdbc:mysql://$host:$port/$database" +
                "?useSSL=false&connectTimeout=5000&socketTimeout=10000" +
                "&useUnicode=true&characterEncoding=UTF-8"
        DriverManager.getConnection(url, user, password).use { conn ->
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
        }
        Timber.i("DbSync: rate=$rate dance=$dance calm=$calm pentru $songId")
    }
}
