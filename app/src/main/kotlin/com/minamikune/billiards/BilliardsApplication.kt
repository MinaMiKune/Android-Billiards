package com.minamikune.billiards

import android.app.Application
import android.content.res.Configuration
import android.util.Log
import com.minamikune.billiards.platform.AppContainer

/**
 * Process entry point. Initializes only process-wide state here; GL, audio and
 * view resources belong to an Activity and are created/released by [MainActivity].
 */
class BilliardsApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Log.i(TAG, "started configVersion=${container.config.version}")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "configuration ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The current resource set is bounded (nine short sounds and procedural GL
        // data), so there is no bitmap cache to evict here. Keep the callback as the
        // single hook for future trimmed caches and record the event for diagnostics.
        Log.d(TAG, "onTrimMemory=$level")
    }

    companion object {
        private const val TAG = "BilliardsApp"
    }
}
