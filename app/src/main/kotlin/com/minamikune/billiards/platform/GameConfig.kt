package com.minamikune.billiards.platform

import android.content.Context
import java.util.Properties

/**
 * Small, versioned runtime configuration read from `assets/game_config.properties`.
 *
 * The values are deliberately limited to presentation/performance knobs. Physics
 * correctness stays in code so a malformed asset can never change the simulation
 * contract. Every value is clamped and missing/invalid assets fall back to [DEFAULT].
 */
data class GameConfig(
    val version: Int,
    val splashMinMillis: Long,
    val splashFadeMillis: Long,
    val snapshotIntervalSeconds: Double,
    val highQualityMemoryClass: Int,
    val audioStreams: Int,
) {
    companion object {
        const val ASSET_NAME = "game_config.properties"

        val DEFAULT = GameConfig(
            version = 1,
            splashMinMillis = 850L,
            splashFadeMillis = 220L,
            snapshotIntervalSeconds = 0.075,
            highQualityMemoryClass = 256,
            audioStreams = 10,
        )

        /** Reads the packaged config; never throws on a missing or corrupt asset. */
        fun load(context: Context): GameConfig {
            return try {
                context.assets.open(ASSET_NAME).use { stream ->
                    val properties = Properties()
                    properties.load(stream)
                    parse(properties)
                }
            } catch (_: Exception) {
                DEFAULT
            }
        }

        /** Pure parser used by JVM tests. Unknown keys are ignored. */
        fun parse(properties: Properties): GameConfig {
            return GameConfig(
                version = properties.int("config.version", DEFAULT.version, 1, 1_000),
                splashMinMillis = properties.long("splash.minMillis", DEFAULT.splashMinMillis, 200L, 5_000L),
                splashFadeMillis = properties.long("splash.fadeMillis", DEFAULT.splashFadeMillis, 0L, 1_000L),
                snapshotIntervalSeconds = properties.double(
                    "loop.snapshotIntervalSeconds",
                    DEFAULT.snapshotIntervalSeconds,
                    0.033,
                    0.5,
                ),
                highQualityMemoryClass = properties.int(
                    "quality.highMemoryClass",
                    DEFAULT.highQualityMemoryClass,
                    64,
                    2_048,
                ),
                audioStreams = properties.int("audio.maxStreams", DEFAULT.audioStreams, 4, 24),
            )
        }

        private fun Properties.int(key: String, fallback: Int, min: Int, max: Int): Int {
            val raw = getProperty(key)?.trim()?.toIntOrNull() ?: return fallback
            return raw.coerceIn(min, max)
        }

        private fun Properties.long(key: String, fallback: Long, min: Long, max: Long): Long {
            val raw = getProperty(key)?.trim()?.toLongOrNull() ?: return fallback
            return raw.coerceIn(min, max)
        }

        private fun Properties.double(key: String, fallback: Double, min: Double, max: Double): Double {
            val raw = getProperty(key)?.trim()?.toDoubleOrNull() ?: return fallback
            if (!raw.isFinite()) return fallback
            return raw.coerceIn(min, max)
        }
    }
}
