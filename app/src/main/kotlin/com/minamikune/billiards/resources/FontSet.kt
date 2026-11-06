package com.minamikune.billiards.resources

import android.graphics.Typeface

/**
 * Typeface set shared by every Canvas view in one Activity. Creating the same
 * family/style on every frame or view is unnecessary; the Android typeface cache
 * already deduplicates the native object, but holding one set also keeps sizes and
 * weights consistent across menu, HUD and splash.
 */
class FontSet {
    val body: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val label: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val display: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
}
