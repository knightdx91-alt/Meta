package com.knightdx.glassestunes

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/** Message paths shared with the watch app (wear module, WatchProtocol). */
object WatchProtocol {
    const val TAKE_PHOTO = "/glasses-tunes/take-photo"
    const val RESULT = "/glasses-tunes/result"
}

/**
 * Receives "take a photo" from the Glasses Tunes watch app and replies with
 * the outcome, which the watch shows (and buzzes for).
 */
class WatchListener : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchProtocol.TAKE_PHOTO) return
        val watch = event.sourceNodeId
        val service = GlassesService.instance
        if (service == null) {
            // Android won't let us start the microphone service from here; the camera works without it,
            // but the connector should be running for everything else anyway.
            reply(watch, "Open Glasses Tunes on your phone first")
            return
        }
        service.takePhoto(fromWatch = true) { message -> reply(watch, message) }
    }

    private fun reply(node: String, message: String) {
        Wearable.getMessageClient(applicationContext)
            .sendMessage(node, WatchProtocol.RESULT, message.toByteArray())
            .addOnFailureListener { Log.w("WatchListener", "couldn't reply to the watch", it) }
    }
}
