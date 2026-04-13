package com.example.kotlin_audio_example

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import android.view.WindowManager
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
import com.example.kotlin_audio_example.ui.component.PlayerControls
import com.example.kotlin_audio_example.ui.component.TrackDisplay
import com.example.kotlin_audio_example.ui.screen.SettingsScreen
import com.example.kotlin_audio_example.ui.theme.KotlinAudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        if (hasStoragePermission()) {
            loadAndPlay()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }

        // Acțiuni externe (notificare, headset) — observate o singură dată
        player.event.onPlayerActionTriggeredExternally
            .onEach { handleExternalAction(it) }
            .launchIn(lifecycleScope)

        setContent {
            KotlinAudioTheme {
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

        var title by remember { mutableStateOf("") }
        var artist by remember { mutableStateOf("") }
        var artwork by remember { mutableStateOf("") }
        var position by remember { mutableStateOf(0L) }
        var duration by remember { mutableStateOf(0L) }
        var isLive by remember { mutableStateOf(false) }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("BecashPlayer", color = MaterialTheme.colorScheme.onPrimary) },
                    colors = TopAppBarDefaults.smallTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                )

                SyncStatusBar(syncState = syncState)

                // Bara de acțiuni — butoane suplimentare vor fi adăugate aici
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

                // Playlist — se întinde ocupând tot spațiul disponibil
                PlaylistView(
                    items = playlistItems,
                    currentIndex = currentTrackIndex,
                    onItemClick = { index -> player.jumpToItem(index) },
                    modifier = Modifier.weight(1f)
                )

                TrackDisplay(
                    title = title, artist = artist, artwork = artwork,
                    position = position, duration = duration, isLive = isLive,
                    onSeek = { player.seek(it, TimeUnit.MILLISECONDS) },
                    modifier = Modifier.padding(top = 16.dp)
                )
                PlayerControls(
                    onPrevious = { player.previous() },
                    onNext = { player.next() },
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
                title = player.currentItem?.title ?: ""
                artist = player.currentItem?.artist ?: ""
                artwork = player.currentItem?.artwork ?: ""
                duration = player.currentItem?.duration ?: 0L
                isLive = player.isCurrentMediaItemLive
                currentTrackIndex = player.currentIndex
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
            withContext(Dispatchers.IO) { localFile.delete() }
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
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { buildLocalAudioItems() }
            if (items.isEmpty()) return@launch
            player.stop()
            player.removeUpcomingItems()
            player.add(items)
            player.play()
            playlistItems = player.items
            currentTrackIndex = 0
        }
    }

    // Rulează pe Dispatchers.IO — apelat mereu din withContext(Dispatchers.IO)
    private fun buildLocalAudioItems(): List<DefaultAudioItem> {
        val audioDir = File(Environment.getExternalStorageDirectory(), appSettings.localFolderName)
        if (!audioDir.exists() || !audioDir.isDirectory) return emptyList()
        val files = audioDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            .let { seq ->
                if (playlistMode == PlaylistMode.SHUFFLE) seq.toList().shuffled()
                else seq.sortedWith(compareBy({ it.parent }, { it.name })).toList()
            }
        return files.map { file ->
            val folderName = if (file.parentFile == audioDir) "" else file.parentFile?.name ?: ""
            DefaultAudioItem(
                audioUrl = "file://${file.absolutePath}",
                type = MediaType.DEFAULT,
                title = file.nameWithoutExtension,
                artist = folderName,
            )
        }
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
                        reloadPlayer()
                        Toast.makeText(
                            this@MainActivity,
                            "Sincronizare completă: ${state.downloaded} descărcate, ${state.skipped} existau deja.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    if (state is SyncState.Error) {
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            )
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
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        if (items.isNotEmpty()) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth()
    ) {
        itemsIndexed(items) { index, item ->
            val isPlaying = index == currentIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isPlaying) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    )
                    .clickable { onItemClick(index) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (!item.artist.isNullOrEmpty()) {
                        Text(
                            text = item.artist!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
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
    KotlinAudioTheme {
        MainScreen(
            title = "Title", artist = "Artist", artwork = "",
            position = 1000, duration = 6000, isLive = false, isPaused = true
        )
    }
}