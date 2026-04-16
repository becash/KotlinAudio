package com.becash.becashplayer

import android.content.Context
import org.json.JSONObject
import java.io.File

class AppSettings(context: Context) {

    private val file = File(context.getExternalFilesDir(null), "settings.json")

    private val data: JSONObject = when {
        file.exists() -> try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            defaults().also { file.writeText(it.toString(2)) }
        }
        else -> defaults().also { file.writeText(it.toString(2)) }
    }

    private fun defaults() = JSONObject().apply {
        put(KEY_SERVER_URL,       DEFAULT_SERVER_URL)
        put(KEY_USERNAME,         DEFAULT_USERNAME)
        put(KEY_PASSWORD,         DEFAULT_PASSWORD)
        put(KEY_REMOTE_FOLDER,    DEFAULT_REMOTE_FOLDER)
        put(KEY_LOCAL_FOLDER,     DEFAULT_LOCAL_FOLDER)
        put(KEY_LAST_TRACK_INDEX, DEFAULT_LAST_TRACK_INDEX)
        put(KEY_LAST_PLAYLIST,    DEFAULT_LAST_PLAYLIST)
        put(KEY_MYSQL_HOST,       DEFAULT_MYSQL_HOST)
        put(KEY_MYSQL_PORT,       DEFAULT_MYSQL_PORT)
        put(KEY_MYSQL_USER,       DEFAULT_MYSQL_USER)
        put(KEY_MYSQL_PASSWORD,   DEFAULT_MYSQL_PASSWORD)
        put(KEY_MYSQL_DB,         DEFAULT_MYSQL_DB)
    }

    private fun save() = file.writeText(data.toString(2))

    var serverUrl: String
        get() = data.optString(KEY_SERVER_URL, DEFAULT_SERVER_URL)
        set(v) { data.put(KEY_SERVER_URL, v); save() }

    var username: String
        get() = data.optString(KEY_USERNAME, DEFAULT_USERNAME)
        set(v) { data.put(KEY_USERNAME, v); save() }

    var password: String
        get() = data.optString(KEY_PASSWORD, DEFAULT_PASSWORD)
        set(v) { data.put(KEY_PASSWORD, v); save() }

    var remoteFolderPath: String
        get() = data.optString(KEY_REMOTE_FOLDER, DEFAULT_REMOTE_FOLDER)
        set(v) { data.put(KEY_REMOTE_FOLDER, v); save() }

    var localFolderName: String
        get() = data.optString(KEY_LOCAL_FOLDER, DEFAULT_LOCAL_FOLDER)
        set(v) { data.put(KEY_LOCAL_FOLDER, v); save() }

    var lastTrackIndex: Int
        get() = data.optInt(KEY_LAST_TRACK_INDEX, DEFAULT_LAST_TRACK_INDEX)
        set(v) { data.put(KEY_LAST_TRACK_INDEX, v); save() }

    var lastPlaylistMode: String
        get() = data.optString(KEY_LAST_PLAYLIST, DEFAULT_LAST_PLAYLIST)
        set(v) { data.put(KEY_LAST_PLAYLIST, v); save() }

    var filterQuery: String
        get() = data.optString(KEY_FILTER_QUERY, "")
        set(v) { data.put(KEY_FILTER_QUERY, v); save() }

    var filterInverted: Boolean
        get() = data.optBoolean(KEY_FILTER_INVERTED, false)
        set(v) { data.put(KEY_FILTER_INVERTED, v); save() }

    var mysqlHost: String
        get() = data.optString(KEY_MYSQL_HOST, DEFAULT_MYSQL_HOST)
        set(v) { data.put(KEY_MYSQL_HOST, v); save() }

    var mysqlPort: Int
        get() = data.optInt(KEY_MYSQL_PORT, DEFAULT_MYSQL_PORT)
        set(v) { data.put(KEY_MYSQL_PORT, v); save() }

    var mysqlUser: String
        get() = data.optString(KEY_MYSQL_USER, DEFAULT_MYSQL_USER)
        set(v) { data.put(KEY_MYSQL_USER, v); save() }

    var mysqlPassword: String
        get() = data.optString(KEY_MYSQL_PASSWORD, DEFAULT_MYSQL_PASSWORD)
        set(v) { data.put(KEY_MYSQL_PASSWORD, v); save() }

    var mysqlDatabase: String
        get() = data.optString(KEY_MYSQL_DB, DEFAULT_MYSQL_DB)
        set(v) { data.put(KEY_MYSQL_DB, v); save() }

    val bariera9: String get() = BARIERA9
    val bariera10: String get() = BARIERA10

    fun isConfigured(): Boolean =
        serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    companion object {
        private const val KEY_SERVER_URL       = "server_url"
        private const val KEY_USERNAME         = "username"
        private const val KEY_PASSWORD         = "password"
        private const val KEY_REMOTE_FOLDER    = "remote_folder"
        private const val KEY_LOCAL_FOLDER     = "local_folder"
        private const val KEY_LAST_TRACK_INDEX = "last_track_index"
        private const val KEY_LAST_PLAYLIST    = "last_playlist_mode"
        private const val KEY_FILTER_QUERY     = "filter_query"
        private const val KEY_FILTER_INVERTED  = "filter_inverted"
        private const val KEY_MYSQL_HOST       = "mysql_host"
        private const val KEY_MYSQL_PORT       = "mysql_port"
        private const val KEY_MYSQL_USER       = "mysql_user"
        private const val KEY_MYSQL_PASSWORD   = "mysql_password"
        private const val KEY_MYSQL_DB         = "mysql_db"
        const val BARIERA9  = ""   // numărul barierei 9
        const val BARIERA10 = ""   // numărul barierei 10

        private const val DEFAULT_SERVER_URL      = ""
        private const val DEFAULT_USERNAME        = ""
        private const val DEFAULT_PASSWORD        = ""
        private const val DEFAULT_REMOTE_FOLDER   = ""
        private const val DEFAULT_LOCAL_FOLDER    = ""
        private const val DEFAULT_LAST_TRACK_INDEX = 0
        private const val DEFAULT_LAST_PLAYLIST   = "SHUFFLE"
        private const val DEFAULT_MYSQL_HOST      = ""
        private const val DEFAULT_MYSQL_PORT      = 3306
        private const val DEFAULT_MYSQL_USER      = ""
        private const val DEFAULT_MYSQL_PASSWORD  = ""
        private const val DEFAULT_MYSQL_DB        = "becash_player"
    }
}
