package com.knightdx.glassestunes

import android.content.Context

object Prefs {
    const val TAP_GESTURE = "tap_gesture"
    const val PREFER_SAMSUNG = "prefer_samsung"
    const val SPEAK_REPLIES = "speak_replies"

    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun tapGesture(c: Context) = prefs(c).getBoolean(TAP_GESTURE, true)
    fun preferSamsung(c: Context) = prefs(c).getBoolean(PREFER_SAMSUNG, true)
    fun speakReplies(c: Context) = prefs(c).getBoolean(SPEAK_REPLIES, true)

    fun get(c: Context, key: String) = prefs(c).getBoolean(key, true)
    fun set(c: Context, key: String, value: Boolean) = prefs(c).edit().putBoolean(key, value).apply()
}
