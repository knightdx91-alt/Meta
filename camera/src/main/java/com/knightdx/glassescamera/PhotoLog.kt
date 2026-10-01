package com.knightdx.glassescamera

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The last few photo attempts and how they went, shown in the app so problems can be diagnosed. */
object PhotoLog {
    private const val PREFS = "photo_log"
    private const val KEY = "entries"
    private const val MAX = 15

    @Synchronized
    fun add(context: Context, source: String, result: GlassesCamera.Result) {
        val time = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date())
        val outcome = when (result) {
            is GlassesCamera.Result.Saved -> "✅ saved"
            is GlassesCamera.Result.Failed -> "⚠️ ${result.reason}"
        }
        val line = "$time · $source · $outcome".replace("\n", " ")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = (listOf(line) + prefs.getString(KEY, "").orEmpty().split("\n").filter { it.isNotBlank() }).take(MAX)
        prefs.edit().putString(KEY, entries.joinToString("\n")).apply()
    }

    fun entries(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty().split("\n").filter { it.isNotBlank() }
}
