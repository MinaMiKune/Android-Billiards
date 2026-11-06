package com.minamikune.billiards.platform

import android.content.Context
import com.minamikune.billiards.audio.SoundBank
import com.minamikune.billiards.resources.FontSet
import com.minamikune.billiards.resources.ResourceManager

/**
 * Resources owned by one Activity. [close] is idempotent and is called from
 * `MainActivity.onDestroy`, which covers activity replacement as well as finish.
 */
class ActivityResources(
    context: Context,
    val config: GameConfig,
) : AutoCloseable {
    private val registry = ResourceManager()

    val sound: SoundBank = registry.get(KEY_SOUND) {
        SoundBank(context, config.audioStreams)
    }

    val fonts: FontSet = registry.get(KEY_FONTS) { FontSet() }

    override fun close() {
        registry.close()
    }

    private companion object {
        const val KEY_SOUND = "audio.soundbank"
        const val KEY_FONTS = "ui.fonts"
    }
}
