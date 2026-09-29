package com.knightdx.glassestunes

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager

/** Recognizes Meta glasses by their Bluetooth name. Pure, for unit tests. */
object GlassesNames {
    private val pattern = Regex("ray[- ]?ban|rayban|\\bmeta\\b|oakley", RegexOption.IGNORE_CASE)
    fun isGlasses(name: String?): Boolean = name != null && pattern.containsMatchIn(name)
}

/**
 * Starts the connector when the glasses connect over Bluetooth and stops it
 * when they disconnect.
 */
class GlassesConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.autoStart(context)) return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val name = try {
            device?.name
        } catch (e: SecurityException) {
            null // Nearby devices permission not granted
        }
        if (!GlassesNames.isGlasses(name)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.i(TAG, "$name connected")
                if (GlassesService.instance == null) AutoStart.start(context, name ?: "Glasses")
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i(TAG, "$name disconnected")
                GlassesService.instance?.stopSelf()
            }
        }
    }

    companion object {
        private const val TAG = "GlassesConnection"
    }
}

object AutoStart {
    private const val NOTIFICATION = 3

    /**
     * The connector needs microphone access, which Android only grants to a
     * service started while one of our screens is showing. "Display over other
     * apps" lets us flash an invisible screen to do that from the background;
     * without it we post a one-tap notification.
     */
    fun start(context: Context, glassesName: String) {
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(
                    Intent(context, StartConnectorActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    )
                )
                return
            } catch (e: RuntimeException) {
                Log.w("AutoStart", "could not start from background", e)
            }
        }
        tapToStart(context, "$glassesName connected")
    }

    /** Android stopped the connector in the background and won't let it restart by itself. */
    fun promptRestart(context: Context) = tapToStart(context, "Glasses Tunes was stopped by Android")

    private fun tapToStart(context: Context, title: String) {
        try {
            GlassesService.createChannel(context)
            val tap = PendingIntent.getActivity(
                context, 0, Intent(context, StartConnectorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            context.getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION,
                android.app.Notification.Builder(context, GlassesService.CHANNEL)
                    .setSmallIcon(R.drawable.ic_glasses)
                    .setContentTitle(title)
                    .setContentText("Tap to turn on voice control")
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (e: RuntimeException) {
            Log.w("AutoStart", "couldn't post the start prompt", e)
        }
    }

    fun clearPrompt(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }
}

/** Invisible screen that starts the connector (so it gets mic access) and closes. Works over the lock screen. */
class StartConnectorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        AutoStart.clearPrompt(this)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            GlassesService.start(this)
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
