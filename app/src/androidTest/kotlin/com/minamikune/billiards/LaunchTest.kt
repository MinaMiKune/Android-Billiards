package com.minamikune.billiards

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @Test
    fun appContextIsAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(context)
        val app = context.applicationContext as BilliardsApplication
        val config = app.container.config
        assertEquals(1, config.version)
        assertTrue(config.audioStreams in 4..24)
        assertTrue(config.snapshotIntervalSeconds in 0.033..0.5)
    }
}
