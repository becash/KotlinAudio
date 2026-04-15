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
                        listen   BIGINT     DEFAULT 0,
                        duration INT        DEFAULT 0,
                        deleted  TINYINT(1) DEFAULT 0,
                        updated  DATETIME   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent())

                stmt.executeQuery("SELECT * FROM played WHERE deleted = 0").use { rs ->
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
}
