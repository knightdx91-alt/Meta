package com.knightdx.glassestunes

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException

/**
 * Listens continuously for "Jarvis" with Vosk, an open-source offline speech
 * recognizer. The small English model ships inside the app, so this needs no
 * account, no key and no internet, and audio never leaves the phone.
 */
class JarvisWakeWord(
    private val context: Context,
    private val onWake: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null

    @Volatile
    private var model: Model? = null

    @Volatile
    private var running = false

    val isRunning get() = running

    /** Start listening on [mic] (the glasses), or the default mic if null. */
    fun start(mic: AudioDeviceInfo?): Boolean {
        if (running) return true
        running = true
        thread = Thread({ loop(mic) }, "jarvis-wake-word").apply { start() }
        return true
    }

    /** Stop and wait for the mic to be released, so speech recognition can use it. */
    fun stop() {
        running = false
        val t = thread ?: return
        thread = null
        if (t !== Thread.currentThread()) t.join(1500)
    }

    fun release() {
        stop()
        model?.close()
        model = null
    }

    /** First run copies the bundled model out of the APK (a few seconds); after that it's instant. */
    private fun loadModel(): Model = model ?: synchronized(this) {
        model ?: Model(StorageService.sync(context, MODEL_ASSET, "model")).also { model = it }
    }

    @SuppressLint("MissingPermission") // the service only runs once RECORD_AUDIO is granted
    private fun loop(mic: AudioDeviceInfo?) {
        val recognizer = try {
            Recognizer(loadModel(), SAMPLE_RATE.toFloat(), JarvisDetector.GRAMMAR).apply { setWords(true) }
        } catch (e: IOException) {
            fail("Jarvis couldn't load its speech model", e)
            return
        }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME * 2 * 8),
            )
        } catch (e: RuntimeException) {
            recognizer.close()
            fail("Couldn't open the microphone for Jarvis", e)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            recognizer.close()
            fail("Couldn't open the microphone for Jarvis", null)
            return
        }
        mic?.let { record.preferredDevice = it }
        val frame = ShortArray(FRAME)
        try {
            record.startRecording()
            Log.i(TAG, "listening for Jarvis on ${mic?.productName ?: "phone mic"}")
            while (running) {
                val n = record.read(frame, 0, frame.size)
                if (n < 0) throw IllegalStateException("AudioRecord.read error $n")
                if (n == 0 || !recognizer.acceptWaveForm(frame, n)) continue
                val result = recognizer.result
                if (JarvisDetector.isWake(result)) {
                    Log.i(TAG, "Jarvis! $result")
                    running = false
                    main.post(onWake)
                }
            }
        } catch (e: Exception) {
            fail("Jarvis stopped listening: ${e.message}", e)
        } finally {
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                // never started
            }
            record.release()
            recognizer.close()
        }
    }

    private fun fail(message: String, e: Throwable?) {
        Log.w(TAG, message, e)
        running = false
        main.post { onError(message) }
    }

    companion object {
        private const val TAG = "JarvisWakeWord"
        private const val SAMPLE_RATE = 16000
        private const val FRAME = 512
        private const val MODEL_ASSET = "model-en-us"
    }
}
