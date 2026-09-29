package com.knightdx.glassestunes

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves crashes so they can be shared from the app, since there's no computer attached to read logs. */
class GlassesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
    }
}

object CrashReport {
    private const val FILE = "last_crash.txt"
    private const val CLEARED_AT = "crash_cleared_at"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                File(context.filesDir, FILE).writeText(
                    "Crash at ${now()} on thread \"${thread.name}\"\n" +
                        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n" +
                        trace
                )
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Records an error the app recovered from, so it still shows up in the shareable report. */
    fun recordProblem(context: Context, where: String, error: Throwable) {
        try {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
            File(context.filesDir, FILE).writeText(
                "Error at ${now()} in $where (the app kept running)\n" +
                    "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n" +
                    trace
            )
        } catch (ignored: Throwable) {
        }
    }

    /** A report to show and share, or null if nothing went wrong recently. */
    fun report(context: Context): String? {
        val parts = ArrayList<String>()
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.let { parts += it }
        if (Build.VERSION.SDK_INT >= 30) {
            val exits = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .filter { it.timestamp > clearedAt(context) }
                .filter {
                    it.reason == ApplicationExitInfo.REASON_CRASH || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                        it.reason == ApplicationExitInfo.REASON_ANR || it.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                }
            exits.forEach { e ->
                val kind = when (e.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                    ApplicationExitInfo.REASON_ANR -> "App not responding"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Startup failure"
                    else -> "Crash"
                }
                var entry = "$kind at ${time(e.timestamp)} (${e.processName}): ${e.description ?: ""}"
                if (e.reason == ApplicationExitInfo.REASON_ANR) {
                    try {
                        e.traceInputStream?.bufferedReader()?.use { r ->
                            entry += "\n" + r.lineSequence().take(80).joinToString("\n")
                        }
                    } catch (ignored: Exception) {
                    }
                }
                parts += entry
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n----\n\n")
    }

    private fun clearedAt(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getLong(CLEARED_AT, 0)

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putLong(CLEARED_AT, System.currentTimeMillis()).apply()
    }

    private fun now() = time(System.currentTimeMillis())
    private fun time(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
