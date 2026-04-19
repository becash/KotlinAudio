package com.doublesymmetry.kotlinaudio.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerNotificationManager
import androidx.media3.ui.PlayerNotificationManager.CustomActionReceiver
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import com.doublesymmetry.kotlinaudio.R
import com.doublesymmetry.kotlinaudio.event.NotificationEventHolder
import com.doublesymmetry.kotlinaudio.event.PlayerEventHolder
import com.doublesymmetry.kotlinaudio.models.AudioItem
import com.doublesymmetry.kotlinaudio.models.MediaSessionCallback
import com.doublesymmetry.kotlinaudio.models.NotificationButton
import com.doublesymmetry.kotlinaudio.models.NotificationConfig
import com.doublesymmetry.kotlinaudio.models.NotificationState
import com.doublesymmetry.kotlinaudio.players.components.getAudioItemHolder
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders

@UnstableApi
class NotificationManager internal constructor(
    private val context: Context,
    private val player: Player,
    private val mediaSession: MediaSession,
    val event: NotificationEventHolder,
    val playerEventHolder: PlayerEventHolder
) : PlayerNotificationManager.NotificationListener {
    private var pendingIntent: PendingIntent? = null
    private val descriptionAdapter = object : PlayerNotificationManager.MediaDescriptionAdapter {
        override fun getCurrentContentTitle(player: Player): CharSequence {
            return getTitle() ?: ""
        }

        override fun createCurrentContentIntent(player: Player): PendingIntent? {
            return pendingIntent
        }

        override fun getCurrentContentText(player: Player): CharSequence? {
            return getArtist() ?: ""
        }

        override fun getCurrentSubText(player: Player): CharSequence? {
            return player.mediaMetadata.displayTitle
        }

        override fun getCurrentLargeIcon(
            player: Player,
            callback: PlayerNotificationManager.BitmapCallback,
        ): Bitmap? {
            val bitmap = getCachedArtworkBitmap()
            if (bitmap != null) {
                return bitmap
            }
            val artwork = getMediaItemArtworkUrl()
            val headers = getNetworkHeaders()
            val holder = player.currentMediaItem?.getAudioItemHolder()
            if (artwork != null && holder?.artworkBitmap == null) {
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(artwork)
                        .headers(headers)
                        .target { result ->
                            val resultBitmap = (result as BitmapDrawable).bitmap
                            holder?.artworkBitmap = resultBitmap
                            invalidate()
                        }
                        .build()
                )
            }
            return iconPlaceholder
        }
    }

    private var internalNotificationManager: PlayerNotificationManager? = null
    private val scope = MainScope()
    private val buttons = mutableSetOf<NotificationButton?>()
    private var invalidateThrottleCount = 0
    private var iconPlaceholder = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    private var notificationMetadataBitmap: Bitmap? = null
    private var notificationMetadataArtworkDisposable: Disposable? = null

    internal var overrideAudioItem: AudioItem? = null
        set(value) {
            notificationMetadataBitmap = null
            val headers = getNetworkHeaders()

            if (field != value) {
                if (value?.artwork != null) {
                    notificationMetadataArtworkDisposable?.dispose()
                    notificationMetadataArtworkDisposable = context.imageLoader.enqueue(
                        ImageRequest.Builder(context)
                            .data(value.artwork)
                            .headers(headers)
                            .target { result ->
                                notificationMetadataBitmap = (result as BitmapDrawable).bitmap
                                invalidate()
                            }
                            .build()
                    )
                }
            }

            field = value
            invalidate()
        }

    private fun getTitle(index: Int? = null): String? {
        val mediaItem = if (index == null) player.currentMediaItem else player.getMediaItemAt(index)
        val audioItem = mediaItem?.getAudioItemHolder()?.audioItem
        return overrideAudioItem?.title
            ?: mediaItem?.mediaMetadata?.title?.toString()
            ?: audioItem?.title
    }

    private fun getArtist(index: Int? = null): String? {
        val mediaItem = if (index == null) player.currentMediaItem else player.getMediaItemAt(index)
        val audioItem = mediaItem?.getAudioItemHolder()?.audioItem
        return overrideAudioItem?.artist
            ?: mediaItem?.mediaMetadata?.artist?.toString()
            ?: mediaItem?.mediaMetadata?.albumArtist?.toString()
            ?: audioItem?.artist
    }

    private fun getGenre(index: Int? = null): String? {
        val mediaItem = if (index == null) player.currentMediaItem else player.getMediaItemAt(index)
        return mediaItem?.mediaMetadata?.genre?.toString()
    }

    private fun getAlbumTitle(index: Int? = null): String? {
        val mediaItem = if (index == null) player.currentMediaItem else player.getMediaItemAt(index)
        return mediaItem?.mediaMetadata?.albumTitle?.toString()
            ?: mediaItem?.getAudioItemHolder()?.audioItem?.albumTitle
    }

    private fun getArtworkUrl(index: Int? = null): String? {
        return getMediaItemArtworkUrl(index)
    }

    private fun getMediaItemArtworkUrl(index: Int? = null): String? {
        val mediaItem = if (index == null) player.currentMediaItem else player.getMediaItemAt(index)
        return overrideAudioItem?.artwork
            ?: mediaItem?.mediaMetadata?.artworkUri?.toString()
            ?: mediaItem?.getAudioItemHolder()?.audioItem?.artwork
    }

    private fun getNetworkHeaders(): Headers {
        return player.currentMediaItem?.getAudioItemHolder()?.audioItem?.options?.headers?.toHeaders()
            ?: Headers.Builder().build()
    }

    private fun getCachedArtworkBitmap(index: Int? = null): Bitmap? {
        val mediaItem = if (index == null) player.currentMediaItem else player.getMediaItemAt(index)
        val isCurrent = index == null || index == player.currentMediaItemIndex
        val artworkData = player.mediaMetadata.artworkData

        return if (isCurrent && overrideAudioItem != null) {
            notificationMetadataBitmap
        } else if (isCurrent && artworkData != null) {
            BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
        } else {
            mediaItem?.getAudioItemHolder()?.artworkBitmap
        }
    }

    private fun getDuration(index: Int? = null): Long? {
        val mediaItem = if (index == null) player.currentMediaItem
            else player.getMediaItemAt(index)

        return if (player.isCurrentMediaItemDynamic || player.duration == C.TIME_UNSET) {
            overrideAudioItem?.duration ?: mediaItem?.getAudioItemHolder()?.audioItem?.duration ?: -1
        } else {
            overrideAudioItem?.duration ?: player.duration
        }
    }

    private fun getUserRating(index: Int? = null): RatingCompat? {
        val mediaItem = if (index == null) player.currentMediaItem
            else player.getMediaItemAt(index)
        return RatingCompat.fromRating(mediaItem?.mediaMetadata?.userRating)
    }

    var showPlayPauseButton = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUsePlayPauseActions(value)
            }
        }

    var showStopButton = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUseStopAction(value)
            }
        }

    var showForwardButton = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUseFastForwardAction(value)
            }
        }

    var showForwardButtonCompact = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUseFastForwardActionInCompactView(value)
            }
        }

    var showRewindButton = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUseRewindAction(value)
            }
        }

    var showRewindButtonCompact = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUseRewindActionInCompactView(value)
            }
        }

    var showNextButton = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUseNextAction(value)
            }
        }

    var showNextButtonCompact = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUseNextActionInCompactView(value)
            }
        }

    var showPreviousButton = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUsePreviousAction(value)
            }
        }

    var showPreviousButtonCompact = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUsePreviousActionInCompactView(value)
            }
        }

    var stopIcon: Int? = null
    var forwardIcon: Int? = null
    var rewindIcon: Int? = null

    private fun extractButtonIcons() {
        for (button in buttons) {
            when (button) {
                is NotificationButton.BACKWARD -> rewindIcon = button.icon ?: rewindIcon
                is NotificationButton.FORWARD  -> forwardIcon = button.icon ?: forwardIcon
                is NotificationButton.STOP     -> stopIcon = button.icon ?: stopIcon
                else -> {}
            }
        }
    }

    public fun overrideMetadata(item: AudioItem) {
        overrideAudioItem = item
    }

    public fun getMediaMetadataCompat(): MediaMetadataCompat {
        val currentItemMetadata = player.currentMediaItem?.mediaMetadata

        return MediaMetadataCompat.Builder().apply {
            getArtist()?.let { putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
            getTitle()?.let {
                putString(MediaMetadataCompat.METADATA_KEY_TITLE, it)
                putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, it)
            }
            currentItemMetadata?.subtitle?.let {
                putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, it.toString())
            }
            currentItemMetadata?.description?.let {
                putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, it.toString())
            }
            getAlbumTitle()?.let { putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it) }
            getGenre()?.let { putString(MediaMetadataCompat.METADATA_KEY_GENRE, it) }
            getDuration()?.let { putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it) }
            getArtworkUrl()?.let { putString(MediaMetadataCompat.METADATA_KEY_ART_URI, it) }
            getCachedArtworkBitmap()?.let {
                putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
            }
            getUserRating()?.let { putRating(MediaMetadataCompat.METADATA_KEY_RATING, it) }
        }.build()
    }

    private fun createNotificationAction(
        drawable: Int,
        action: String,
        instanceId: Int
    ): NotificationCompat.Action {
        val intent: Intent = Intent(action).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            instanceId,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )
        return NotificationCompat.Action.Builder(drawable, action, pendingIntent).build()
    }

    private fun handlePlayerAction(action: String) {
        when (action) {
            REWIND  -> playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.REWIND)
            FORWARD -> playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.FORWARD)
            STOP    -> playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.STOP)
        }
    }

    private val customActionReceiver = object : CustomActionReceiver {
        override fun createCustomActions(
            context: Context,
            instanceId: Int
        ): MutableMap<String, NotificationCompat.Action> {
            if (!needsCustomActionsToAddMissingButtons) return mutableMapOf()
            return mutableMapOf(
                REWIND  to createNotificationAction(rewindIcon  ?: DEFAULT_REWIND_ICON,   REWIND,   instanceId),
                FORWARD to createNotificationAction(forwardIcon ?: DEFAULT_FORWARD_ICON,  FORWARD,  instanceId),
                STOP    to createNotificationAction(stopIcon    ?: DEFAULT_STOP_ICON,     STOP,     instanceId),
            )
        }

        override fun getCustomActions(player: Player): List<String> {
            if (!needsCustomActionsToAddMissingButtons) return emptyList()
            return buttons.mapNotNull {
                when (it) {
                    is NotificationButton.BACKWARD -> REWIND
                    is NotificationButton.FORWARD  -> FORWARD
                    is NotificationButton.STOP     -> STOP
                    else -> null
                }
            }
        }

        override fun onCustomAction(player: Player, action: String, intent: Intent) {
            handlePlayerAction(action)
        }
    }

    fun invalidate() {
        if (invalidateThrottleCount++ == 0) {
            scope.launch {
                internalNotificationManager?.invalidate()
                delay(300)
                val wasThrottled = invalidateThrottleCount > 1
                invalidateThrottleCount = 0
                if (wasThrottled) invalidate()
            }
        }
    }

    fun createNotification(config: NotificationConfig) = scope.launch {
        if (isNotificationButtonsChanged(config.buttons)) {
            hideNotification()
        }

        buttons.apply {
            clear()
            addAll(config.buttons)
        }

        stopIcon = null
        forwardIcon = null
        rewindIcon = null

        extractButtonIcons()

        pendingIntent = config.pendingIntent
        showPlayPauseButton = false
        showForwardButton = false
        showRewindButton = false
        showNextButton = false
        showPreviousButton = false
        showStopButton = false

        if (internalNotificationManager == null) {
            internalNotificationManager =
                PlayerNotificationManager.Builder(context, NOTIFICATION_ID, CHANNEL_ID)
                    .apply {
                        setChannelNameResourceId(R.string.playback_channel_name)
                        setMediaDescriptionAdapter(descriptionAdapter)
                        setCustomActionReceiver(customActionReceiver)
                        setNotificationListener(this@NotificationManager)

                        for (button in buttons) {
                            if (button == null) continue
                            when (button) {
                                is NotificationButton.PLAY_PAUSE -> {
                                    button.playIcon?.let { setPlayActionIconResourceId(it) }
                                    button.pauseIcon?.let { setPauseActionIconResourceId(it) }
                                }
                                is NotificationButton.STOP     -> button.icon?.let { setStopActionIconResourceId(it) }
                                is NotificationButton.FORWARD  -> button.icon?.let { setFastForwardActionIconResourceId(it) }
                                is NotificationButton.BACKWARD -> button.icon?.let { setRewindActionIconResourceId(it) }
                                is NotificationButton.NEXT     -> button.icon?.let { setNextActionIconResourceId(it) }
                                is NotificationButton.PREVIOUS -> button.icon?.let { setPreviousActionIconResourceId(it) }
                                else -> {}
                            }
                        }
                    }.build().apply {
                        setMediaSessionToken(mediaSession.sessionCompatToken)
                        setPlayer(player)
                    }
        }
        setupInternalNotificationManager(config)
    }

    private fun isNotificationButtonsChanged(newButtons: List<NotificationButton>): Boolean {
        val currentByType = buttons.filterNotNull().associateBy { it::class }
        return newButtons.any { newButton ->
            when (newButton) {
                is NotificationButton.PLAY_PAUSE ->
                    (currentByType[NotificationButton.PLAY_PAUSE::class] as? NotificationButton.PLAY_PAUSE).let { cur ->
                        newButton.pauseIcon != cur?.pauseIcon || newButton.playIcon != cur?.playIcon
                    }
                is NotificationButton.STOP ->
                    (currentByType[NotificationButton.STOP::class] as? NotificationButton.STOP).let { cur ->
                        newButton.icon != cur?.icon
                    }
                is NotificationButton.FORWARD ->
                    (currentByType[NotificationButton.FORWARD::class] as? NotificationButton.FORWARD).let { cur ->
                        newButton.icon != cur?.icon
                    }
                is NotificationButton.BACKWARD ->
                    (currentByType[NotificationButton.BACKWARD::class] as? NotificationButton.BACKWARD).let { cur ->
                        newButton.icon != cur?.icon
                    }
                is NotificationButton.NEXT ->
                    (currentByType[NotificationButton.NEXT::class] as? NotificationButton.NEXT).let { cur ->
                        newButton.icon != cur?.icon
                    }
                is NotificationButton.PREVIOUS ->
                    (currentByType[NotificationButton.PREVIOUS::class] as? NotificationButton.PREVIOUS).let { cur ->
                        newButton.icon != cur?.icon
                    }
                else -> false
            }
        }
    }

    private fun setupInternalNotificationManager(config: NotificationConfig) {
        internalNotificationManager?.run {
            setColor(config.accentColor ?: Color.TRANSPARENT)
            config.smallIcon?.let { setSmallIcon(it) }
            for (button in buttons) {
                if (button == null) continue
                when (button) {
                    is NotificationButton.PLAY_PAUSE -> showPlayPauseButton = true
                    is NotificationButton.STOP       -> showStopButton = true
                    is NotificationButton.FORWARD    -> { showForwardButton = true; showForwardButtonCompact = button.isCompact }
                    is NotificationButton.BACKWARD   -> { showRewindButton = true;  showRewindButtonCompact  = button.isCompact }
                    is NotificationButton.NEXT       -> { showNextButton = true;    showNextButtonCompact    = button.isCompact }
                    is NotificationButton.PREVIOUS   -> { showPreviousButton = true; showPreviousButtonCompact = button.isCompact }
                    else -> {}
                }
            }
        }
    }

    fun hideNotification() {
        internalNotificationManager?.setPlayer(null)
        internalNotificationManager = null
        invalidate()
    }

    override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
        scope.launch {
            event.updateNotificationState(NotificationState.POSTED(notificationId, notification, ongoing))
        }
    }

    override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
        scope.launch {
            event.updateNotificationState(NotificationState.CANCELLED(notificationId))
        }
    }

    internal fun destroy() = scope.launch {
        internalNotificationManager?.setPlayer(null)
    }

    companion object {
        private val needsCustomActionsToAddMissingButtons = Build.VERSION.SDK_INT >= 33
        private const val REWIND = "rewind"
        private const val FORWARD = "forward"
        private const val STOP = "stop"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "kotlin_audio_player"
        private val DEFAULT_STOP_ICON    = androidx.media3.ui.R.drawable.exo_notification_stop
        private val DEFAULT_REWIND_ICON  = androidx.media3.ui.R.drawable.exo_notification_rewind
        private val DEFAULT_FORWARD_ICON = androidx.media3.ui.R.drawable.exo_notification_fastforward
    }
}
