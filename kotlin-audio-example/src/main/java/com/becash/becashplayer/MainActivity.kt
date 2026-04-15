package com.becash.becashplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.FilterListOff
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
import com.becash.becashplayer.ui.component.PlayerControls
import com.becash.becashplayer.ui.component.TrackDisplay
import com.becash.becashplayer.ui.screen.SettingsScreen
import com.becash.becashplayer.ui.theme.BecashPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.becash.becashplayer.ext.millisecondsToString
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

enum class PlaylistMode {
    SHUFFLE, NORMAL, PLAY_ONE;
    fun next() = when (this) {
        SHUFFLE  -> NORMAL
        NORMAL   -> PLAY_ONE
        PLAY_ONE -> SHUFFLE
    }
    val label get() = when (this) {
        SHUFFLE  -> "Shuffle"
        NORMAL   -> "Normal"
        PLAY_ONE -> "Play One"
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var player: QueuedAudioPlayer
    private lateinit var appSettings: AppSettings

    private var currentScreen by mutableStateOf<Screen>(Screen.Main)
    private var syncState by mutableStateOf<SyncState>(SyncState.Idle)
    private var playlistMode by mutableStateOf(PlaylistMode.SHUFFLE)
    private var playlistItems by mutableStateOf<List<com.doublesymmetry.kotlinaudio.models.AudioItem>>(emptyList())
    private var currentTrackIndex by mutableStateOf(0)
    private var filterQuery by mutableStateOf("")
    private var filterInverted by mutableStateOf(false)
    private var songInfoMap by mutableStateOf<Map<String, JSONObject>>(emptyMap())
    private var isDbSyncBusy by mutableStateOf(false)

    /**
     * Lista de indecși din player (în ordine sortată) care trec filtrul curent.
     * Dacă filtrul e gol, conține toți indicii sortați.
     */
    private val filteredIndices: List<Int>
        get() {
            // În modul shuffle coada e deja amestecată — păstrăm ordinea ei naturală
            // În modul normal sortăm alfabetic
            val indexed = playlistItems.mapIndexed { i, item -> i to item }
            val ordered = if (playlistMode == PlaylistMode.SHUFFLE) indexed
                          else indexed.sortedWith(compareBy({ it.second.artist ?: "" }, { it.second.title ?: "" }))
            return if (filterQuery.isBlank()) ordered.map { it.first }
            else {
                val q = filterQuery.trim().lowercase()
                ordered.filter { (_, item) ->
                    val matches = (item.title ?: "").lowercase().contains(q) ||
                                  (item.artist ?: "").lowercase().contains(q)
                    if (filterInverted) !matches else matches
                }.map { it.first }
            }
        }

    private fun nextFiltered() {
        val indices = filteredIndices
        if (indices.isEmpty()) { player.next(); return }
        val pos = indices.indexOf(currentTrackIndex)
        val next = if (pos < 0 || pos >= indices.lastIndex) indices.first() else indices[pos + 1]
        player.jumpToItem(next)
        player.play()
    }

    private fun previousFiltered() {
        val indices = filteredIndices
        if (indices.isEmpty()) { player.previous(); return }
        val pos = indices.indexOf(currentTrackIndex)
        val prev = if (pos <= 0) indices.last() else indices[pos - 1]
        player.jumpToItem(prev)
        player.play()
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------
    private val requiredPermissions: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ->
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            else ->
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            loadAndPlay()
        } else {
            Toast.makeText(this, "Permisiunea de stocare este necesară.", Toast.LENGTH_LONG).show()
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        Timber.plant(Timber.DebugTree())
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        appSettings = AppSettings(this)

        // Restaurează modul playlist salvat
        playlistMode = try {
            PlaylistMode.valueOf(appSettings.lastPlaylistMode)
        } catch (_: Exception) {
            PlaylistMode.SHUFFLE
        }
        filterQuery    = appSettings.filterQuery
        filterInverted = appSettings.filterInverted

        // Player inițializat pe main thread — obligatoriu pentru ExoPlayer
        player = QueuedAudioPlayer(
            this, playerConfig = PlayerConfig(
                interceptPlayerActionsTriggeredExternally = true,
                handleAudioBecomingNoisy = true,
                handleAudioFocus = true
            )
        )
        player.playerOptions.repeatMode = RepeatMode.ALL
        setupNotification()

        // Folder local creat pe IO thread
        lifecycleScope.launch(Dispatchers.IO) {
            val dir = File(Environment.getExternalStorageDirectory(), appSettings.localFolderName)
            if (!dir.exists()) dir.mkdirs()
        }

        // Încarcă cache-ul MySQL local
        songInfoMap = SongInfoStore.load(this)

        if (hasStoragePermission()) {
            loadAndPlay()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }

        // Acțiuni externe (notificare, headset) — observate o singură dată
        player.event.onPlayerActionTriggeredExternally
            .onEach { handleExternalAction(it) }
            .launchIn(lifecycleScope)

        // Sari automat la piesa următoare dacă fișierul curent este corupt
        player.event.playbackError
            .onEach { error ->
                Timber.e("Eroare redare (${error.code}): ${error.message} — se trece la piesa următoare")
                player.next()
            }
            .launchIn(lifecycleScope)

        setContent {
            BecashPlayerTheme {
                when (currentScreen) {
                    is Screen.Settings -> SettingsScreen(
                        settings = appSettings,
                        onBack = { currentScreen = Screen.Main }
                    )
                    is Screen.Main -> MainActivityContent()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainActivityContent() {
        val playerState = player.event.stateChange.collectAsState(initial = AudioPlayerState.IDLE)

        // Menține ecranul aprins cât timp cântă
        DisposableEffect(playerState.value) {
            if (playerState.value == AudioPlayerState.PLAYING) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        // Map audioUrl → JSONObject (rând MySQL) pentru a afișa durata în playlist
        val audioBaseDir = remember {
            File(Environment.getExternalStorageDirectory(), appSettings.localFolderName).absolutePath
        }
        val songInfoByUrl = remember(songInfoMap) {
            songInfoMap.entries.associate { (songId, doc) ->
                "file://$audioBaseDir/${songId.trimStart('/')}" to doc
            }
        }

        var title by remember { mutableStateOf("") }
        var artist by remember { mutableStateOf("") }
        var artwork by remember { mutableStateOf("") }
        var position by remember { mutableStateOf(0L) }
        var duration by remember { mutableStateOf(0L) }
        var isLive by remember { mutableStateOf(false) }

        // Detectare mod split-screen: fereastra ocupă mai puțin de 60% din ecran
        val context = LocalContext.current
        val density = LocalDensity.current
        val screenHeightPx = remember {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.maximumWindowMetrics.bounds.height()
            } else {
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                dm.heightPixels
            }
        }
        val screenHeightDp = with(density) { screenHeightPx.toDp() }
        val windowHeightDp = LocalConfiguration.current.screenHeightDp.dp
        val isSplitMode = windowHeightDp < screenHeightDp * 0.6f

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                SyncStatusBar(syncState = syncState)

                // Bara de acțiuni — ascunsă în mod split-screen
                if (!isSplitMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mod playlist
                        IconButton(onClick = { applyPlaylistMode(playlistMode.next()) }) {
                            val icon = when (playlistMode) {
                                PlaylistMode.SHUFFLE  -> Icons.Rounded.Shuffle
                                PlaylistMode.NORMAL   -> Icons.Rounded.Repeat
                                PlaylistMode.PLAY_ONE -> Icons.Rounded.RepeatOne
                            }
                            val tint = when (playlistMode) {
                                PlaylistMode.NORMAL -> MaterialTheme.colorScheme.onSurface
                                else               -> MaterialTheme.colorScheme.primary
                            }
                            Icon(icon, contentDescription = playlistMode.label, tint = tint)
                        }
                        // Sincronizare Nextcloud
                        IconButton(onClick = { startSync() }) {
                            Icon(
                                Icons.Rounded.Sync,
                                contentDescription = "Sincronizează Nextcloud",
                                tint = if (syncState is SyncState.Syncing)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        // Șterge cântecul curent (local + Nextcloud) și trece la următor
                        IconButton(onClick = { deleteCurrentTrack() }) {
                            Icon(
                                Icons.Rounded.DeleteForever,
                                contentDescription = "Șterge cântecul curent",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // Playlist — se întinde ocupând tot spațiul disponibil
                // Calculăm poziția exactă ca în PlaylistView: sortare alfabetică + filtru
                val sortedForLabel = remember(playlistItems) {
                    playlistItems.mapIndexed { i, item -> i to item }
                        .sortedWith(compareBy({ it.second.artist ?: "" }, { it.second.title ?: "" }))
                }
                val filteredForLabel = remember(sortedForLabel, filterQuery, filterInverted) {
                    if (filterQuery.isBlank()) sortedForLabel
                    else {
                        val q = filterQuery.trim().lowercase()
                        sortedForLabel.filter { (_, item) ->
                            val matches = (item.title ?: "").lowercase().contains(q) ||
                                          (item.artist ?: "").lowercase().contains(q)
                            if (filterInverted) !matches else matches
                        }
                    }
                }
                val trackPos = filteredForLabel.indexOfFirst { it.first == currentTrackIndex }
                    .takeIf { it >= 0 }?.plus(1)
                val trackLabel = if (trackPos != null) "$trackPos / ${filteredForLabel.size}" else ""

                // Playlist și bara de căutare — ascunse în mod split-screen
                if (!isSplitMode) {
                    PlaylistView(
                        items = playlistItems,
                        currentIndex = currentTrackIndex,
                        onItemClick = { index -> player.jumpToItem(index); player.play() },
                        filterQuery = filterQuery,
                        onFilterQueryChange = { filterQuery = it; appSettings.filterQuery = it },
                        filterInverted = filterInverted,
                        onFilterInvertedChange = { filterInverted = it; appSettings.filterInverted = it },
                        songInfoByUrl = songInfoByUrl,
                        trackLabel = trackLabel,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                TrackDisplay(
                    title = title, artist = artist, artwork = artwork,
                    position = position, duration = duration, isLive = isLive,
                    onSeek = { player.seek(it, TimeUnit.MILLISECONDS) },
                    modifier = Modifier.padding(top = 16.dp)
                )
                PlayerControls(
                    onPrevious = { previousFiltered() },
                    onNext = { nextFiltered() },
                    isPaused = playerState.value != AudioPlayerState.PLAYING,
                    onPlayPause = {
                        if (player.playerState == AudioPlayerState.PLAYING) player.pause()
                        else player.play()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                )
            }
        }

        // Track info — actualizat la schimbarea piesei
        LaunchedEffect(Unit) {
            player.event.audioItemTransition.collect {
                val newIndex = player.currentIndex
                // Dacă filtrul e activ și piesa nouă nu e în lista filtrată, sari la următoarea filtrată
                val fi = filteredIndices
                if (filterQuery.isNotBlank() && fi.isNotEmpty() && newIndex !in fi) {
                    val next = fi.firstOrNull { it > newIndex } ?: fi.first()
                    player.jumpToItem(next)
                    player.play()
                    return@collect
                }
                title = player.currentItem?.title ?: ""
                artist = player.currentItem?.artist ?: ""
                artwork = player.currentItem?.artwork ?: ""
                duration = player.currentItem?.duration ?: 0L
                isLive = player.isCurrentMediaItemLive
                currentTrackIndex = newIndex
                playlistItems = player.items
            }
        }

        // Poziția actualizată la 500ms pe main thread (ExoPlayer impune acest lucru)
        LaunchedEffect(Unit) {
            while (true) {
                delay(500L)
                position = player.position
                duration = player.duration
                isLive = player.isCurrentMediaItemLive
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle — salvare stare
    // -------------------------------------------------------------------------
    override fun onStop() {
        super.onStop()
        appSettings.lastTrackIndex   = player.currentIndex
        appSettings.lastPlaylistMode = playlistMode.name
    }

    // -------------------------------------------------------------------------
    // Player helpers
    // -------------------------------------------------------------------------
    private fun deleteCurrentTrack() {
        val audioUrl = player.currentItem?.audioUrl ?: return
        val localPath = audioUrl.removePrefix("file://")
        val localFile = File(localPath)
        val audioRootDir = File(Environment.getExternalStorageDirectory(), appSettings.localFolderName)
        val relativePath = localFile.toRelativeString(audioRootDir)  // ex: 883/song.mp3
        val remoteFilePath = "${appSettings.remoteFolderPath.trimEnd('/')}/$relativePath"

        // Trece la cântecul următor înainte de ștergere
        player.next()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                localFile.delete()
                // Elimină intrarea din playlist.json
                val updated = PlaylistStore.load(this@MainActivity, audioRootDir.absolutePath).filter { it != localPath }
                PlaylistStore.save(this@MainActivity, updated, audioRootDir.absolutePath)
            }
            WebDavSync.deleteFile(
                serverUrl = appSettings.serverUrl,
                username = appSettings.username,
                password = appSettings.password,
                remotePath = remoteFilePath
            )
            Toast.makeText(this@MainActivity, "\"${localFile.nameWithoutExtension}\" șters.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPlaylistMode(mode: PlaylistMode) {
        appSettings.lastPlaylistMode = mode.name
        playlistMode = mode
        when (mode) {
            PlaylistMode.SHUFFLE  -> { player.playerOptions.repeatMode = RepeatMode.ALL;  reloadPlayer() }
            PlaylistMode.NORMAL   -> { player.playerOptions.repeatMode = RepeatMode.ALL;  reloadPlayer() }
            PlaylistMode.PLAY_ONE -> { player.playerOptions.repeatMode = RepeatMode.ONE }
        }
    }

    private fun loadAndPlay() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { buildLocalAudioItems() }
            if (items.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "Folderul '${appSettings.localFolderName}' este gol. Sincronizează din Nextcloud.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            player.add(items)
            val savedIndex = appSettings.lastTrackIndex.coerceIn(0, items.lastIndex)
            if (savedIndex > 0) player.jumpToItem(savedIndex)
            player.play()
            playlistItems = player.items
            currentTrackIndex = player.currentIndex
        }
    }

    private fun reloadPlayer() {
        val currentUrl = player.currentItem?.audioUrl
        lifecycleScope.launch {
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

    // Rulează pe Dispatchers.IO — apelat mereu din withContext(Dispatchers.IO)
    private fun buildLocalAudioItems(): List<DefaultAudioItem> {
        val audioDir = File(Environment.getExternalStorageDirectory(), appSettings.localFolderName)

        // Citim lista din PlaylistStore; dacă e goală (prima rulare), scanăm și salvăm
        val paths = PlaylistStore.load(this, audioDir.absolutePath).ifEmpty {
            val scanned = scanAudioFiles(audioDir)
            if (scanned.isNotEmpty()) PlaylistStore.save(this, scanned, audioDir.absolutePath)
            scanned
        }

        val files = if (playlistMode == PlaylistMode.SHUFFLE)
            paths.map { File(it) }.shuffled()
        else
            paths.map { File(it) }.sortedWith(compareBy({ it.parent }, { it.name }))

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

    // Scanează filesystem-ul și returnează căile absolute — folosit doar la refresh
    private fun scanAudioFiles(audioDir: File): List<String> {
        if (!audioDir.exists() || !audioDir.isDirectory) return emptyList()
        return audioDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS && it.length() > 4096 }
            .sortedWith(compareBy({ it.parent }, { it.name }))
            .map { it.absolutePath }
            .toList()
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

    // -------------------------------------------------------------------------
    // Sincronizare WebDAV
    // -------------------------------------------------------------------------
    private fun hasWritePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true

    private fun startSync() {
        if (!appSettings.isConfigured()) {
            Toast.makeText(this, "Configurează mai întâi setările Nextcloud.", Toast.LENGTH_LONG).show()
            currentScreen = Screen.Settings
            return
        }
        if (!hasWritePermission()) {
            Toast.makeText(this, "Acordă permisiunea 'Acces la toate fișierele'.", Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
            return
        }

        lifecycleScope.launch {
            val localDir = File(Environment.getExternalStorageDirectory(), appSettings.localFolderName)
            WebDavSync.sync(
                settings = appSettings,
                localDir = localDir,
                onProgress = { state ->
                    syncState = state
                    if (state is SyncState.Done) {
                        // Rescrie playlist.json cu lista actualizată din folder
                        withContext(Dispatchers.IO) {
                            val paths = scanAudioFiles(localDir)
                            PlaylistStore.save(this@MainActivity, paths, localDir.absolutePath)
                        }
                        reloadPlayer()
                        Toast.makeText(
                            this@MainActivity,
                            "Sincronizare completă: ${state.downloaded} descărcate, ${state.skipped} existau deja.",
                            Toast.LENGTH_LONG
                        ).show()
                        // Imediat după Nextcloud, sincronizăm și datele din MySQL
                        startDbSync()
                    }
                    if (state is SyncState.Error) {
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    private fun startDbSync() {
        if (appSettings.mysqlHost.isBlank()) {
            Toast.makeText(this, "Configurează conexiunea MySQL în setări.", Toast.LENGTH_LONG).show()
            currentScreen = Screen.Settings
            return
        }
        isDbSyncBusy = true
        lifecycleScope.launch {
            try {
                // Descarcă întreaga tabelă played din MySQL
                val result = DbSync.sync(
                    host = appSettings.mysqlHost,
                    port = appSettings.mysqlPort,
                    user = appSettings.mysqlUser,
                    password = appSettings.mysqlPassword,
                    database = appSettings.mysqlDatabase,
                )
                withContext(Dispatchers.IO) {
                    SongInfoStore.save(this@MainActivity, result)
                    // Actualizează playlist.json cu datele din MySQL (mapping după id)
                    val playlistIds = PlaylistStore.load(this@MainActivity)
                    PlaylistStore.saveEnriched(this@MainActivity, playlistIds, result)
                }
                songInfoMap = result
                Toast.makeText(
                    this@MainActivity,
                    "MySQL: ${result.size} cântece sincronizate.",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Timber.e(e, "DbSync error")
                Toast.makeText(this@MainActivity, "Eroare MySQL: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isDbSyncBusy = false
            }
        }
    }

    private fun hasStoragePermission(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus")
    }
}

// -------------------------------------------------------------------------
// Navigare
// -------------------------------------------------------------------------
sealed class Screen {
    object Main : Screen()
    object Settings : Screen()
}

// -------------------------------------------------------------------------
// Bara de status sincronizare
// -------------------------------------------------------------------------
@Composable
fun SyncStatusBar(syncState: SyncState) {
    when (syncState) {
        is SyncState.Syncing -> {
            val progress = syncState.current.toFloat() / syncState.total.toFloat()
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    text = "Sincronizare: ${syncState.current}/${syncState.total} — ${syncState.currentFile}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1
                )
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            }
        }
        is SyncState.Error -> {
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "Eroare sincronizare: ${syncState.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, maxLines = 2
                )
            }
        }
        else -> {}
    }
}

// -------------------------------------------------------------------------
// Playlist
// -------------------------------------------------------------------------
@Composable
fun PlaylistView(
    items: List<AudioItem>,
    currentIndex: Int,
    onItemClick: (Int) -> Unit,
    filterQuery: String,
    onFilterQueryChange: (String) -> Unit,
    filterInverted: Boolean,
    onFilterInvertedChange: (Boolean) -> Unit,
    songInfoByUrl: Map<String, JSONObject> = emptyMap(),
    trackLabel: String = "",
    modifier: Modifier = Modifier,
) {
    // Sortare alfabetică după folder + titlu, cu păstrarea indexului original din player
    val sorted = remember(items) {
        items.mapIndexed { index, item -> index to item }
            .sortedWith(compareBy(
                { it.second.artist ?: "" },
                { it.second.title ?: "" }
            ))
    }

    // Filtrare — cântecul care cântă nu este afectat de filtru (rămâne în player)
    val filtered = remember(sorted, filterQuery, filterInverted) {
        if (filterQuery.isBlank()) {
            sorted
        } else {
            val q = filterQuery.trim().lowercase()
            sorted.filter { (_, item) ->
                val matches = (item.title ?: "").lowercase().contains(q) ||
                              (item.artist ?: "").lowercase().contains(q)
                if (filterInverted) !matches else matches
            }
        }
    }

    // Poziția cântecului curent în lista filtrată
    val currentPosInFiltered = remember(filtered, currentIndex) {
        filtered.indexOfFirst { it.first == currentIndex }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(currentPosInFiltered) {
        if (currentPosInFiltered >= 0) {
            listState.animateScrollToItem(currentPosInFiltered)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            itemsIndexed(filtered) { listPos, (playerIndex, item) ->
                val isPlaying = playerIndex == currentIndex
                val rowNumber = listPos + 1
                val itemColor = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                val duration = songInfoByUrl[item.audioUrl]?.optLong("duration", 0L)?.takeIf { it > 0 }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isPlaying) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable { onItemClick(playerIndex) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$rowNumber",
                        style = MaterialTheme.typography.labelSmall,
                        color = itemColor,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        if (!item.artist.isNullOrEmpty()) {
                            Text(
                                text = item.artist!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = itemColor,
                                maxLines = 1
                            )
                        }
                        Text(
                            text = item.title ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                    if (duration != null) {
                        Text(
                            text = duration.millisecondsToString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = itemColor,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }

        // Bara de filtrare — în partea de jos a playlist-ului
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (trackLabel.isNotEmpty()) {
                Text(
                    text = trackLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, end = 8.dp)
                )
            }
            OutlinedTextField(
                value = filterQuery,
                onValueChange = onFilterQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Filtrează...", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
            )
            IconButton(onClick = { onFilterInvertedChange(!filterInverted) }) {
                Icon(
                    imageVector = if (filterInverted) Icons.Rounded.FilterListOff else Icons.Rounded.FilterList,
                    contentDescription = if (filterInverted) "Anulează inversare" else "Inversează filtrul",
                    tint = if (filterInverted) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// -------------------------------------------------------------------------
// Preview
// -------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    title: String, artist: String, artwork: String,
    position: Long, duration: Long, isLive: Boolean,
    onPrevious: () -> Unit = {}, onNext: () -> Unit = {},
    isPaused: Boolean, onPlayPause: () -> Unit = {}, onSeek: (Long) -> Unit = {},
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("BecashPlayer", color = MaterialTheme.colorScheme.onPrimary) },
                colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
            )
            TrackDisplay(
                title = title, artist = artist, artwork = artwork,
                position = position, duration = duration, isLive = isLive,
                onSeek = onSeek, modifier = Modifier.padding(top = 46.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            PlayerControls(
                onPrevious = onPrevious, onNext = onNext,
                isPaused = isPaused, onPlayPause = onPlayPause,
                modifier = Modifier.fillMaxWidth().padding(bottom = 60.dp)
            )
        }
    }
}

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ContentPreview() {
    BecashPlayerTheme {
        MainScreen(
            title = "Title", artist = "Artist", artwork = "",
            position = 1000, duration = 6000, isLive = false, isPaused = true
        )
    }
}