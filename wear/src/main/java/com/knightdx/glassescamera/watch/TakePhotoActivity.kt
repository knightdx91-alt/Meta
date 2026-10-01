package com.knightdx.glassescamera.watch

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.widget.TextView
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

/** Message paths shared with the phone app (app module, WatchProtocol). */
object WatchProtocol {
    const val TAKE_PHOTO = "/glasses-tunes/take-photo"
    const val RESULT = "/glasses-tunes/result"
}

/**
 * Opening this app asks the phone to take a photo with the glasses, shows the
 * result, buzzes, and closes. Set the watch's Home button double-press to open
 * it ("Glasses Camera") and every double-press takes a photo.
 */
class TakePhotoActivity : Activity(), MessageClient.OnMessageReceivedListener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var label: TextView
    private val giveUp = Runnable { show("No answer from your phone", ok = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
        }
        setContentView(label)
        Wearable.getMessageClient(this).addListener(this)
        requestPhoto()
    }

    // Opening the app again (another Home double-press) while it's showing takes another photo.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        requestPhoto()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STEM_1 || keyCode == KeyEvent.KEYCODE_STEM_2 || keyCode == KeyEvent.KEYCODE_STEM_3) {
            requestPhoto()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        Wearable.getMessageClient(this).removeListener(this)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun requestPhoto() {
        main.removeCallbacksAndMessages(null)
        label.text = "📸\nTaking a photo…"
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    show("Phone not connected", ok = false)
                    return@addOnSuccessListener
                }
                nodes.forEach { Wearable.getMessageClient(this).sendMessage(it.id, WatchProtocol.TAKE_PHOTO, ByteArray(0)) }
                main.postDelayed(giveUp, 30_000)
            }
            .addOnFailureListener { show("Couldn't reach your phone", ok = false) }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchProtocol.RESULT) return
        val message = String(event.data)
        main.post { show(message, ok = message == "Photo saved") }
    }

    private fun show(message: String, ok: Boolean) {
        main.removeCallbacks(giveUp)
        label.text = (if (ok) "✅\n" else "⚠️\n") + message
        buzz(ok)
        main.postDelayed({ finish() }, if (ok) 1500 else 4000)
    }

    private fun buzz(ok: Boolean) {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        val effect = if (ok) VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
        else VibrationEffect.createWaveform(longArrayOf(0, 120, 120, 120), -1)
        vibrator.vibrate(effect)
    }
}
