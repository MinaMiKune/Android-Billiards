package com.minamikune.billiards.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.minamikune.billiards.R
import com.minamikune.billiards.physics.PhysicsEvent
import kotlin.math.max
import kotlin.math.min

/** Small, pre-decoded sound bank. Impact volume/rate follows the simulated impulse. */
class SoundBank(context: Context) {
    var enabled: Boolean = true

    private val pool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val cue = load(context, R.raw.cue)
    private val ballLight = load(context, R.raw.ball_light)
    private val ballHard = load(context, R.raw.ball_hard)
    private val cushion = load(context, R.raw.cushion)
    private val pocket = load(context, R.raw.pocket)
    private val foul = load(context, R.raw.foul)
    private val win = load(context, R.raw.win)
    private val lose = load(context, R.raw.lose)
    private val ui = load(context, R.raw.ui)
    private val lastPlayed = HashMap<Int, Long>()

    private fun load(context: Context, res: Int): Int = pool.load(context, res, 1)

    fun play(event: PhysicsEvent) {
        if (!enabled) return
        when (event) {
            is PhysicsEvent.BallHit -> {
                val speed = event.impactSpeed
                val heavy = speed > 1.35
                val id = if (heavy) ballHard else ballLight
                val volume = (0.16 + speed * 0.15).toFloat().coerceIn(0.12f, 0.95f)
                playRateLimited(id, volume, (0.94 + speed * 0.018).toFloat().coerceIn(0.9f, 1.16f), 28)
            }
            is PhysicsEvent.CushionHit -> {
                val v = (0.10 + event.impactSpeed * 0.12).toFloat().coerceIn(0.08f, 0.65f)
                playRateLimited(cushion, v, 1f, 55)
            }
            is PhysicsEvent.Pocketed -> playRateLimited(pocket, 0.85f, 1f, 90)
            is PhysicsEvent.CueStruck -> playRateLimited(cue, (0.35 + event.speed * 0.06).toFloat().coerceIn(0.3f, 0.95f), 1f, 40)
            PhysicsEvent.Foul -> playRateLimited(foul, 0.55f, 1f, 500)
            is PhysicsEvent.MatchWon -> playRateLimited(if (event.humanWon) win else lose, 0.85f, 1f, 900)
            PhysicsEvent.Settled -> Unit
        }
    }

    fun playUi() {
        if (!enabled) return
        playRateLimited(ui, 0.35f, 1f, 30)
    }

    private fun playRateLimited(id: Int, volume: Float, rate: Float, cooldownMs: Long) {
        if (id == 0) return
        val now = System.nanoTime() / 1_000_000
        val last = lastPlayed[id] ?: 0L
        if (now - last < cooldownMs) return
        lastPlayed[id] = now
        pool.play(id, max(0f, min(1f, volume)), max(0f, min(1f, volume)), 1, 0, rate)
    }

    fun release() {
        pool.release()
    }
}
