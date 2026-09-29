package com.knightdx.glassestunes

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Listens for one spoken command through the glasses' microphone.
 *
 * Ray-Ban Meta glasses expose their mics to the phone as a normal Bluetooth
 * headset (HFP/SCO). We switch the phone's voice input to that headset, play a
 * short beep in the glasses, run Android's speech recognizer, then switch back
 * so music returns to full-quality A2DP.
 */
class GlassesVoice(private val context: Context) {

    sealed class Result {
        data class Heard(val phrases: List<String>, val viaGlasses: Boolean) : Result()
        data class Failed(val reason: String) : Result()
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tone: ToneGenerator? = null
    private var legacySco = false
    private var finished = true

    /** True if a Bluetooth headset microphone (the glasses) is connected. */
    fun glassesMicConnected(): Boolean = findGlassesMic() != null

    fun glassesName(): String? = findGlassesMic()?.productName?.toString()

    private fun findGlassesMic(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= 31) {
            audio.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?.let { return it }
        }
        return audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    fun listen(onResult: (Result) -> Unit) {
        main.post {
            if (!finished) return@post
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onResult(Result.Failed("Speech recognition isn't available. Install or enable the Google app."))
                return@post
            }
            finished = false
            val viaGlasses = routeToGlasses()
            // Give the Bluetooth voice link a moment to open before we beep.
            main.postDelayed({ beepThenRecognize(viaGlasses, onResult) }, if (viaGlasses) 900L else 100L)
        }
    }

    fun cancel() {
        main.post { finish(null, Result.Failed("cancelled")) }
    }

    private fun beepThenRecognize(viaGlasses: Boolean, onResult: (Result) -> Unit) {
        if (finished) return
        try {
            val stream = if (viaGlasses) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
            tone = ToneGenerator(stream, 90).apply { startTone(ToneGenerator.TONE_PROP_BEEP, 180) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "beep failed", e)
        }

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                finish(onResult, if (phrases.isEmpty()) Result.Failed("I didn't catch that.") else Result.Heard(phrases, viaGlasses))
            }

            override fun onError(error: Int) {
                finish(onResult, Result.Failed(describe(error)))
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        sr.startListening(intent)
        main.postDelayed({ finish(onResult, Result.Failed("I didn't hear anything.")) }, 12_000)
    }

    private fun finish(onResult: ((Result) -> Unit)?, result: Result) {
        if (finished) return
        finished = true
        main.removeCallbacksAndMessages(null)
        recognizer?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (e: RuntimeException) {
                Log.w(TAG, "recognizer cleanup", e)
            }
        }
        recognizer = null
        tone?.release()
        tone = null
        releaseRoute()
        onResult?.invoke(result)
    }

    private fun routeToGlasses(): Boolean {
        val mic = findGlassesMic() ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                audio.setCommunicationDevice(mic)
            } else {
                @Suppress("DEPRECATION")
                run {
                    audio.mode = AudioManager.MODE_IN_COMMUNICATION
                    audio.startBluetoothSco()
                    audio.isBluetoothScoOn = true
                }
                legacySco = true
                true
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not route to glasses mic", e)
            false
        }
    }

    private fun releaseRoute() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audio.clearCommunicationDevice()
            }
            if (legacySco) {
                @Suppress("DEPRECATION")
                run {
                    audio.isBluetoothScoOn = false
                    audio.stopBluetoothSco()
                }
                audio.mode = AudioManager.MODE_NORMAL
                legacySco = false
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "release route", e)
        }
    }

    private fun describe(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy."
        SpeechRecognizer.ERROR_AUDIO -> "Couldn't record audio."
        else -> "Speech recognition error $error."
    }

    companion object {
        private const val TAG = "GlassesVoice"
    }
}
