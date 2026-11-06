package com.minamikune.billiards.data

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("billiards", Context.MODE_PRIVATE)

    var soundEnabled: Boolean
        get() = p.getBoolean("sound", true)
        set(value) = p.edit().putBoolean("sound", value).apply()

    var hapticsEnabled: Boolean
        get() = p.getBoolean("haptics", true)
        set(value) = p.edit().putBoolean("haptics", value).apply()

    /** 0.35 .. 1.0; multiplied into every vibration amplitude. */
    var hapticStrength: Float
        get() = p.getFloat("haptic_strength", 0.8f)
        set(value) = p.edit().putFloat("haptic_strength", value.coerceIn(0.2f, 1f)).apply()

    /** 0 = automatic, 1 = high, 2 = battery saver. */
    var quality: Int
        get() = p.getInt("quality", 0)
        set(value) = p.edit().putInt("quality", value.coerceIn(0, 2)).apply()

    var gamesPlayed: Int
        get() = p.getInt("games", 0)
        set(value) = p.edit().putInt("games", value).apply()

    var wins: Int
        get() = p.getInt("wins", 0)
        set(value) = p.edit().putInt("wins", value).apply()
}
