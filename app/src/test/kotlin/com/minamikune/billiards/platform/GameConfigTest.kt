package com.minamikune.billiards.platform

import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameConfigTest {
    @Test
    fun emptyPropertiesUseDefaults() {
        assertEquals(GameConfig.DEFAULT, GameConfig.parse(Properties()))
    }

    @Test
    fun validPropertiesAreApplied() {
        val properties = Properties().apply {
            setProperty("config.version", "3")
            setProperty("splash.minMillis", "1200")
            setProperty("splash.fadeMillis", "300")
            setProperty("loop.snapshotIntervalSeconds", "0.12")
            setProperty("quality.highMemoryClass", "512")
            setProperty("audio.maxStreams", "12")
        }
        val config = GameConfig.parse(properties)
        assertEquals(3, config.version)
        assertEquals(1_200L, config.splashMinMillis)
        assertEquals(300L, config.splashFadeMillis)
        assertEquals(0.12, config.snapshotIntervalSeconds, 0.0001)
        assertEquals(512, config.highQualityMemoryClass)
        assertEquals(12, config.audioStreams)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val properties = Properties().apply {
            setProperty("splash.minMillis", "1")
            setProperty("splash.fadeMillis", "-5")
            setProperty("loop.snapshotIntervalSeconds", "9")
            setProperty("quality.highMemoryClass", "1")
            setProperty("audio.maxStreams", "99")
        }
        val config = GameConfig.parse(properties)
        assertEquals(200L, config.splashMinMillis)
        assertEquals(0L, config.splashFadeMillis)
        assertEquals(0.5, config.snapshotIntervalSeconds, 0.0001)
        assertEquals(64, config.highQualityMemoryClass)
        assertEquals(24, config.audioStreams)
    }

    @Test
    fun malformedAndNanValuesFallBackIndividually() {
        val properties = Properties().apply {
            setProperty("config.version", "not-a-number")
            setProperty("splash.minMillis", "also-wrong")
            setProperty("loop.snapshotIntervalSeconds", "NaN")
        }
        val config = GameConfig.parse(properties)
        assertEquals(GameConfig.DEFAULT.version, config.version)
        assertEquals(GameConfig.DEFAULT.splashMinMillis, config.splashMinMillis)
        assertEquals(GameConfig.DEFAULT.snapshotIntervalSeconds, config.snapshotIntervalSeconds, 0.0001)
        assertTrue(config.splashFadeMillis > 0)
    }
}
