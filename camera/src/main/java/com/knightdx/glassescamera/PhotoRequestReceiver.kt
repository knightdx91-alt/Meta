package com.knightdx.glassescamera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * "Jarvis, take a photo" from Glasses Tunes. Only apps signed with the same key
 * can send this (see the TAKE_PHOTO permission in the manifest), so no other
 * app on the phone can trigger the glasses camera.
 * The result message is sent back as the broadcast's result data.
 */
class PhotoRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TAKE_PHOTO) return
        val pending = goAsync()
        thread(name = "photo-request") {
            val message = WatchListener.takePhotoAndWait(context.applicationContext, "Glasses Tunes")
            pending.resultCode = if (message == "Photo saved") 1 else 0
            pending.resultData = message
            pending.finish()
        }
    }

    companion object {
        const val ACTION_TAKE_PHOTO = "com.knightdx.glassescamera.TAKE_PHOTO"
    }
}
