package com.minamikune.billiards.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.minamikune.billiards.R
import com.minamikune.billiards.physics.PhysicsEvent
import com.minamikune.billiards.util.CooldownGate
import kotlin.math.max
import kotlin.math.min

/** Small, pre-decoded sound bank. Impact volume/rate follows the simulated impulse. */
class SoundBank(context: Context, maxStreams: Int = 10) {
    var enabled: Boolean = true

    private val pool = SoundPool.Builder()
        .setMaxStreams(maxStreams.coerceIn(4, 24))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val soundIds = mapOf(
        SoundKind.CUE to load(context, R.raw.cue),
        SoundKind.BALL_LIGHT to load(context, R.raw.ball_light),
        SoundKind.BALL_HARD to load(context, R.raw.ball_hard),
        SoundKind.CUSHION to load(context, R.raw.cushion),
        SoundKind.POCKET to load(context, R.raw.pocket),
        SoundKind.FOUL to load(context, R.raw.foul),
        SoundKind.WIN to load(context, R.raw.win),
        SoundKind.LOSE to load(context, R.raw.lose),
        SoundKind.UI to load(context, R.raw.ui),
    )

    private val gate = CooldownGate()

    private fun load(context: Context, res: Int): Int = pool.load(context, res, 1)

    fun play(event: PhysicsEvent) {
        if (!enabled) return
        val cue = SoundCues.forEvent(event) ?: return
        playCue(cue)
    }

    fun playUi() {
        if (!enabled) return
        playCue(SoundCues.ui())
    }

    private fun playCue(cue: SoundCue) {
        val id = soundIds[cue.kind] ?: return
        if (id == 0) return
        if (!gate.tryAcquire(cue.kind.name, cue.cooldownMs)) return
        val volume = max(0f, min(1f, cue.volume))
        pool.play(id, volume, volume, 1, 0, cue.rate)
    }

    fun release() {
        pool.release()
    }
}
