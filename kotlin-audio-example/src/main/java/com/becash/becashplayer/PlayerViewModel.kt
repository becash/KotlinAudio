package com.becash.becashplayer

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.doublesymmetry.kotlinaudio.models.AudioItem
import com.doublesymmetry.kotlinaudio.models.AudioPlayerState
import com.doublesymmetry.kotlinaudio.models.DefaultAudioItem
import com.doublesymmetry.kotlinaudio.models.MediaSessionCallback
import com.doublesymmetry.kotlinaudio.models.MediaType
import com.doublesymmetry.kotlinaudio.models.NotificationButton
import com.doublesymmetry.kotlinaudio.models.NotificationConfig
import com.doublesymmetry.kotlinaudio.models.PlayerConfig
import com.doublesymmetry.kotlinaudio.models.RepeatMode
import com.doublesymmetry.kotlinaudio.players.QueuedAudioPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import io.sentry.Sentry
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class PlayerViewModel(private val app: Application) : AndroidViewModel(app) {

    val appSettings = AppSettings(app)
    val playlistStore = PlaylistStore()
    val webDavSync = WebDavSync()
    val dbSync = DbSync()

    val player: QueuedAudioPlayer

    var currentScreen by mutableStateOf<Screen>(Screen.Main)
    var syncState by mutableStateOf<SyncState>(SyncState.Idle)
    var playlistMode by mutableStateOf(PlaylistMode.SHUFFLE)
    var playlistItems by mutableStateOf<List<AudioItem>>(emptyList())
    var currentTrackIndex by mutableStateOf(0)
    var filterQuery by mutableStateOf("")
    var filterInverted by mutableStateOf(false)
    var songInfoMap by mutableStateOf<Map<String, JSONObject>>(emptyMap())
    var isDbSyncBusy by mutableStateOf(false)
    var ratingFilter by mutableStateOf(RatingFilter.ALL)

    private var listenSongId: String? = null
    private var listenDuration = 0L
    private var listenStartTime = 0L
    private var listenAccumulatedMs = 0L

    val audioBaseDir: String
        get() = File(Environment.getExternalStorageDirectory(), appSettings.localFolderName).absolutePath

    val filteredIndices: List<Int>
        get() {
            val indexed = playlistItems.mapIndexed { i, item -> i to item }
            val ordered = if (playlistMode == PlaylistMode.SHUFFLE) indexed
                          else indexed.sortedWith(compareBy({ it.second.artist ?: "" }, { it.second.title ?: "" }))
            val afterText = if (filterQuery.isBlank()) ordered
                            else ordered.filter { (_, item) -> matchesTextFilter(item, filterQuery, filterInverted) }
            val afterRating = if (ratingFilter == RatingFilter.ALL) afterText
                              else afterText.filter { (_, item) ->
                                  val relPath = item.audioUrl.removePrefix("file://$audioBaseDir")
                                  passesRatingFilter(relPath, songInfoMap, ratingFilter)
                              }
            return afterRating.map { it.first }
        }

    init {
        playlistMode = try { PlaylistMode.valueOf(appSettings.lastPlaylistMode) } catch (_: Exception) { PlaylistMode.SHUFFLE }
        filterQuery = appSettings.filterQuery
        filterInverted = appSettings.filterInverted
        ratingFilter = try { RatingFilter.valueOf(appSettings.lastRatingFilter) } catch (_: Exception) { RatingFilter.ALL }
        songInfoMap = playlistStore.loadAsMap(app)

        player = QueuedAudioPlayer(
            app,
            playerConfig = PlayerConfig(
                interceptPlayerActionsTriggeredExternally = true,
                handleAudioBecomingNoisy = true,
                handleAudioFocus = true
            )
        )
        player.playerOptions.repeatMode = RepeatMode.ALL
        setupNotification()

        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(Environment.getExternalStorageDirectory(), appSettings.localFolderName)
            if (!dir.exists()) dir.mkdirs()
        }

        player.event.onPlayerActionTriggeredExternally
            .onEach { handleExternalAction(it) }
            .launchIn(viewModelScope)

        player.event.playbackError
            .onEach { error ->
                Timber.e("Eroare redare (${error.code}): ${error.message} — se trece la piesa următoare")
                Sentry.captureMessage("Playback error cod=${error.code}: ${error.message} | piesa=${player.currentItem?.audioUrl}")
                player.next()
            }
            .launchIn(viewModelScope)

        player.event.stateChange
            .onEach { state ->
                when (state) {
                    AudioPlayerState.PLAYING -> {
                        if (listenStartTime == 0L) listenStartTime = System.currentTimeMillis()
                    }
                    AudioPlayerState.PAUSED -> {
                        if (listenStartTime != 0L) {
                            listenAccumulatedMs += System.currentTimeMillis() - listenStartTime
                            listenStartTime = 0L
                        }
                    }
                    else -> {}
                }
            }
            .launchIn(viewModelScope)

        player.event.audioItemTransition
            .onEach {
                flushListen()
                val base = audioBaseDir
                val currentSongId = player.currentItem?.audioUrl
                    ?.removePrefix("file://$base")
                    ?.let { url -> if (url.startsWith("/")) url else "/$url" }
                listenSongId = currentSongId

                val newIndex = player.currentIndex
                val fi = filteredIndices
                if ((filterQuery.isNotBlank() || ratingFilter != RatingFilter.ALL) && fi.isNotEmpty() && newIndex !in fi) {
                    val next = fi.firstOrNull { it > newIndex } ?: fi.first()
                    player.jumpToItem(next)
                    player.play()
                    return@onEach
                }

                currentTrackIndex = newIndex
                playlistItems = player.items
                listenDuration = player.currentItem?.duration ?: 0L

                if (currentSongId != null && appSettings.mysqlHost.isNotBlank()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            dbSync.incrementPlays(
                                host = appSettings.mysqlHost,
                                port = appSettings.mysqlPort,
                                user = appSettings.mysqlUser,
                                password = appSettings.mysqlPassword,
                                database = appSettings.mysqlDatabase,
                                songId = currentSongId,
                            )
                        } catch (e: Exception) {
                            Timber.e(e, "incrementPlays error")
                            Sentry.captureException(e)
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    fun nextFiltered() {
        val indices = filteredIndices
        if (indices.isEmpty()) { player.next(); return }
        val pos = indices.indexOf(currentTrackIndex)
        val next = if (pos < 0 || pos >= indices.lastIndex) indices.first() else indices[pos + 1]
        player.jumpToItem(next)
        player.play()
    }

    fun previousFiltered() {
        val indices = filteredIndices
        if (indices.isEmpty()) { player.previous(); return }
        val pos = indices.indexOf(currentTrackIndex)
        val prev = if (pos <= 0) indices.last() else indices[pos - 1]
        player.jumpToItem(prev)
        player.play()
    }

    fun deleteCurrentTrack() {
        val audioUrl = player.currentItem?.audioUrl ?: return
        val localPath = audioUrl.removePrefix("file://")
        val localFile = File(localPath)
        val audioRootDir = File(Environment.getExternalStorageDirectory(), appSettings.localFolderName)
        val relativePath = localFile.toRelativeString(audioRootDir)
        val remoteFilePath = "${appSettings.remoteFolderPath.trimEnd('/')}/$relativePath"

        player.next()

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                localFile.delete()
                val updated = playlistStore.load(app, audioRootDir.absolutePath).filter { it != localPath }
                playlistStore.save(app, updated, audioRootDir.absolutePath)
            }
            reloadPlayer()
            webDavSync.deleteFile(
                serverUrl = appSettings.serverUrl,
                username = appSettings.username,
                password = appSettings.password,
                remotePath = remoteFilePath
            )
            showToast("\"${localFile.nameWithoutExtension}\" șters.", Toast.LENGTH_SHORT)
        }
    }

    fun applyPlaylistMode(mode: PlaylistMode) {
        appSettings.lastPlaylistMode = mode.name
        playlistMode = mode
        when (mode) {
            PlaylistMode.SHUFFLE  -> { player.playerOptions.repeatMode = RepeatMode.ALL; reloadPlayer() }
            PlaylistMode.NORMAL   -> { player.playerOptions.repeatMode = RepeatMode.ALL; reloadPlayer() }
            PlaylistMode.PLAY_ONE -> { player.playerOptions.repeatMode = RepeatMode.ONE }
        }
    }

    fun loadAndPlay() {
        if (playlistItems.isNotEmpty()) return
        viewModelScope.launch {
            val (items, freshInfoMap) = withContext(Dispatchers.IO) {
                buildLocalAudioItems() to playlistStore.loadAsMap(app)
            }
            if (items.isEmpty()) {
                val audioDir = File(Environment.getExternalStorageDirectory(), appSettings.localFolderName)
                Sentry.captureMessage(
                    "loadAndPlay: playlist gol | dir=${audioDir.absolutePath} | exists=${audioDir.exists()} | canRead=${audioDir.canRead()}"
                )
                showToast("Folderul '${appSettings.localFolderName}' este gol. Sincronizează din Nextcloud.", Toast.LENGTH_LONG)
                return@launch
            }
            if (freshInfoMap.isNotEmpty()) songInfoMap = freshInfoMap
            player.add(items)
            val savedUrl = appSettings.lastSongUrl
            val savedIndex = if (savedUrl.isNotBlank())
                items.indexOfFirst { it.audioUrl == savedUrl }.takeIf { it >= 0 }
                    ?: appSettings.lastTrackIndex.coerceIn(0, items.lastIndex)
            else
                appSettings.lastTrackIndex.coerceIn(0, items.lastIndex)
            if (savedIndex > 0) player.jumpToItem(savedIndex)
            player.play()
            playlistItems = player.items
            currentTrackIndex = player.currentIndex
        }
    }

    fun reloadPlayer() {
        val currentUrl = player.currentItem?.audioUrl
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { buildLocalAudioItems() }
            if (items.isEmpty()) return@launch
            player.stop()
            player.clear()
            player.add(items)
            val resumeIndex = if (currentUrl != null)
                items.indexOfFirst { it.audioUrl == currentUrl }.coerceAtLeast(0)
            else 0
            if (resumeIndex > 0) player.jumpToItem(resumeIndex)
            player.play()
            playlistItems = player.items
            currentTrackIndex = player.currentIndex
        }
    }

    fun buildLocalAudioItems(): List<DefaultAudioItem> {
        val audioDir = File(Environment.getExternalStorageDirectory(), appSettings.localFolderName)
        val paths = playlistStore.load(app, audioDir.absolutePath).ifEmpty {
            val scanned = scanAudioFiles(audioDir)
            if (scanned.isEmpty()) {
                Sentry.captureMessage(
                    "buildLocalAudioItems: scanAudioFiles gol | dir=${audioDir.absolutePath} | exists=${audioDir.exists()} | canRead=${audioDir.canRead()} | isManageStorageGranted=${android.os.Environment.isExternalStorageManager()}"
                )
            }
            if (scanned.isNotEmpty()) playlistStore.save(app, scanned, audioDir.absolutePath)
            scanned
        }
        val files = if (playlistMode == PlaylistMode.SHUFFLE) {
            val weightsMap = playlistStore.loadWeightsMap(app)
            val fileList = paths.map { File(it) }
            if (weightsMap.isEmpty()) fileList.shuffled()
            else {
                val base = audioDir.absolutePath
                val filesWithWeights = fileList.map { f ->
                    val id = f.absolutePath.removePrefix(base)
                    f to (weightsMap[id] ?: 1.0)
                }
                weightedShuffle(filesWithWeights)
            }
        } else {
            paths.map { File(it) }.sortedWith(compareBy({ it.parent }, { it.name }))
        }
        return files.map { file ->
            val folderName = file.parentFile
                ?.takeIf { it != audioDir }
                ?.toRelativeString(audioDir)
                ?: ""
            DefaultAudioItem(
                audioUrl = "file://${file.absolutePath}",
                type = MediaType.DEFAULT,
                title = file.nameWithoutExtension,
                artist = folderName,
            )
        }
    }

    fun weightedShuffle(items: List<Pair<File, Double>>): List<File> {
        val result = mutableListOf<File>()
        val remaining = items.toMutableList()
        while (remaining.isNotEmpty()) {
            val totalWeight = remaining.sumOf { it.second }
            var r = Random.nextDouble() * totalWeight
            var selectedIdx = remaining.lastIndex
            for (i in remaining.indices) {
                r -= remaining[i].second
                if (r <= 0.0) { selectedIdx = i; break }
            }
            result.add(remaining[selectedIdx].first)
            remaining.removeAt(selectedIdx)
        }
        return result
    }

    fun scanAudioFiles(audioDir: File): List<String> {
        if (!audioDir.exists() || !audioDir.isDirectory) return emptyList()
        return audioDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS && it.length() > 4096 }
            .sortedWith(compareBy({ it.parent }, { it.name }))
            .map { it.absolutePath }
            .toList()
    }

    fun flushListen() {
        if (listenStartTime != 0L) {
            listenAccumulatedMs += System.currentTimeMillis() - listenStartTime
            listenStartTime = 0L
        }
        val songId = listenSongId ?: return
        val ms = listenAccumulatedMs
        val dur = listenDuration
        listenAccumulatedMs = 0L
        listenDuration = 0L
        listenSongId = null
        if (ms <= 0 || appSettings.mysqlHost.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbSync.addListen(
                    host = appSettings.mysqlHost,
                    port = appSettings.mysqlPort,
                    user = appSettings.mysqlUser,
                    password = appSettings.mysqlPassword,
                    database = appSettings.mysqlDatabase,
                    songId = songId,
                    milliseconds = ms,
                    duration = dur,
                )
            } catch (e: Exception) {
                Timber.e(e, "addListen error")
                Sentry.captureException(e)
            }
        }
    }

    fun updateRateDance(songId: String, relPath: String, rate: Int, dance: Boolean, calm: Boolean) {
        val updated = (songInfoMap[relPath]?.let { JSONObject(it.toString()) } ?: JSONObject()).apply {
            put("rate", rate)
            put("dance", if (dance) 1 else 0)
            put("calm", if (calm) 1 else 0)
        }
        songInfoMap = songInfoMap + (relPath to updated)
        if (appSettings.mysqlHost.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    dbSync.setRateDance(
                        host = appSettings.mysqlHost,
                        port = appSettings.mysqlPort,
                        user = appSettings.mysqlUser,
                        password = appSettings.mysqlPassword,
                        database = appSettings.mysqlDatabase,
                        songId = songId,
                        rate = rate,
                        dance = dance,
                        calm = calm,
                    )
                } catch (e: Exception) {
                    Timber.e(e, "setRateDance error")
                    Sentry.captureException(e)
                }
            }
        }
    }

    fun startSync() {
        if (!appSettings.isWebDavConfigured()) {
            showToast("Configurează mai întâi setările Nextcloud.", Toast.LENGTH_LONG)
            currentScreen = Screen.Settings
            return
        }
        viewModelScope.launch {
            val localDir = File(Environment.getExternalStorageDirectory(), appSettings.localFolderName)
            webDavSync.sync(
                settings = appSettings,
                localDir = localDir,
                onProgress = { state ->
                    syncState = state
                    if (state is SyncState.Done) {
                        withContext(Dispatchers.IO) {
                            val paths = scanAudioFiles(localDir)
                            val basePath = localDir.absolutePath
                            val relIds = paths.map { "/${it.removePrefix("$basePath/")}" }
                            playlistStore.saveEnriched(app, relIds, songInfoMap)
                        }
                        reloadPlayer()
                        showToast(
                            "Sincronizare completă: ${state.downloaded} descărcate, ${state.skipped} existau deja.",
                            Toast.LENGTH_LONG
                        )
                        startDbSync()
                    }
                    if (state is SyncState.Error) {
                        showToast(state.message, Toast.LENGTH_LONG)
                    }
                }
            )
        }
    }

    fun startDbSync() {
        if (appSettings.mysqlHost.isBlank()) {
            showToast("Configurează conexiunea MySQL în setări.", Toast.LENGTH_LONG)
            currentScreen = Screen.Settings
            return
        }
        isDbSyncBusy = true
        viewModelScope.launch {
            try {
                val result = dbSync.sync(
                    host = appSettings.mysqlHost,
                    port = appSettings.mysqlPort,
                    user = appSettings.mysqlUser,
                    password = appSettings.mysqlPassword,
                    database = appSettings.mysqlDatabase,
                )
                withContext(Dispatchers.IO) {
                    val playlistIds = playlistStore.load(app)
                    playlistStore.saveEnriched(app, playlistIds, result)
                }
                songInfoMap = playlistStore.loadAsMap(app)
                showToast("MySQL: ${result.size} cântece sincronizate.", Toast.LENGTH_SHORT)
            } catch (e: Exception) {
                Timber.e(e, "DbSync error")
                Sentry.captureException(e)
                showToast("Eroare MySQL: ${e.message}", Toast.LENGTH_LONG)
            } finally {
                isDbSyncBusy = false
            }
        }
    }

    fun saveState() {
        appSettings.lastTrackIndex = player.currentIndex
        appSettings.lastPlaylistMode = playlistMode.name
        appSettings.lastRatingFilter = ratingFilter.name
        player.currentItem?.audioUrl?.let { appSettings.lastSongUrl = it }
    }

    private fun handleExternalAction(action: MediaSessionCallback) {
        when (action) {
            MediaSessionCallback.PLAY -> player.play()
            MediaSessionCallback.PAUSE -> player.pause()
            MediaSessionCallback.NEXT -> player.next()
            MediaSessionCallback.PREVIOUS -> player.previous()
            MediaSessionCallback.STOP -> player.stop()
            is MediaSessionCallback.SEEK -> player.seek(action.positionMs, TimeUnit.MILLISECONDS)
            else -> Timber.d("Event not handled")
        }
    }

    private fun setupNotification() {
        player.notificationManager.createNotification(
            NotificationConfig(
                listOf(
                    NotificationButton.PLAY_PAUSE(),
                    NotificationButton.NEXT(isCompact = true),
                    NotificationButton.PREVIOUS(isCompact = true),
                    NotificationButton.SEEK_TO
                ),
                accentColor = null, smallIcon = null, pendingIntent = null
            )
        )
    }

    fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(app, message, duration).show()
        }
    }

    override fun onCleared() {
        player.destroy()
        super.onCleared()
    }

}

// -------------------------------------------------------------------------
// Funcții utilitare pentru filtrare — refolosite în ViewModel și Composables
// -------------------------------------------------------------------------
fun matchesTextFilter(item: AudioItem, query: String, inverted: Boolean): Boolean {
    val q = query.trim().lowercase()
    val matches = (item.title ?: "").lowercase().contains(q) ||
                  (item.artist ?: "").lowercase().contains(q)
    return if (inverted) !matches else matches
}

fun passesRatingFilter(relPath: String, songInfoMap: Map<String, JSONObject>, filter: RatingFilter): Boolean {
    val info = songInfoMap[relPath]
    val rate = info?.optInt("rate", 0) ?: 0
    val dance = info?.opt("dance").let { it == true || it == 1 || it?.toString() == "1" }
    val calm  = info?.opt("calm").let  { it == true || it == 1 || it?.toString() == "1" }
    return when (filter) {
        RatingFilter.WITH_RATING -> rate > 0
        RatingFilter.NO_RATING   -> rate == 0
        RatingFilter.TOP         -> rate >= 4
        RatingFilter.BEST        -> rate == 5
        RatingFilter.DANCE       -> dance
        RatingFilter.CALM        -> calm
        RatingFilter.ALL         -> true
    }
}
