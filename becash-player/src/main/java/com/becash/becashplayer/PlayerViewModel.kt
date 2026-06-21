package com.becash.becashplayer

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.becash.becashplayer.data.AUDIO_EXTENSIONS
import com.becash.becashplayer.data.AppSettings
import com.becash.becashplayer.data.DbSync
import com.becash.becashplayer.data.OfflineQueue
import com.becash.becashplayer.data.PlaylistStore
import com.becash.becashplayer.data.SyncState
import com.becash.becashplayer.data.WebDavSync
import com.becash.becashplayer.ui.Screen
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
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@UnstableApi
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

    // Sursa de adevăr pentru logică: player.items (live din ExoPlayer).
    // playlistItems e o copie pentru UI Compose și nu trebuie folosită în logica de navigare.

    /** ID-ul cântecului (cheia din playlist.json/MySQL) derivat din URL-ul audio. */
    private fun songIdOf(url: String?): String? {
        url ?: return null
        val decoded = try { URLDecoder.decode(url, "UTF-8") } catch (_: Exception) { url }
        val rel = decoded.removePrefix("file://$audioBaseDir")
        return if (rel.startsWith("/")) rel else "/$rel"
    }

    /** ID-ul cântecului curent — folosit de UI pentru songInfoMap și de statistici. */
    val currentSongId: String?
        get() = songIdOf(player.currentItem?.audioUrl)

    val ratingFilteredIndices: Set<Int>?
        get() {
            if (ratingFilter == RatingFilter.ALL) return null
            return player.items.mapIndexedNotNull { idx, item ->
                val songId = songIdOf(item.audioUrl) ?: return@mapIndexedNotNull null
                if (passesRatingFilter(songId, songInfoMap, ratingFilter)) idx else null
            }.toSet()
        }

    val filteredIndices: List<Int>
        get() {
            val indexed = player.items.mapIndexed { i, item -> i to item }
            val ordered = if (playlistMode == PlaylistMode.SHUFFLE) indexed
                          else indexed.sortedWith(compareBy(byArtistTitle) { it.second })
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
                showToast("Eroare redare (${error.code}): ${error.message}", Toast.LENGTH_LONG)
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
            .onEach { reason -> handleItemTransition(reason) }
            .launchIn(viewModelScope)
    }

    private fun handleItemTransition(reason: AudioItemTransitionReason?) {
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
                    listenSongId = currentSongId
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
                    player.pause()
                    player.jumpToItem(pickShuffle() ?: fallbackNextIndex())
                    player.play()
                }
                PlaylistMode.NORMAL -> {
                    player.pause()
                    player.jumpToItem(pickSequential(+1) ?: fallbackNextIndex())
                    player.play()
                }
            }
            return
        }

        // Tranziție programatică (jumpToItem din next/previous/delete/loadAndPlay etc.)
        // Actualizăm starea UI și înregistrăm play-ul pentru cântecul nou.
        val songId = currentSongId
        listenSongId = songId
        currentTrackIndex = player.currentIndex
        playlistItems = player.items
        listenDuration = player.currentItem?.duration ?: 0L

        if (songId != null) {
            recordStat(
                label = "incrementPlays",
                enqueue = { offlineQueue.enqueueIncrementPlays(songId) },
                send = { dbSync.incrementPlays(appSettings, songId) },
            )
        }
    }

    // ---------------------------------------------------------------------
    // Navigare în playlist — alegerea indexului următor, o singură implementare
    // ---------------------------------------------------------------------

    /** Index aleator din lista filtrată, diferit de cel curent; null dacă filtrul e gol. */
    private fun pickShuffle(): Int? {
        val fi = filteredIndices
        if (fi.isEmpty()) return null
        val candidates = fi.filter { it != player.currentIndex }
        return if (candidates.isEmpty()) fi.first()
               else candidates[Random.nextInt(candidates.size)]
    }

    /** Indexul următor/precedent din lista filtrată (cu wrap-around); null dacă filtrul e gol. */
    private fun pickSequential(offset: Int): Int? {
        val fi = filteredIndices
        if (fi.isEmpty()) return null
        val pos = fi.indexOf(player.currentIndex)
        return if (pos < 0) (if (offset > 0) fi.first() else fi.last())
               else fi[(pos + offset).mod(fi.size)]
    }

    private fun fallbackNextIndex(): Int =
        (player.currentIndex + 1) % player.items.size.coerceAtLeast(1)

    fun nextFiltered() {
        when (playlistMode) {
            PlaylistMode.MANUAL -> {
                val nextIdx = manualNextIndex ?: return
                manualNextIndex = null
                player.jumpToItem(nextIdx)
                player.play()
            }
            PlaylistMode.SHUFFLE -> {
                player.jumpToItem(pickShuffle() ?: fallbackNextIndex())
                player.play()
            }
            else -> {
                val next = pickSequential(+1) ?: run { player.next(); return }
                player.jumpToItem(next)
                player.play()
            }
        }
    }

    fun previousFiltered() {
        when (playlistMode) {
            PlaylistMode.MANUAL -> player.seek(0L, TimeUnit.MILLISECONDS)
            PlaylistMode.SHUFFLE -> {
                val size = player.items.size.coerceAtLeast(1)
                player.jumpToItem(pickShuffle() ?: (player.currentIndex - 1 + size) % size)
                player.play()
            }
            else -> {
                val prev = pickSequential(-1) ?: run { player.previous(); return }
                player.jumpToItem(prev)
                player.play()
            }
        }
    }

    fun deleteCurrentTrack() {
        val audioUrl = player.currentItem?.audioUrl ?: return
        val localPath = audioUrl.removePrefix("file://")
        val localFile = File(localPath)
        val audioRootDir = File(Environment.getExternalStorageDirectory(), appSettings.localFolderName)
        val relativePath = localFile.toRelativeString(audioRootDir)
        val remoteFilePath = "${appSettings.remoteFolderPath.trimEnd('/')}/$relativePath"
        val deletedIndex = player.currentIndex
        val wasPlaying = player.playerState == AudioPlayerState.PLAYING

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
                val fi = filteredIndices
                val nextIndex = when {
                    fi.isEmpty() -> deletedIndex.coerceIn(0, playlistItems.lastIndex)
                    playlistMode == PlaylistMode.SHUFFLE -> fi[Random.nextInt(fi.size)]
                    else -> fi.firstOrNull { it >= deletedIndex } ?: fi.first()
                }
                player.jumpToItem(nextIndex)
                if (wasPlaying) player.play()
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
        // Toate modurile rulează cu RepeatMode.ONE: tranziția REPEAT e semnalul că piesa
        // s-a terminat, iar noi alegem următoarea (vezi handleItemTransition).
        player.playerOptions.repeatMode = RepeatMode.ONE
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
        flushListen()
        val currentUrl = player.currentItem?.audioUrl
        val savedPosition = player.position
        val wasPlaying = player.playerState == AudioPlayerState.PLAYING
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                if (playlistMode == PlaylistMode.SHUFFLE && currentUrl != null) {
                    songIdOf(currentUrl)?.let { playlistStore.zeroWeight(app, it) }
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
                    "buildLocalAudioItems: scanAudioFiles gol | dir=${audioDir.absolutePath} | exists=${audioDir.exists()} | canRead=${audioDir.canRead()} | isManageStorageGranted=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else "n/a"}"
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
            val selectedIdx = if (totalWeight <= 0.0) {
                // Toate greutățile sunt 0 — alegere uniform aleatoare
                Random.nextInt(remaining.size)
            } else {
                var r = Random.nextDouble() * totalWeight
                var idx = remaining.lastIndex
                for (i in remaining.indices) {
                    r -= remaining[i].second
                    if (r <= 0.0) { idx = i; break }
                }
                idx
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

    // ---------------------------------------------------------------------
    // Statistici (MySQL sau coadă offline) — un singur loc pentru dispatch
    // ---------------------------------------------------------------------

    /**
     * Rulează o operație de statistici: în mod offline o pune în coadă, altfel
     * o trimite la MySQL; la eroare cade înapoi pe coada offline.
     */
    private fun recordStat(label: String, enqueue: () -> Unit, send: suspend () -> Unit) {
        if (!appSettings.isMysqlConfigured()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isOfflineMode) {
                    enqueue()
                    offlineQueueCount = offlineQueue.count()
                } else {
                    send()
                }
            } catch (e: Exception) {
                Timber.e(e, "$label error")
                Sentry.captureException(e)
                enqueue()
                offlineQueueCount = offlineQueue.count()
            }
        }
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
        if (ms <= 0) return
        recordStat(
            label = "addListen",
            enqueue = { offlineQueue.enqueueAddListen(songId, ms, dur) },
            send = { dbSync.addListen(appSettings, songId, ms, dur) },
        )
    }

    fun updateRateDance(songId: String, rate: Int, dance: Boolean, calm: Boolean) {
        val updated = (songInfoMap[songId]?.let { JSONObject(it.toString()) } ?: JSONObject()).apply {
            put("rate", rate)
            put("dance", if (dance) 1 else 0)
            put("calm", if (calm) 1 else 0)
        }
        songInfoMap = songInfoMap + (songId to updated)
        recordStat(
            label = "setRateDance",
            enqueue = { offlineQueue.enqueueSetRateDance(songId, rate, dance, calm) },
            send = { dbSync.setRateDance(appSettings, songId, rate, dance, calm) },
        )
    }

    fun toggleOfflineMode() {
        val wasOffline = isOfflineMode
        isOfflineMode = !wasOffline
        appSettings.isOfflineMode = isOfflineMode
        if (wasOffline && appSettings.isMysqlConfigured() && offlineQueueCount > 0) {
            flushOfflineQueue()
        }
    }

    fun flushOfflineQueue() {
        if (!appSettings.isMysqlConfigured()) return
        viewModelScope.launch(Dispatchers.IO) {
            flushOfflineQueueNow()
        }
    }

    // Golește coada offline în MySQL pe coroutina curentă (de așteptat înainte de pull-ul MySQL).
    private suspend fun flushOfflineQueueNow() {
        if (!appSettings.isMysqlConfigured() || offlineQueue.count() == 0) return
        try {
            val sent = offlineQueue.flushToDb(dbSync, appSettings)
            offlineQueueCount = 0
            if (sent > 0) showToast("$sent operații trimise la MySQL.", Toast.LENGTH_SHORT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            offlineQueueCount = offlineQueue.count()
            Timber.e(e, "flushOfflineQueue error")
            Sentry.captureException(e)
            showToast("Eroare trimitere offline: ${e.message}", Toast.LENGTH_LONG)
        }
    }

    // ---------------------------------------------------------------------
    // Sincronizare Nextcloud + MySQL
    // ---------------------------------------------------------------------

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
                        // Întâi împinge scrierile locale în așteptare, apoi trage datele proaspete din MySQL.
                        flushOfflineQueueNow()
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
        if (!appSettings.isMysqlConfigured()) {
            showToast("Configurează conexiunea MySQL în setări.", Toast.LENGTH_LONG)
            currentScreen = Screen.Settings
            return
        }
        isDbSyncBusy = true
        viewModelScope.launch {
            try {
                val result = dbSync.sync(appSettings)
                if (result == null) {
                    // MySQL inaccesibil: păstrăm playlist-ul îmbogățit existent, nu-l suprascriem cu ID-uri goale.
                    showToast("MySQL inaccesibil — playlist-ul local a fost păstrat.", Toast.LENGTH_LONG)
                    return@launch
                }
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
