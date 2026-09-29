package com.knightdx.glassestunes

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Short spoken replies in the glasses ("Playing Queen"). */
class Speaker(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val pending = HashMap<String, () -> Unit>()
    private var counter = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.getDefault()
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = complete(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = complete(utteranceId)
        })
    }

    private fun complete(id: String?) {
        main.post { id?.let { pending.remove(it) }?.invoke() }
    }

    /** Speak [text], then run [then] on the main thread (immediately if TTS isn't available). */
    fun say(text: String, then: () -> Unit) {
        if (!ready) {
            then()
            return
        }
        val id = "u${counter++}"
        pending[id] = then
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            then()
            return
        }
        // Never get stuck if the engine forgets to call back.
        main.postDelayed({ pending.remove(id)?.invoke() }, 8000)
    }

    fun shutdown() {
        tts.shutdown()
        pending.clear()
    }
}
