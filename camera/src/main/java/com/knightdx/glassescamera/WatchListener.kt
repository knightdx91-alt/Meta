package com.knightdx.glassescamera

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Message paths shared with the watch app (wear module). */
object WatchProtocol {
    const val TAKE_PHOTO = "/glasses-tunes/take-photo"
    const val RESULT = "/glasses-tunes/result"
}

/**
 * Receives "take a photo" from the Glasses Camera watch app, takes it, and
 * replies with the outcome, which the watch shows (and buzzes for).
 *
 * This runs on a background thread while Google Play services keeps us bound,
 * so we simply wait here for the photo instead of starting a service.
 */
class WatchListener : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchProtocol.TAKE_PHOTO) return
        val message = takePhotoAndWait(this, "watch")
        Wearable.getMessageClient(applicationContext)
            .sendMessage(event.sourceNodeId, WatchProtocol.RESULT, message.toByteArray())
            .addOnFailureListener { Log.w("WatchListener", "couldn't reply to the watch", it) }
    }

    companion object {
        /** Takes a photo and returns a short message ("Photo saved" or why not). Call off the main thread. */
        fun takePhotoAndWait(context: android.content.Context, source: String): String {
            val done = CountDownLatch(1)
            var message = "The glasses didn't respond"
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                GlassesCamera.get(context).takePhoto(source) { result ->
                    message = when (result) {
                        is GlassesCamera.Result.Saved -> "Photo saved"
                        is GlassesCamera.Result.Failed -> result.reason
                    }
                    done.countDown()
                }
            }
            done.await(50, TimeUnit.SECONDS)
            return message
        }
    }
}
