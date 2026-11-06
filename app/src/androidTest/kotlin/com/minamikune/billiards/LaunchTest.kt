package com.minamikune.billiards

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @Test
    fun appContextIsAvailable() {
        assertNotNull(InstrumentationRegistry.getInstrumentation().targetContext)
    }
}
