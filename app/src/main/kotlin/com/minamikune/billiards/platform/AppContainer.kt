package com.minamikune.billiards.platform

import android.content.Context
import com.minamikune.billiards.data.Prefs
import com.minamikune.billiards.data.SaveStore

/**
 * Process-scoped dependencies. These objects are cheap, stateless with respect to a
 * single Activity, and intentionally survive Activity recreation.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val prefs = Prefs(appContext)
    val saves = SaveStore(appContext)
    val config = GameConfig.load(appContext)

    /** Creates resources that must follow one Activity/EGL surface lifecycle. */
    fun createActivityResources(): ActivityResources =
        ActivityResources(appContext, config)
}
