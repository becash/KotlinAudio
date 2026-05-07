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
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.rounded.EmojiPeople
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import com.doublesymmetry.kotlinaudio.models.AudioItem
import com.doublesymmetry.kotlinaudio.models.AudioPlayerState
import com.becash.becashplayer.ui.component.PlayerControls
import com.becash.becashplayer.ui.component.TrackDisplay
import com.becash.becashplayer.ui.screen.SettingsScreen
import com.becash.becashplayer.ui.theme.BecashPlayerTheme
import com.becash.becashplayer.ext.toDurationString
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit


class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    private val requiredPermissions: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ->
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else ->
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.loadAndPlay()
        } else {
            Toast.makeText(this, "Permisiunea de stocare este necesară.", Toast.LENGTH_LONG).show()
        }
    }

    private var pendingCallNumber: String? = null
    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCallNumber?.let { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$it"))) }
        } else {
            Toast.makeText(this, "Permisiunea de apel este necesară.", Toast.LENGTH_LONG).show()
        }
        pendingCallNumber = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (hasStoragePermission()) {
            viewModel.loadAndPlay()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }

        setContent {
            BecashPlayerTheme {
                when (viewModel.currentScreen) {
                    is Screen.Settings -> SettingsScreen(
                        settings = viewModel.appSettings,
                        onBack = { viewModel.currentScreen = Screen.Main }
                    )
                    is Screen.Main -> MainActivityContent()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.saveState()
    }

    fun callPhone(number: String) {
        if (number.isBlank()) return
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
        } else {
            pendingCallNumber = number
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun hasStoragePermission(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }


    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainActivityContent() {
        val vm = viewModel
        val playerState = vm.player.event.stateChange.collectAsState(initial = AudioPlayerState.IDLE)

        DisposableEffect(playerState.value) {
            if (playerState.value == AudioPlayerState.PLAYING) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }

        val context = LocalContext.current
        val density = LocalDensity.current
        val screenHeightPx = remember {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.maximumWindowMetrics.bounds.height()
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                dm.heightPixels
            }
        }
        val screenHeightDp = with(density) { screenHeightPx.toDp() }
        val windowHeightDp = LocalConfiguration.current.screenHeightDp.dp
        val isSplitMode = windowHeightDp < screenHeightDp * 0.6f

        val audioBaseDir = remember {
            File(Environment.getExternalStorageDirectory(), vm.appSettings.localFolderName).absolutePath
        }
        val songInfoByUrl = remember(vm.songInfoMap, audioBaseDir) {
            vm.songInfoMap.entries.associate { (songId, doc) ->
                Uri.fromFile(File("$audioBaseDir/${songId.trimStart('/')}")).toString() to doc
            }
        }

        var position by remember { mutableStateOf(0L) }
        var duration by remember { mutableStateOf(0L) }

        val title    = vm.player.currentItem?.title   ?: ""
        val artist   = vm.player.currentItem?.artist  ?: ""
        val artwork  = vm.player.currentItem?.artwork ?: ""
        val isLive   = vm.player.isCurrentMediaItemLive

        var showTrackInfoDialog by remember { mutableStateOf(false) }
        var showRateDanceDialog by remember { mutableStateOf(false) }
        var showQuickPanel by remember { mutableStateOf(false) }

        val currentRelPath = remember(vm.currentTrackIndex, vm.playlistItems) {
            vm.playlistItems.getOrNull(vm.currentTrackIndex)?.audioUrl
                ?.let { url -> try { URLDecoder.decode(url, "UTF-8") } catch (_: Exception) { url } }
                ?.removePrefix("file://$audioBaseDir")
        }
        val currentSongId = remember(currentRelPath) {
            currentRelPath?.let { if (it.startsWith("/")) it else "/$it" }
        }

        // Indecșii care trec filtrul de rating (pentru PlaylistView) — sursă unică din ViewModel
        val ratingFilterSet by remember(vm.ratingFilter, vm.songInfoMap, vm.playlistItems) {
            derivedStateOf { vm.ratingFilteredIndices }
        }

        // Poziția cântecului curent în lista filtrată (pentru eticheta "N / total")
        val sortedForLabel = remember(vm.playlistItems, ratingFilterSet) {
            val filterSet = ratingFilterSet
            vm.playlistItems.mapIndexed { i, item -> i to item }
                .filter { (i, _) -> filterSet == null || i in filterSet }
                .sortedWith(compareBy({ it.second.artist ?: "" }, { it.second.title ?: "" }))
        }
        val filteredForLabel = remember(sortedForLabel, vm.filterQuery, vm.filterInverted) {
            if (vm.filterQuery.isBlank()) sortedForLabel
            else sortedForLabel.filter { (_, item) -> matchesTextFilter(item, vm.filterQuery, vm.filterInverted) }
        }
        val trackPos = filteredForLabel.indexOfFirst { it.first == vm.currentTrackIndex }
            .takeIf { it >= 0 }?.plus(1)
        val trackLabel = if (trackPos != null) "$trackPos / ${filteredForLabel.size}" else ""

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                SyncStatusBar(syncState = vm.syncState)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var modeMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { modeMenuExpanded = true }) {
                            val icon = when (vm.playlistMode) {
                                PlaylistMode.SHUFFLE  -> Icons.Rounded.Shuffle
                                PlaylistMode.NORMAL   -> Icons.Rounded.Repeat
                                PlaylistMode.PLAY_ONE -> Icons.Rounded.RepeatOne
                                PlaylistMode.MANUAL   -> Icons.Rounded.TouchApp
                            }
                            val tint = when (vm.playlistMode) {
                                PlaylistMode.NORMAL -> MaterialTheme.colorScheme.onSurface
                                else               -> MaterialTheme.colorScheme.primary
                            }
                            Icon(icon, contentDescription = vm.playlistMode.label, tint = tint)
                        }
                        DropdownMenu(expanded = modeMenuExpanded, onDismissRequest = { modeMenuExpanded = false }) {
                            listOf(
                                PlaylistMode.SHUFFLE  to Icons.Rounded.Shuffle,
                                PlaylistMode.NORMAL   to Icons.Rounded.Repeat,
                                PlaylistMode.PLAY_ONE to Icons.Rounded.RepeatOne,
                                PlaylistMode.MANUAL   to Icons.Rounded.TouchApp,
                            ).forEach { (mode, icon) ->
                                val active = vm.playlistMode == mode
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    leadingIcon = {
                                        Icon(icon, contentDescription = null,
                                            tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                    },
                                    onClick = { modeMenuExpanded = false; vm.applyPlaylistMode(mode) }
                                )
                            }
                        }
                    }

                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        BadgedBox(
                            badge = {
                                if (vm.offlineQueueCount > 0) {
                                    Badge {
                                        Text(if (vm.offlineQueueCount > 99) "99+" else "${vm.offlineQueueCount}")
                                    }
                                }
                            }
                        ) {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = "Mai multe opțiuni",
                                    tint = if (vm.syncState is SyncState.Syncing || vm.isOfflineMode)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Sincronizează Nextcloud") },
                                leadingIcon = { Icon(Icons.Rounded.Sync, contentDescription = null) },
                                onClick = { menuExpanded = false; vm.startSync() }
                            )
                            DropdownMenuItem(
                                text = {
                                    val label = if (vm.isOfflineMode) "Mod offline" else "Mod online"
                                    val suffix = if (vm.offlineQueueCount > 0) " — ${vm.offlineQueueCount} în coadă" else ""
                                    Text(label + suffix)
                                },
                                leadingIcon = {
                                    Icon(
                                        if (vm.isOfflineMode) Icons.Rounded.AirplanemodeActive else Icons.Rounded.Wifi,
                                        contentDescription = null,
                                        tint = if (vm.isOfflineMode) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                    )
                                },
                                onClick = { menuExpanded = false; vm.toggleOfflineMode() }
                            )
                            if (vm.appSettings.bariera9.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text("Bariera 9 — ${vm.appSettings.bariera9}") },
                                    leadingIcon = { Text("9", style = MaterialTheme.typography.titleMedium) },
                                    onClick = { menuExpanded = false; callPhone(vm.appSettings.bariera9) }
                                )
                            }
                            if (vm.appSettings.bariera10.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text("Bariera 10 — ${vm.appSettings.bariera10}") },
                                    leadingIcon = { Text("10", style = MaterialTheme.typography.titleMedium) },
                                    onClick = { menuExpanded = false; callPhone(vm.appSettings.bariera10) }
                                )
                            }
                        }
                    }

                    val playlistFilters = remember {
                        listOf(
                            RatingFilter.NO_RATING   to Icons.Rounded.StarBorder,
                            RatingFilter.WITH_RATING to Icons.Rounded.Star,
                            RatingFilter.TOP         to Icons.Rounded.Person,
                            RatingFilter.BEST        to Icons.Rounded.Public,
                            RatingFilter.DANCE       to Icons.Rounded.EmojiPeople,
                            RatingFilter.CALM        to Icons.Rounded.SelfImprovement,
                        )
                    }
                    val activePlaylistIcon = playlistFilters.firstOrNull { it.first == vm.ratingFilter }?.second
                    var playlistMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { playlistMenuExpanded = true }) {
                            Icon(
                                imageVector = activePlaylistIcon ?: Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = "Playlist",
                                tint = if (vm.ratingFilter != RatingFilter.ALL)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        }
                        DropdownMenu(expanded = playlistMenuExpanded, onDismissRequest = { playlistMenuExpanded = false }) {
                            playlistFilters.forEach { (filter, icon) ->
                                val active = vm.ratingFilter == filter
                                DropdownMenuItem(
                                    text = { Text(filter.label) },
                                    leadingIcon = {
                                        Icon(icon, contentDescription = null,
                                            tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                    },
                                    onClick = {
                                        playlistMenuExpanded = false
                                        vm.ratingFilter = if (active) RatingFilter.ALL else filter
                                        vm.appSettings.lastRatingFilter = vm.ratingFilter.name
                                    }
                                )
                            }
                        }
                    }

                    val currentInfo = currentRelPath?.let { vm.songInfoMap[it] }
                    val hasRateOrDance = (currentInfo?.optInt("rate", 0) ?: 0) > 0 ||
                        currentInfo?.opt("dance").let { it == true || it == 1 || it?.toString() == "1" } ||
                        currentInfo?.opt("calm").let  { it == true || it == 1 || it?.toString() == "1" }
                    IconButton(onClick = { showRateDanceDialog = true }) {
                        Icon(Icons.Rounded.Tune, contentDescription = "Apreciere și dans",
                            tint = if (hasRateOrDance) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { showTrackInfoDialog = true }) {
                        Icon(Icons.Rounded.Info, contentDescription = "Detalii cântec curent",
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { showQuickPanel = !showQuickPanel }) {
                        Icon(
                            imageVector = if (showQuickPanel) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = "Panou rapid",
                            tint = if (showQuickPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.deleteCurrentTrack() }) {
                        Icon(Icons.Rounded.DeleteForever, contentDescription = "Șterge cântecul curent",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }

                if (showRateDanceDialog && currentRelPath != null && currentSongId != null) {
                    val info = vm.songInfoMap[currentRelPath]
                    val rate = info?.optInt("rate", 0) ?: 0
                    val dance = info?.opt("dance").let { it == true || it == 1 || it?.toString() == "1" }
                    val calm  = info?.opt("calm").let  { it == true || it == 1 || it?.toString() == "1" }
                    AlertDialog(
                        onDismissRequest = { showRateDanceDialog = false },
                        title = { Text("Apreciere — cântec curent") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                listOf(
                                    0 to "Fără apreciere", 1 to "1", 2 to "2", 3 to "3",
                                    4 to "4 — Top personal", 5 to "5 — Top public",
                                ).forEach { (value, label) ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = rate == value,
                                                onClick = { vm.updateRateDance(currentSongId, currentRelPath, value, dance, calm) }
                                            )
                                            .padding(vertical = 2.dp)
                                    ) {
                                        RadioButton(selected = rate == value, onClick = null)
                                        Text(label, modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    Checkbox(checked = dance,
                                        onCheckedChange = { vm.updateRateDance(currentSongId, currentRelPath, rate, it, calm) })
                                    Text("Muzică dans", modifier = Modifier.padding(start = 8.dp))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    Checkbox(checked = calm,
                                        onCheckedChange = { vm.updateRateDance(currentSongId, currentRelPath, rate, dance, it) })
                                    Text("Liniștit", modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showRateDanceDialog = false }) { Text("Închide") }
                        }
                    )
                }

                if (!isSplitMode) {
                    PlaylistView(
                        items = vm.playlistItems,
                        currentIndex = vm.currentTrackIndex,
                        onItemClick = { index -> vm.player.jumpToItem(index); vm.player.play() },
                        filterQuery = vm.filterQuery,
                        filterInverted = vm.filterInverted,
                        songInfoByUrl = songInfoByUrl,
                        allowedIndices = ratingFilterSet,
                        manualNextIndex = if (vm.playlistMode == PlaylistMode.MANUAL) vm.manualNextIndex else null,
                        onItemLongClick = if (vm.playlistMode == PlaylistMode.MANUAL) { index ->
                            vm.manualNextIndex = if (vm.manualNextIndex == index) null else index
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (trackLabel.isNotEmpty()) {
                            Text(text = trackLabel, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, end = 6.dp))
                        }
                        OutlinedTextField(
                            value = vm.filterQuery,
                            onValueChange = { vm.filterQuery = it; vm.appSettings.filterQuery = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Filtrează...", style = MaterialTheme.typography.bodyMedium) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        IconButton(onClick = {
                            vm.filterInverted = !vm.filterInverted
                            vm.appSettings.filterInverted = vm.filterInverted
                        }) {
                            Icon(
                                imageVector = if (vm.filterInverted) Icons.Rounded.FilterListOff else Icons.Rounded.FilterList,
                                contentDescription = null,
                                tint = if (vm.filterInverted) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (showQuickPanel && currentRelPath != null && currentSongId != null) {
                        val qInfo = vm.songInfoMap[currentRelPath]
                        val qRate  = qInfo?.optInt("rate", 0) ?: 0
                        val qDance = qInfo?.opt("dance").let { it == true || it == 1 || it?.toString() == "1" }
                        val qCalm  = qInfo?.opt("calm").let  { it == true || it == 1 || it?.toString() == "1" }
                        val activeColor = MaterialTheme.colorScheme.error
                        val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { vm.updateRateDance(currentSongId, currentRelPath, if (qRate == 3) 0 else 3, qDance, qCalm) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Star, contentDescription = "Rating 3",
                                        tint = if (qRate == 3) activeColor else inactiveColor)
                                    Text("3", style = MaterialTheme.typography.labelMedium,
                                        color = if (qRate == 3) activeColor else inactiveColor)
                                }
                            }
                            IconButton(onClick = { vm.updateRateDance(currentSongId, currentRelPath, if (qRate == 4) 0 else 4, qDance, qCalm) }) {
                                Icon(Icons.Rounded.Person, contentDescription = "Top personal (4)",
                                    tint = if (qRate == 4) activeColor else inactiveColor)
                            }
                            IconButton(onClick = { vm.updateRateDance(currentSongId, currentRelPath, if (qRate == 5) 0 else 5, qDance, qCalm) }) {
                                Icon(Icons.Rounded.Public, contentDescription = "Top public (5)",
                                    tint = if (qRate == 5) activeColor else inactiveColor)
                            }
                            IconButton(onClick = { vm.updateRateDance(currentSongId, currentRelPath, qRate, !qDance, qCalm) }) {
                                Icon(Icons.Rounded.EmojiPeople, contentDescription = "Muzică dans",
                                    tint = if (qDance) activeColor else inactiveColor)
                            }
                            IconButton(onClick = { vm.updateRateDance(currentSongId, currentRelPath, qRate, qDance, !qCalm) }) {
                                Icon(Icons.Rounded.SelfImprovement, contentDescription = "Liniștit",
                                    tint = if (qCalm) activeColor else inactiveColor)
                            }
                        }
                    }
                }

                TrackDisplay(
                    title = title, artist = artist, artwork = artwork,
                    position = position, duration = duration, isLive = isLive,
                    onSeek = { vm.player.seek(it, TimeUnit.MILLISECONDS) },
                    modifier = Modifier.padding(top = 4.dp)
                )
                PlayerControls(
                    onPrevious = { vm.previousFiltered() },
                    onNext = { vm.nextFiltered() },
                    isPaused = playerState.value != AudioPlayerState.PLAYING,
                    onPlayPause = {
                        if (vm.player.playerState == AudioPlayerState.PLAYING) vm.player.pause()
                        else vm.player.play()
                    },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)
                )
            }
        }

        if (showTrackInfoDialog) {
            val currentAudioUrl = vm.playlistItems.getOrNull(vm.currentTrackIndex)?.audioUrl
            if (currentAudioUrl != null) {
                TrackInfoDialog(
                    audioUrl = currentAudioUrl,
                    songInfo = songInfoByUrl[currentAudioUrl],
                    onDismiss = { showTrackInfoDialog = false }
                )
            }
        }

        LaunchedEffect(Unit) {
            while (true) {
                delay(500L)
                position = vm.player.position
                duration = vm.player.duration
                if (vm.player.duration > 0L && duration == 0L) duration = vm.player.duration
            }
        }
    }
}

// -------------------------------------------------------------------------
// Bara de status sincronizare
// -------------------------------------------------------------------------
@Composable
fun SyncStatusBar(modifier: Modifier = Modifier, syncState: SyncState) {
    when (syncState) {
        is SyncState.Syncing -> {
            val progress = syncState.current.toFloat() / syncState.total.toFloat()
            Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    text = "Sincronizare: ${syncState.current}/${syncState.total} — ${syncState.currentFile}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1
                )
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        is SyncState.Error -> {
            Box(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart) {
                Text(text = "Eroare sincronizare: ${syncState.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, maxLines = 2)
            }
        }
        else -> {}
    }
}

// -------------------------------------------------------------------------
// Playlist
// -------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistView(
    items: List<AudioItem>,
    currentIndex: Int,
    onItemClick: (Int) -> Unit,
    filterQuery: String,
    filterInverted: Boolean,
    songInfoByUrl: Map<String, JSONObject> = emptyMap(),
    allowedIndices: Set<Int>? = null,
    manualNextIndex: Int? = null,
    onItemLongClick: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sorted = remember(items, allowedIndices) {
        items.mapIndexed { index, item -> index to item }
            .filter { (idx, _) -> allowedIndices == null || idx in allowedIndices }
            .sortedWith(compareBy({ it.second.artist ?: "" }, { it.second.title ?: "" }))
    }

    val filtered = remember(sorted, filterQuery, filterInverted) {
        if (filterQuery.isBlank()) sorted
        else sorted.filter { (_, item) -> matchesTextFilter(item, filterQuery, filterInverted) }
    }

    val currentPosInFiltered = remember(filtered, currentIndex) {
        filtered.indexOfFirst { it.first == currentIndex }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(currentPosInFiltered) {
        if (currentPosInFiltered >= 0) listState.animateScrollToItem(currentPosInFiltered)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(filtered) { listPos, (playerIndex, item) ->
                val isPlaying = playerIndex == currentIndex
                val isManualNext = playerIndex == manualNextIndex
                val rowNumber = listPos + 1
                val itemColor = when {
                    isPlaying    -> MaterialTheme.colorScheme.onPrimaryContainer
                    isManualNext -> MaterialTheme.colorScheme.onTertiaryContainer
                    else         -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val info = songInfoByUrl[item.audioUrl]
                val dur = info?.optLong("duration", 0L)?.takeIf { it > 0 }
                val rate = info?.optInt("rate", 0)?.takeIf { it > 0 }
                val completeness: Float? = info?.optDouble("completeness", -1.0)
                    ?.takeIf { it >= 0 }?.toFloat()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(when {
                            isPlaying    -> MaterialTheme.colorScheme.primaryContainer
                            isManualNext -> MaterialTheme.colorScheme.tertiaryContainer
                            else         -> MaterialTheme.colorScheme.surface
                        })
                        .combinedClickable(
                            onClick = { onItemClick(playerIndex) },
                            onLongClick = { onItemLongClick?.invoke(playerIndex) }
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "$rowNumber", style = MaterialTheme.typography.labelSmall,
                        color = itemColor, modifier = Modifier.padding(end = 10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        if (!item.artist.isNullOrEmpty()) {
                            Text(text = item.artist!!, style = MaterialTheme.typography.labelSmall,
                                color = itemColor, maxLines = 1)
                        }
                        Text(text = item.title ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                isPlaying    -> MaterialTheme.colorScheme.onPrimaryContainer
                                isManualNext -> MaterialTheme.colorScheme.onTertiaryContainer
                                else         -> MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                        if (dur != null) {
                            Text(text = dur.toDurationString(), style = MaterialTheme.typography.labelSmall, color = itemColor)
                        }
                        if (rate != null || completeness != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                if (rate != null) {
                                    Text(text = "$rate", style = MaterialTheme.typography.labelSmall,
                                        color = itemColor, modifier = Modifier.padding(end = 3.dp))
                                }
                                if (completeness != null) {
                                    LinearProgressIndicator(
                                        progress = { completeness },
                                        modifier = Modifier.width(40.dp),
                                        color = when {
                                            completeness >= 0.7f -> MaterialTheme.colorScheme.primary
                                            completeness >= 0.4f -> MaterialTheme.colorScheme.tertiary
                                            else                 -> MaterialTheme.colorScheme.error
                                        },
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                }
                            }
                        }
                    }
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
            )
            TrackDisplay(title = title, artist = artist, artwork = artwork,
                position = position, duration = duration, isLive = isLive,
                onSeek = onSeek, modifier = Modifier.padding(top = 46.dp))
            Spacer(modifier = Modifier.weight(1f))
            PlayerControls(onPrevious = onPrevious, onNext = onNext, isPaused = isPaused,
                onPlayPause = onPlayPause, modifier = Modifier.fillMaxWidth().padding(bottom = 60.dp))
        }
    }
}

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ContentPreview() {
    BecashPlayerTheme {
        MainScreen(title = "Title", artist = "Artist", artwork = "",
            position = 1000, duration = 6000, isLive = false, isPaused = true)
    }
}
