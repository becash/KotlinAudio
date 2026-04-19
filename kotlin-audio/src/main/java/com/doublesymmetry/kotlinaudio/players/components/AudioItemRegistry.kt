package com.doublesymmetry.kotlinaudio.players.components

import com.doublesymmetry.kotlinaudio.models.AudioItemHolder
import java.util.concurrent.ConcurrentHashMap

internal object AudioItemRegistry {
    private val registry = ConcurrentHashMap<String, AudioItemHolder>()

    fun register(mediaId: String, holder: AudioItemHolder) {
        registry[mediaId] = holder
    }

    fun get(mediaId: String): AudioItemHolder? = registry[mediaId]

    fun remove(mediaId: String) = registry.remove(mediaId)

    fun clear() = registry.clear()
}
