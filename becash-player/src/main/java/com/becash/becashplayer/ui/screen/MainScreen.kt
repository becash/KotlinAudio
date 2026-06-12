package com.becash.becashplayer.ui.screen

import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.EmojiPeople
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.FilterListOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.becash.becashplayer.PlayerViewModel
import com.becash.becashplayer.PlaylistMode
import com.becash.becashplayer.RatingFilter
import com.becash.becashplayer.byArtistTitle
import com.becash.becashplayer.data.SyncState
import com.becash.becashplayer.ext.toDurationString
import com.becash.becashplayer.matchesTextFilter
import com.becash.becashplayer.optFlag
import com.becash.becashplayer.ui.component.PlayerControls
import com.becash.becashplayer.ui.component.TrackDisplay
import com.doublesymmetry.kotlinaudio.models.AudioItem
import com.doublesymmetry.kotlinaudio.models.AudioPlayerState
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

// Perechile mod/filtru → iconiță, refolosite de butoane și de meniurile lor
private val playlistModeIcons = listOf(
    PlaylistMode.SHUFFLE  to Icons.Rounded.Shuffle,
    PlaylistMode.NORMAL   to Icons.Rounded.Repeat,
    PlaylistMode.PLAY_ONE to Icons.Rounded.RepeatOne,
    PlaylistMode.MANUAL   to Icons.Rounded.TouchApp,
)
private val ratingFilterIcons = listOf(
    RatingFilter.NO_RATING   to Icons.Rounded.StarBorder,
    RatingFilter.WITH_RATING to Icons.Rounded.Star,
    RatingFilter.TOP         to Icons.Rounded.Person,
    RatingFilter.BEST        to Icons.Rounded.Public,
    RatingFilter.DANCE       to Icons.Rounded.EmojiPeople,
    RatingFilter.CALM        to Icons.Rounded.SelfImprovement,
)

@UnstableApi
@Composable
fun MainScreen(vm: PlayerViewModel, onCallPhone: (String) -> Unit) {
    val playerState = vm.player.event.stateChange.collectAsState(initial = AudioPlayerState.IDLE)

    // Ține ecranul aprins cât timp se redă muzică
    val view = LocalView.current
    DisposableEffect(playerState.value) {
        view.keepScreenOn = playerState.value == AudioPlayerState.PLAYING
        onDispose { view.keepScreenOn = false }
    }

    // Split-screen: fereastra e mult mai mică decât ecranul → ascundem playlistul
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenHeightPx = remember {
        val wm = context.getSystemService(WindowManager::class.java)!!
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

    val audioBaseDir = remember { vm.audioBaseDir }
    val songInfoByUrl = remember(vm.songInfoMap, audioBaseDir) {
        vm.songInfoMap.entries.associate { (songId, doc) ->
            Uri.fromFile(File("$audioBaseDir/${songId.trimStart('/')}")).toString() to doc
        }
    }

    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    var showTrackInfoDialog by remember { mutableStateOf(false) }
    var showRateDanceDialog by remember { mutableStateOf(false) }
    var showQuickPanel by remember { mutableStateOf(false) }

    val currentSongId = remember(vm.currentTrackIndex, vm.playlistItems) { vm.currentSongId }

    // Lista de afișare (sortată + filtrată) — calculată O DATĂ, folosită și de
    // eticheta "N / total" și de PlaylistView.
    val ratingFilterSet = remember(vm.ratingFilter, vm.songInfoMap, vm.playlistItems) {
        vm.ratingFilteredIndices
    }
    val displayEntries = remember(vm.playlistItems, ratingFilterSet, vm.filterQuery, vm.filterInverted) {
        val sorted = vm.playlistItems.mapIndexed { i, item -> i to item }
            .filter { (i, _) -> ratingFilterSet == null || i in ratingFilterSet }
            .sortedWith(compareBy(byArtistTitle) { it.second })
        if (vm.filterQuery.isBlank()) sorted
        else sorted.filter { (_, item) -> matchesTextFilter(item, vm.filterQuery, vm.filterInverted) }
    }
    val trackPos = displayEntries.indexOfFirst { it.first == vm.currentTrackIndex }
        .takeIf { it >= 0 }?.plus(1)
    val trackLabel = if (trackPos != null) "$trackPos / ${displayEntries.size}" else ""

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
                PlaylistModeMenu(vm)
                OverflowMenu(vm, onCallPhone)
                RatingFilterMenu(vm)

                val currentInfo = currentSongId?.let { vm.songInfoMap[it] }
                val hasRateOrDance = (currentInfo?.optInt("rate", 0) ?: 0) > 0 ||
                    currentInfo.optFlag("dance") || currentInfo.optFlag("calm")
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

            if (showRateDanceDialog && currentSongId != null) {
                RateDanceDialog(vm, currentSongId, onDismiss = { showRateDanceDialog = false })
            }

            if (!isSplitMode) {
                PlaylistView(
                    entries = displayEntries,
                    currentIndex = vm.currentTrackIndex,
                    onItemClick = { index -> vm.player.jumpToItem(index); vm.player.play() },
                    songInfoByUrl = songInfoByUrl,
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
                if (showQuickPanel && currentSongId != null) {
                    QuickRatePanel(vm, currentSongId)
                }
            }

            TrackDisplay(
                title = vm.player.currentItem?.title ?: "",
                artist = vm.player.currentItem?.artist ?: "",
                artwork = vm.player.currentItem?.artwork ?: "",
                position = position, duration = duration,
                isLive = vm.player.isCurrentMediaItemLive,
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
        }
    }
}

// -------------------------------------------------------------------------
// Meniurile din bara de acțiuni
// -------------------------------------------------------------------------
@UnstableApi
@Composable
private fun PlaylistModeMenu(vm: PlayerViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = playlistModeIcons.first { it.first == vm.playlistMode }.second,
                contentDescription = vm.playlistMode.label,
                tint = if (vm.playlistMode == PlaylistMode.NORMAL) MaterialTheme.colorScheme.onSurface
                       else MaterialTheme.colorScheme.primary
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            playlistModeIcons.forEach { (mode, icon) ->
                val active = vm.playlistMode == mode
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    leadingIcon = {
                        Icon(icon, contentDescription = null,
                            tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    },
                    onClick = { expanded = false; vm.applyPlaylistMode(mode) }
                )
            }
        }
    }
}

@UnstableApi
@Composable
private fun OverflowMenu(vm: PlayerViewModel, onCallPhone: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
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
            IconButton(onClick = { expanded = true }) {
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
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Sincronizează Nextcloud") },
                leadingIcon = { Icon(Icons.Rounded.Sync, contentDescription = null) },
                onClick = { expanded = false; vm.startSync() }
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
                onClick = { expanded = false; vm.toggleOfflineMode() }
            )
            if (vm.appSettings.bariera9.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("Bariera 9 — ${vm.appSettings.bariera9}") },
                    leadingIcon = { Text("9", style = MaterialTheme.typography.titleMedium) },
                    onClick = { expanded = false; onCallPhone(vm.appSettings.bariera9) }
                )
            }
            if (vm.appSettings.bariera10.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("Bariera 10 — ${vm.appSettings.bariera10}") },
                    leadingIcon = { Text("10", style = MaterialTheme.typography.titleMedium) },
                    onClick = { expanded = false; onCallPhone(vm.appSettings.bariera10) }
                )
            }
        }
    }
}

@UnstableApi
@Composable
private fun RatingFilterMenu(vm: PlayerViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val activeIcon = ratingFilterIcons.firstOrNull { it.first == vm.ratingFilter }?.second
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = activeIcon ?: Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = "Playlist",
                tint = if (vm.ratingFilter != RatingFilter.ALL)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ratingFilterIcons.forEach { (filter, icon) ->
                val active = vm.ratingFilter == filter
                DropdownMenuItem(
                    text = { Text(filter.label) },
                    leadingIcon = {
                        Icon(icon, contentDescription = null,
                            tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    },
                    onClick = {
                        expanded = false
                        vm.ratingFilter = if (active) RatingFilter.ALL else filter
                        vm.appSettings.lastRatingFilter = vm.ratingFilter.name
                    }
                )
            }
        }
    }
}

// -------------------------------------------------------------------------
// Apreciere / dans / liniștit pentru cântecul curent
// -------------------------------------------------------------------------
@UnstableApi
@Composable
private fun RateDanceDialog(vm: PlayerViewModel, songId: String, onDismiss: () -> Unit) {
    val info = vm.songInfoMap[songId]
    val rate = info?.optInt("rate", 0) ?: 0
    val dance = info.optFlag("dance")
    val calm = info.optFlag("calm")
    AlertDialog(
        onDismissRequest = onDismiss,
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
                                onClick = { vm.updateRateDance(songId, value, dance, calm) }
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
                        onCheckedChange = { vm.updateRateDance(songId, rate, it, calm) })
                    Text("Muzică dans", modifier = Modifier.padding(start = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Checkbox(checked = calm,
                        onCheckedChange = { vm.updateRateDance(songId, rate, dance, it) })
                    Text("Liniștit", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Închide") }
        }
    )
}

@UnstableApi
@Composable
private fun QuickRatePanel(vm: PlayerViewModel, songId: String) {
    val info = vm.songInfoMap[songId]
    val rate = info?.optInt("rate", 0) ?: 0
    val dance = info.optFlag("dance")
    val calm = info.optFlag("calm")
    val activeColor = MaterialTheme.colorScheme.error
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { vm.updateRateDance(songId, if (rate == 3) 0 else 3, dance, calm) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Star, contentDescription = "Rating 3",
                    tint = if (rate == 3) activeColor else inactiveColor)
                Text("3", style = MaterialTheme.typography.labelMedium,
                    color = if (rate == 3) activeColor else inactiveColor)
            }
        }
        IconButton(onClick = { vm.updateRateDance(songId, if (rate == 4) 0 else 4, dance, calm) }) {
            Icon(Icons.Rounded.Person, contentDescription = "Top personal (4)",
                tint = if (rate == 4) activeColor else inactiveColor)
        }
        IconButton(onClick = { vm.updateRateDance(songId, if (rate == 5) 0 else 5, dance, calm) }) {
            Icon(Icons.Rounded.Public, contentDescription = "Top public (5)",
                tint = if (rate == 5) activeColor else inactiveColor)
        }
        IconButton(onClick = { vm.updateRateDance(songId, rate, !dance, calm) }) {
            Icon(Icons.Rounded.EmojiPeople, contentDescription = "Muzică dans",
                tint = if (dance) activeColor else inactiveColor)
        }
        IconButton(onClick = { vm.updateRateDance(songId, rate, dance, !calm) }) {
            Icon(Icons.Rounded.SelfImprovement, contentDescription = "Liniștit",
                tint = if (calm) activeColor else inactiveColor)
        }
    }
}

// -------------------------------------------------------------------------
// Bara de status sincronizare
// -------------------------------------------------------------------------
@Composable
private fun SyncStatusBar(syncState: SyncState, modifier: Modifier = Modifier) {
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
// Playlist — primește lista deja sortată și filtrată (displayEntries)
// -------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistView(
    entries: List<Pair<Int, AudioItem>>,
    currentIndex: Int,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    songInfoByUrl: Map<String, JSONObject> = emptyMap(),
    manualNextIndex: Int? = null,
    onItemLongClick: ((Int) -> Unit)? = null,
) {
    val currentPosInList = remember(entries, currentIndex) {
        entries.indexOfFirst { it.first == currentIndex }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(currentPosInList) {
        if (currentPosInList >= 0) listState.animateScrollToItem(currentPosInList)
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        itemsIndexed(entries) { listPos, (playerIndex, item) ->
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
