package com.knightdx.glassestunes

import android.content.Context

object Prefs {
    const val TAP_GESTURE = "tap_gesture"
    const val PREFER_SAMSUNG = "prefer_samsung"
    const val SPEAK_REPLIES = "speak_replies"
    const val AUTO_START = "auto_start"
    const val JARVIS = "jarvis"
    const val JARVIS_IDLE_ONLY = "jarvis_idle_only"
    const val ANNOUNCE_MESSAGES = "announce_messages"

    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun tapGesture(c: Context) = prefs(c).getBoolean(TAP_GESTURE, true)
    fun preferSamsung(c: Context) = prefs(c).getBoolean(PREFER_SAMSUNG, true)
    fun speakReplies(c: Context) = prefs(c).getBoolean(SPEAK_REPLIES, true)
    fun autoStart(c: Context) = prefs(c).getBoolean(AUTO_START, true)
    fun jarvis(c: Context) = prefs(c).getBoolean(JARVIS, true)
    fun jarvisOnlyWhenIdle(c: Context) = prefs(c).getBoolean(JARVIS_IDLE_ONLY, false)
    fun announceMessages(c: Context) = prefs(c).getBoolean(ANNOUNCE_MESSAGES, false)

    fun geminiKey(c: Context): String = prefs(c).getString("gemini_key", "").orEmpty()
    fun setGeminiKey(c: Context, key: String) = prefs(c).edit().putString("gemini_key", key.trim()).remove("gemini_route").apply()
    fun geminiRoute(c: Context): String = prefs(c).getString("gemini_route", "").orEmpty()
    fun setGeminiRoute(c: Context, route: String) = prefs(c).edit().putString("gemini_route", route).apply()
    fun homeCity(c: Context): String = prefs(c).getString("home_city", "").orEmpty()
    fun setHomeCity(c: Context, city: String) = prefs(c).edit().putString("home_city", city.trim()).apply()

    fun get(c: Context, key: String) = prefs(c).getBoolean(key, key != JARVIS_IDLE_ONLY && key != ANNOUNCE_MESSAGES)
    fun set(c: Context, key: String, value: Boolean) = prefs(c).edit().putBoolean(key, value).apply()
}
