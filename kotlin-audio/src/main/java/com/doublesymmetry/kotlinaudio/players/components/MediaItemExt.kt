package com.doublesymmetry.kotlinaudio.players.components

import androidx.media3.common.MediaItem
import com.doublesymmetry.kotlinaudio.models.AudioItemHolder

fun MediaItem.getAudioItemHolder(): AudioItemHolder? = AudioItemRegistry.get(mediaId)
