package com.minamikune.billiards

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the real activity, which creates the GL surface, simulation thread and
 * menu overlay. This is the only test that exercises the full lifecycle wiring.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @Test
    fun activityLaunchesWithoutFinishing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("activity finished during startup", activity.isFinishing)
                assertNotNull(activity.window.decorView)
            }
        }
    }
}
