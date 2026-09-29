package com.knightdx.glassestunes

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

data class AppEntry(val label: String, val packageName: String)

/** Picks the installed app whose name best matches what was said. Pure, for unit tests. */
object AppMatcher {
    fun find(apps: List<AppEntry>, spoken: String): AppEntry? =
        apps.sortedBy { it.label.length } // on a tie, "Camera" beats "Camera Assistant"
            .maxByOrNull { LibraryMatcher.score(it.label, spoken) }
            ?.takeIf { LibraryMatcher.score(it.label, spoken) >= 60 }
}

/**
 * Opens other apps by voice. Android only lets a background app open another
 * app's screen if it holds "Display over other apps", so without that we post
 * a notification you can tap instead.
 */
class AppLauncher(private val context: Context) {

    fun canOpenFromBackground(): Boolean = Settings.canDrawOverlays(context)

    fun isLocked(): Boolean = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    fun installedApps(): List<AppEntry> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
    }

    fun find(spoken: String): AppEntry? = AppMatcher.find(installedApps(), spoken)

    fun open(app: AppEntry): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: return false
        return start(intent, app.label)
    }

    /** Starts the default assistant listening (Gemini, when it's your default digital assistant). */
    fun openAssistant(): Boolean {
        val voice = Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (start(voice, "Gemini")) return true
        val gemini = context.packageManager.getLaunchIntentForPackage(GEMINI_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        return start(gemini, "Gemini")
    }

    private fun start(intent: Intent, label: String): Boolean {
        if (!canOpenFromBackground()) {
            offerTap(intent, label)
            return true
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for $intent", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "not allowed to open $label", e)
            offerTap(intent, label)
            true
        }
    }

    private fun offerTap(intent: Intent, label: String) {
        val pending = PendingIntent.getActivity(
            context, label.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = android.app.Notification.Builder(context, GlassesService.CHANNEL)
            .setSmallIcon(R.drawable.ic_glasses)
            .setContentTitle("Open $label")
            .setContentText("Tap to open. Allow \"Display over other apps\" to open apps hands-free.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(OPEN_APP_NOTIFICATION, notification)
    }

    companion object {
        private const val TAG = "AppLauncher"
        private const val OPEN_APP_NOTIFICATION = 2
        const val GEMINI_PACKAGE = "com.google.android.apps.bard"
    }
}
