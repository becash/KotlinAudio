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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.doublesymmetry.kotlinaudio.models.AudioPlayerState
import com.doublesymmetry.kotlinaudio.models.DefaultAudioItem
import com.doublesymmetry.kotlinaudio.models.MediaSessionCallback
import com.doublesymmetry.kotlinaudio.models.MediaType
import com.doublesymmetry.kotlinaudio.models.NotificationButton
import com.doublesymmetry.kotlinaudio.models.NotificationConfig
import com.doublesymmetry.kotlinaudio.models.RepeatMode
import com.doublesymmetry.kotlinaudio.models.PlayerConfig
import com.doublesymmetry.kotlinaudio.players.QueuedAudioPlayer
import com.example.kotlin_audio_example.ui.component.ActionBottomSheet
import com.example.kotlin_audio_example.ui.component.PlayerControls
import com.example.kotlin_audio_example.ui.component.TrackDisplay
import com.example.kotlin_audio_example.ui.screen.SettingsScreen
import com.example.kotlin_audio_example.ui.theme.KotlinAudioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {
    private lateinit var player: QueuedAudioPlayer
    private lateinit var appSettings: AppSettings

    // -------------------------------------------------------------------------
    // UI state (hoisted as Activity-level state for simplicity)
    // -------------------------------------------------------------------------
    private var currentScreen by mutableStateOf<Screen>(Screen.Main)
    private var syncState by mutableStateOf<SyncState>(SyncState.Idle)

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
            loadAndPlayFromLocalFolder()
        } else {
            Toast.makeText(
                this,
                "Permisiunea de stocare este necesară pentru a citi folderul Audio.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        Timber.plant(Timber.DebugTree())
        super.onCreate(savedInstanceState)

        appSettings = AppSettings(this)

        player = QueuedAudioPlayer(
            this, playerConfig = PlayerConfig(
                interceptPlayerActionsTriggeredExternally = true,
                handleAudioBecomingNoisy = true,
                handleAudioFocus = true
            )
        )
        player.playerOptions.repeatMode = RepeatMode.ALL

        setupNotification()
        createLocalAudioFolder()

        if (hasStoragePermission()) {
            loadAndPlayFromLocalFolder()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }

        setContent {
            KotlinAudioTheme {
                when (currentScreen) {
                    is Screen.Settings -> {
                        SettingsScreen(
                            settings = appSettings,
                            onBack = { currentScreen = Screen.Main }
                        )
                    }
                    is Screen.Main -> {
                        MainActivityContent()
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Main screen content
    // -------------------------------------------------------------------------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainActivityContent() {
        val state = player.event.stateChange.collectAsState(initial = AudioPlayerState.IDLE)
        var title by remember { mutableStateOf("") }
        var artist by remember { mutableStateOf("") }
        var artwork by remember { mutableStateOf("") }
        var position by remember { mutableStateOf(0L) }
        var duration by remember { mutableStateOf(0L) }
        var isLive by remember { mutableStateOf(false) }
        var showSheet by remember { mutableStateOf(false) }

        if (showSheet) {
            ActionBottomSheet(
                onDismiss = { showSheet = false },
                onOpenSettings = { currentScreen = Screen.Settings },
                onStartSync = { startSync() }
            )
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(
                            text = "Kotlin Audio",
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    },
                    actions = {
                        IconButton(onClick = { showSheet = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Meniu",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.smallTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                )

                // Bara de progres sincronizare
                SyncStatusBar(syncState = syncState)

                TrackDisplay(
                    title = title,
                    artist = artist,
                    artwork = artwork,
                    position = position,
                    duration = duration,
                    isLive = isLive,
                    onSeek = { player.seek(it, TimeUnit.MILLISECONDS) },
                    modifier = Modifier.padding(top = 16.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                PlayerControls(
                    onPrevious = { player.previous() },
                    onNext = { player.next() },
                    isPaused = state.value != AudioPlayerState.PLAYING,
                    onPlayPause = {
                        if (player.playerState == AudioPlayerState.PLAYING) {
                            player.pause()
                        } else {
                            player.play()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 60.dp)
                )
            }
        }

        LaunchedEffect(player, player.event.audioItemTransition, player.event.onPlayerActionTriggeredExternally) {
            player.event.audioItemTransition
                .onEach {
                    title = player.currentItem?.title ?: ""
                    artist = player.currentItem?.artist ?: ""
                    artwork = player.currentItem?.artwork ?: ""
                    duration = player.currentItem?.duration ?: 0
                    isLive = player.isCurrentMediaItemLive
                }
                .launchIn(this)

            player.event.onPlayerActionTriggeredExternally
                .onEach {
                    when (it) {
                        MediaSessionCallback.PLAY -> player.play()
                        MediaSessionCallback.PAUSE -> player.pause()
                        MediaSessionCallback.NEXT -> player.next()
                        MediaSessionCallback.PREVIOUS -> player.previous()
                        MediaSessionCallback.STOP -> player.stop()
                        is MediaSessionCallback.SEEK -> player.seek(it.positionMs, TimeUnit.MILLISECONDS)
                        else -> Timber.d("Event not handled")
                    }
                }
                .launchIn(this)
        }

        LaunchedEffect(Unit) {
            while (true) {
                position = player.position
                duration = player.duration
                isLive = player.isCurrentMediaItemLive
                delay(1.seconds / 30)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Sincronizare WebDAV
    // -------------------------------------------------------------------------
    private fun hasWritePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // gestionat prin requestLegacyExternalStorage + WRITE_EXTERNAL_STORAGE runtime
        }
    }

    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun startSync() {
        if (!appSettings.isConfigured()) {
            Toast.makeText(
                this,
                "Configurează mai întâi setările Nextcloud.",
                Toast.LENGTH_LONG
            ).show()
            currentScreen = Screen.Settings
            return
        }

        if (!hasWritePermission()) {
            Toast.makeText(
                this,
                "Acordă permisiunea 'Acces la toate fișierele' pentru a descărca muzică.",
                Toast.LENGTH_LONG
            ).show()
            requestManageStoragePermission()
            return
        }

        lifecycleScope.launch {
            val localDir = File(
                Environment.getExternalStorageDirectory(),
                appSettings.localFolderName
            )
            WebDavSync.sync(
                settings = appSettings,
                localDir = localDir,
                onProgress = { state ->
                    syncState = state
                    if (state is SyncState.Done) {
                        // Reîncarcă playerul după sincronizare
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

    // -------------------------------------------------------------------------
    // Player helpers
    // -------------------------------------------------------------------------
    private fun createLocalAudioFolder() {
        val audioDir = File(
            Environment.getExternalStorageDirectory(),
            appSettings.localFolderName
        )
        if (!audioDir.exists()) {
            audioDir.mkdirs()
            Timber.d("Folder creat: ${audioDir.absolutePath}")
        }
    }

    private fun loadAndPlayFromLocalFolder() {
        val items = buildLocalAudioItems()
        if (items.isEmpty()) {
            Toast.makeText(
                this,
                "Folderul '${appSettings.localFolderName}' este gol. Adaugă fișiere audio sau sincronizează din Nextcloud.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        player.add(items)
        player.play()
    }

    private fun reloadPlayer() {
        val items = buildLocalAudioItems()
        if (items.isEmpty()) return
        player.stop()
        player.removeUpcomingItems()
        player.add(items)
        player.play()
    }

    private fun buildLocalAudioItems(): List<DefaultAudioItem> {
        val audioDir = File(
            Environment.getExternalStorageDirectory(),
            appSettings.localFolderName
        )
        if (!audioDir.exists() || !audioDir.isDirectory) return emptyList()
        return audioDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            ?.sortedBy { it.name }
            ?.map { file ->
                DefaultAudioItem(
                    audioUrl = "file://${file.absolutePath}",
                    type = MediaType.DEFAULT,
                    title = file.nameWithoutExtension,
                    artist = "",
                )
            }
            ?: emptyList()
    }

    private fun hasStoragePermission(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun setupNotification() {
        val notificationConfig = NotificationConfig(
            listOf(
                NotificationButton.PLAY_PAUSE(),
                NotificationButton.NEXT(isCompact = true),
                NotificationButton.PREVIOUS(isCompact = true),
                NotificationButton.SEEK_TO
            ),
            accentColor = null, smallIcon = null, pendingIntent = null
        )
        player.notificationManager.createNotification(notificationConfig)
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Sincronizare: ${syncState.current}/${syncState.total} — ${syncState.currentFile}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        is SyncState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "Eroare sincronizare: ${syncState.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2
                )
            }
        }
        else -> { /* Idle sau Done — nu afișăm nimic */ }
    }
}

// -------------------------------------------------------------------------
// Preview
// -------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    title: String,
    artist: String,
    artwork: String,
    position: Long,
    duration: Long,
    isLive: Boolean,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    isPaused: Boolean,
    onTopBarAction: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = "Kotlin Audio",
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                },
                actions = {
                    IconButton(onClick = onTopBarAction) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Meniu",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            )
            TrackDisplay(
                title = title,
                artist = artist,
                artwork = artwork,
                position = position,
                duration = duration,
                isLive = isLive,
                onSeek = onSeek,
                modifier = Modifier.padding(top = 46.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            PlayerControls(
                onPrevious = onPrevious,
                onNext = onNext,
                isPaused = isPaused,
                onPlayPause = onPlayPause,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 60.dp)
            )
        }
    }
}

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ContentPreview() {
    KotlinAudioTheme {
        MainScreen(
            title = "Title",
            artist = "Artist",
            artwork = "",
            position = 1000,
            duration = 6000,
            isLive = false,
            isPaused = true
        )
    }
}