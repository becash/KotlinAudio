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
import com.doublesymmetry.kotlinaudio.models.AudioItemTransitionReason
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
import io.sentry.SentryLevel
import timber.log.Timber
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class PlayerViewModel(private val app: Application) : AndroidViewModel(app) {

    val appSettings = AppSettings(app)
    val playlistStore = PlaylistStore()
    val webDavSync = WebDavSync()
    val dbSync = DbSync()
    val offlineQueue = OfflineQueue(app)

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
    var isOfflineMode by mutableStateOf(false)
    var offlineQueueCount by mutableStateOf(0)
    var manualNextIndex by mutableStateOf<Int?>(null)

    private var listenSongId: String? = null
    private var listenDuration = 0L
    private var listenStartTime = 0L
    private var listenAccumulatedMs = 0L

    val audioBaseDir: String
        get() = File(Environment.getExternalStorageDirectory(), appSettings.localFolderName).absolutePath

    val ratingFilteredIndices: Set<Int>?
        get() {
            if (ratingFilter == RatingFilter.ALL) return null
            val base = audioBaseDir
            return playlistItems.mapIndexedNotNull { idx, item ->
                val decoded = try { URLDecoder.decode(item.audioUrl, "UTF-8") } catch (_: Exception) { item.audioUrl }
                val relPath = decoded.removePrefix("file://$base")
                if (passesRatingFilter(relPath, songInfoMap, ratingFilter)) idx else null
            }.toSet()
        }

    val filteredIndices: List<Int>
        get() {
            val indexed = playlistItems.mapIndexed { i, item -> i to item }
            val ordered = if (playlistMode == PlaylistMode.SHUFFLE) indexed
                          else indexed.sortedWith(compareBy({ it.second.artist ?: "" }, { it.second.title ?: "" }))
            val afterText = if (filterQuery.isBlank()) ordered
                            else ordered.filter { (_, item) -> matchesTextFilter(item, filterQuery, filterInverted) }
            val allowed = ratingFilteredIndices
            val afterRating = if (allowed == null) afterText
                              else afterText.filter { (i, _) -> i in allowed }
            return afterRating.map { it.first }
        }

    init {
        playlistMode = try { PlaylistMode.valueOf(appSettings.lastPlaylistMode) } catch (_: Exception) { PlaylistMode.SHUFFLE }
        filterQuery = appSettings.filterQuery
        filterInverted = appSettings.filterInverted
        ratingFilter = try { RatingFilter.valueOf(appSettings.lastRatingFilter) } catch (_: Exception) { RatingFilter.ALL }
        isOfflineMode = appSettings.isOfflineMode
        offlineQueueCount = offlineQueue.count()
        songInfoMap = playlistStore.loadAsMap(app)

        player = QueuedAudioPlayer(
            app,
            playerConfig = PlayerConfig(
                interceptPlayerActionsTriggeredExternally = true,
                handleAudioBecomingNoisy = true,
                handleAudioFocus = true
            )
        )
        player.playerOptions.repeatMode = RepeatMode.ONE
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
            .onEach { reason ->
                val justPlayedSongId = listenSongId
                flushListen()
                if (justPlayedSongId != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        playlistStore.zeroWeight(app, justPlayedSongId)
                    }
                }

                // Cântecul s-a terminat — ExoPlayer a repetat prin RepeatMode.ONE.
                // Noi decidem ce urmează, fără ca vreun cântec greșit să cânte.
                if (reason is AudioItemTransitionReason.REPEAT) {
                    when (playlistMode) {
                        PlaylistMode.PLAY_ONE -> {
                            // intenționat: repetă același cântec, actualizăm doar listen tracking
                            val base = audioBaseDir
                            listenSongId = player.currentItem?.audioUrl
                                ?.let { url -> try { URLDecoder.decode(url, "UTF-8") } catch (_: Exception) { url } }
                                ?.removePrefix("file://$base")
                                ?.let { if (it.startsWith("/")) it else "/$it" }
                        }
                        PlaylistMode.MANUAL -> {
                            val nextIdx = manualNextIndex
                            if (nextIdx != null) {
                                manualNextIndex = null
                                player.pause()
                                player.jumpToItem(nextIdx)
                                player.play()
                            } else {
                                player.pause()
                            }
                        }
                        PlaylistMode.SHUFFLE -> {
                            // Queue-ul intern e deja amestecat de buildLocalAudioItems.
                            // Avansăm în ordinea lui, sărind cântecele care nu trec filtrul.
                            val size = player.items.size.coerceAtLeast(1)
                            val currentIdx = player.currentIndex
                            val allowed = filteredIndices.toSet()
                            val next = if (allowed.isEmpty()) {
                                (currentIdx + 1) % size
                            } else {
                                var found = -1
                                for (i in 1..size) {
                                    val candidate = (currentIdx + i) % size
                                    if (candidate in allowed) { found = candidate; break }
                                }
                                if (found >= 0) found else allowed.first()
                            }
                            player.pause()
                            player.jumpToItem(next)
                            player.play()
                        }
                        PlaylistMode.NORMAL -> {
                            // fi e sortat după artist/titlu — urmărim exact acea ordine.
                            val fi = filteredIndices
                            val pos = fi.indexOf(player.currentIndex)
                            val next = when {
                                fi.isEmpty() -> (player.currentIndex + 1) % player.items.size.coerceAtLeast(1)
                                pos >= 0     -> fi[(pos + 1) % fi.size]
                                else         -> fi.first()
                            }
                            player.pause()
                            player.jumpToItem(next)
                            player.play()
                        }
                    }
                    return@onEach
                }

                // Tranziție programatică (jumpToItem din next/previous/delete/loadAndPlay etc.)
                // Actualizăm starea UI și înregistrăm play-ul pentru cântecul nou.
                val base = audioBaseDir
                val currentSongId = player.currentItem?.audioUrl
                    ?.let { url -> try { URLDecoder.decode(url, "UTF-8") } catch (_: Exception) { url } }
                    ?.removePrefix("file://$base")
                    ?.let { url -> if (url.startsWith("/")) url else "/$url" }
                listenSongId = currentSongId

                currentTrackIndex = player.currentIndex
                playlistItems = player.items
                listenDuration = player.currentItem?.duration ?: 0L

                if (currentSongId != null && appSettings.mysqlHost.isNotBlank()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            if (isOfflineMode) {
                                offlineQueue.enqueueIncrementPlays(currentSongId)
                                offlineQueueCount = offlineQueue.count()
                            } else {
                                dbSync.incrementPlays(
                                    host = appSettings.mysqlHost,
                                    port = appSettings.mysqlPort,
                                    user = appSettings.mysqlUser,
                                    password = appSettings.mysqlPassword,
                                    database = appSettings.mysqlDatabase,
                                    songId = currentSongId,
                                )
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "incrementPlays error")
                            Sentry.captureException(e)
                            offlineQueue.enqueueIncrementPlays(currentSongId)
                            offlineQueueCount = offlineQueue.count()
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    fun nextFiltered() {
        if (playlistMode == PlaylistMode.MANUAL) {
            val nextIdx = manualNextIndex
            if (nextIdx != null) {
                manualNextIndex = null
                player.jumpToItem(nextIdx)
                player.play()
            }
            return
        }
        val indices = filteredIndices
        if (indices.isEmpty()) { player.next(); return }
        val pos = indices.indexOf(currentTrackIndex)
        val next = if (pos < 0 || pos >= indices.lastIndex) indices.first() else indices[pos + 1]
        player.jumpToItem(next)
        player.play()
    }

    fun previousFiltered() {
        if (playlistMode == PlaylistMode.MANUAL) {
            player.seek(0L, TimeUnit.MILLISECONDS)
            return
        }
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
        val deletedIndex = player.currentIndex

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                localFile.delete()
                val deletedId = "/$relativePath"
                val remainingMap = playlistStore.loadAsMap(app) - deletedId
                playlistStore.saveEnriched(app, remainingMap.keys.toList(), remainingMap)
            }
            player.remove(deletedIndex)
            playlistItems = player.items
            if (playlistItems.isNotEmpty()) {
                val nextIndex = deletedIndex.coerceIn(0, playlistItems.lastIndex)
                player.jumpToItem(nextIndex)
                player.play()
            }
            currentTrackIndex = player.currentIndex
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
        manualNextIndex = null
        when (mode) {
            PlaylistMode.SHUFFLE,
            PlaylistMode.NORMAL,
            PlaylistMode.PLAY_ONE,
            PlaylistMode.MANUAL   -> player.playerOptions.repeatMode = RepeatMode.ONE
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
        val savedPosition = player.position
        val wasPlaying = player.playerState == AudioPlayerState.PLAYING
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                if (playlistMode == PlaylistMode.SHUFFLE && currentUrl != null) {
                    val base = audioBaseDir
                    val decoded = try { URLDecoder.decode(currentUrl, "UTF-8") } catch (_: Exception) { currentUrl }
                    val songId = decoded.removePrefix("file://$base")
                        .let { if (it.startsWith("/")) it else "/$it" }
                    playlistStore.zeroWeight(app, songId)
                }
                buildLocalAudioItems()
            }
            if (items.isEmpty()) return@launch
            player.stop()
            player.clear()
            player.add(items)
            val resumeIndex = if (currentUrl != null)
                items.indexOfFirst { it.audioUrl == currentUrl }.coerceAtLeast(0)
            else 0
            if (resumeIndex > 0) player.jumpToItem(resumeIndex)
            if (savedPosition > 0) player.seek(savedPosition, TimeUnit.MILLISECONDS)
            if (wasPlaying) player.play()
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
            } else {
                val playlistFile = app.getExternalFilesDir(null)?.let { File(it, "playlist.json") }
                val fileState = when {
                    playlistFile == null -> "stocare externă indisponibilă"
                    !playlistFile.exists() -> "lipsă"
                    playlistFile.length() == 0L -> "gol (0B)"
                    else -> "corupt/invalid (${playlistFile.length()}B)"
                }
                Sentry.captureMessage(
                    "buildLocalAudioItems: load() gol → scanare directă (${scanned.size} fișiere) | playlist.json $fileState",
                    SentryLevel.WARNING
                )
            }
            if (scanned.isNotEmpty()) {
                val relIds = scanned.map { "/${it.removePrefix("${audioDir.absolutePath}/")}" }
                playlistStore.saveEnriched(app, relIds, songInfoMap)
            }
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
                    f to (if (weightsMap.containsKey(id)) weightsMap[id]!! else 1.0)
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
                audioUrl = Uri.fromFile(file).toString(),
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
                if (isOfflineMode) {
                    offlineQueue.enqueueAddListen(songId, ms, dur)
                    offlineQueueCount = offlineQueue.count()
                } else {
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
                }
            } catch (e: Exception) {
                Timber.e(e, "addListen error")
                Sentry.captureException(e)
                offlineQueue.enqueueAddListen(songId, ms, dur)
                offlineQueueCount = offlineQueue.count()
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
                    if (isOfflineMode) {
                        offlineQueue.enqueueSetRateDance(songId, rate, dance, calm)
                        offlineQueueCount = offlineQueue.count()
                    } else {
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
                    }
                } catch (e: Exception) {
                    Timber.e(e, "setRateDance error")
                    Sentry.captureException(e)
                    offlineQueue.enqueueSetRateDance(songId, rate, dance, calm)
                    offlineQueueCount = offlineQueue.count()
                }
            }
        }
    }

    fun toggleOfflineMode() {
        val wasOffline = isOfflineMode
        isOfflineMode = !wasOffline
        appSettings.isOfflineMode = isOfflineMode
        if (wasOffline && appSettings.mysqlHost.isNotBlank() && offlineQueueCount > 0) {
            flushOfflineQueue()
        }
    }

    fun flushOfflineQueue() {
        if (appSettings.mysqlHost.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sent = offlineQueue.flushToDb(dbSync, appSettings)
                offlineQueueCount = 0
                showToast("$sent operații trimise la MySQL.", Toast.LENGTH_SHORT)
            } catch (e: Exception) {
                offlineQueueCount = offlineQueue.count()
                Timber.e(e, "flushOfflineQueue error")
                Sentry.captureException(e)
                showToast("Eroare trimitere offline: ${e.message}", Toast.LENGTH_LONG)
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
                            if (songInfoMap.isEmpty()) {
                                Sentry.captureMessage(
                                    "startSync: songInfoMap gol → saveEnriched fără date MySQL | fișiere=${paths.size}",
                                    SentryLevel.WARNING
                                )
                            }
                            playlistStore.saveEnriched(app, relIds, songInfoMap)
                        }
                        reloadPlayer()
                        val deletedMsg = if (state.deleted > 0) ", ${state.deleted} șterse local" else ""
                        showToast(
                            "Sincronizare completă: ${state.downloaded} descărcate, ${state.skipped} existau deja$deletedMsg.",
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
                reloadPlayer()
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
            MediaSessionCallback.NEXT -> nextFiltered()
            MediaSessionCallback.PREVIOUS -> previousFiltered()
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
