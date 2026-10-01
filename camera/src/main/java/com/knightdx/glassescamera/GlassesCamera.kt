package com.knightdx.glassescamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.WearablesError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Takes photos with the glasses' camera through Meta's Wearables Device Access
 * Toolkit (works with Developer Mode on in the Meta AI app). A photo is taken
 * from a short camera stream, saved to the phone's gallery, and the stream is
 * kept open for a little while so the next shot is quick.
 */
class GlassesCamera private constructor(private val context: Context) {

    sealed class Result {
        data class Saved(val uri: Uri) : Result()
        data class Failed(val reason: String) : Result()
    }

    enum class Readiness { NEEDS_BLUETOOTH_PERMISSION, NOT_CONNECTED, CONNECTING, READY }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val busy = Mutex()
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var idleClose: Job? = null

    /** Starts the toolkit. It needs Bluetooth ("Nearby devices") permission first. */
    fun ensureInitialized(): Boolean {
        if (initialized) return true
        if (!hasNearbyDevicesPermission(context)) return false
        Wearables.initialize(context.applicationContext)
            .onSuccess { initialized = true }
            .onFailure { error, _ ->
                if (error == WearablesError.ALREADY_INITIALIZED) initialized = true
                else Log.w(TAG, "toolkit didn't start: ${error.description}")
            }
        return initialized
    }

    fun readiness(): Readiness {
        if (!ensureInitialized()) return Readiness.NEEDS_BLUETOOTH_PERMISSION
        return when (Wearables.registrationState.value) {
            RegistrationState.REGISTERED -> Readiness.READY
            RegistrationState.REGISTERING -> Readiness.CONNECTING
            else -> Readiness.NOT_CONNECTED
        }
    }

    /** Takes a photo; [done] runs on the main thread. Every attempt is recorded in [PhotoLog]. */
    fun takePhoto(source: String = "app", done: (Result) -> Unit) {
        scope.launch {
            val result = try {
                if (busy.isLocked) {
                    Result.Failed("Still taking the last photo")
                } else {
                    busy.withLock {
                        // Never let one stuck attempt block every photo after it.
                        withTimeout(TOTAL_TIMEOUT_MS) { capture() }.also {
                            // A failed attempt may leave the stream half-broken; start fresh next time.
                            if (it is Result.Failed) closeCamera()
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                closeCamera()
                Result.Failed("The glasses didn't respond${lastError?.let { " ($it)" } ?: ""}. Make sure they're on, unfolded and connected.")
            } catch (e: Exception) {
                Log.w(TAG, "photo failed", e)
                closeCamera()
                Result.Failed("The photo didn't work: ${e.message ?: e.javaClass.simpleName}")
            }
            PhotoLog.add(context, source, result)
            done(result)
        }
    }

    /** Drops any open connection to the glasses camera; the next photo connects from scratch. */
    fun reset(done: () -> Unit) {
        scope.launch {
            idleClose?.cancel()
            closeCamera()
            done()
        }
    }

    /** The most recent error the toolkit reported (session or stream), for clearer failure messages. */
    @Volatile
    private var lastError: String? = null
    private var errorWatch: Job? = null

    private suspend fun capture(): Result {
        when (readiness()) {
            Readiness.NEEDS_BLUETOOTH_PERMISSION ->
                return Result.Failed("Open Glasses Camera on your phone and allow Nearby devices")
            Readiness.NOT_CONNECTED, Readiness.CONNECTING ->
                return Result.Failed("Open Glasses Camera on your phone and connect the glasses first")
            Readiness.READY -> Unit
        }
        val permission = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
        if (permission != PermissionStatus.Granted) {
            // Happens when "Allow once" was chosen in Meta AI: it expires after a while.
            return Result.Failed("Camera permission ran out. Open Glasses Camera, tap step 3 and choose Always allow")
        }
        idleClose?.cancel()
        lastError = null
        val cam = openCamera()
            ?: return Result.Failed(
                "Couldn't reach the glasses${lastError?.let { " ($it)" } ?: ""}. Make sure you're wearing them, they're unfolded and connected to Meta AI"
            )
        val photo = withTimeout(CAPTURE_TIMEOUT_MS) { cam.stream.capturePhoto() }
        val data = photo.getOrNull()
            ?: return Result.Failed("The glasses couldn't take the photo: ${photo.errorOrNull()?.description ?: "unknown error"}")
        val uri = withContext(Dispatchers.IO) { save(data) } ?: return Result.Failed("I couldn't save the photo")
        // Keep the camera ready briefly for another shot, then let the glasses rest.
        idleClose = scope.launch {
            delay(KEEP_OPEN_MS)
            busy.withLock { closeCamera() }
        }
        return Result.Saved(uri)
    }

    /** A streaming camera, reusing the open one when possible. */
    private suspend fun openCamera(): Camera? {
        camera?.let { if (isStreaming(it)) return it }
        closeCamera()
        val created = Wearables.createSession(AutoDeviceSelector())
        val newSession = created.getOrNull() ?: run {
            lastError = created.errorOrNull()?.description
            return null
        }
        session = newSession
        errorWatch?.cancel()
        errorWatch = scope.launch {
            launch { newSession.errors.collect { lastError = it.description; Log.w(TAG, "session: ${it.description}") } }
            // The glasses can end the session themselves (taken off, folded, out of range): forget it then,
            // so the next photo starts a new one instead of using a dead connection.
            newSession.state.collect { state ->
                if (state == DeviceSessionState.STOPPED && session === newSession) {
                    camera = null
                    session = null
                }
            }
        }
        newSession.start()
        withTimeout(20_000) { newSession.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED } }
        if (newSession.state.value != DeviceSessionState.STARTED) return null
        val added = newSession.addCamera(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 15))
        val cam = added.getOrNull() ?: run {
            lastError = added.errorOrNull()?.description
            return null
        }
        camera = cam
        scope.launch { cam.stream.errorStream.collect { lastError = it.description; Log.w(TAG, "stream: ${it.description}") } }
        val started = cam.stream.start()
        if (started.isFailure) {
            lastError = started.errorOrNull()?.description
            return null
        }
        withTimeout(20_000) {
            cam.stream.state.first { it == StreamState.STREAMING || it == StreamState.STOPPED || it == StreamState.CLOSED }
        }
        return cam.takeIf { isStreaming(it) }
    }

    /** False for a camera whose stream has ended (the toolkit throws if a stopped camera is touched). */
    private fun isStreaming(cam: Camera): Boolean = try {
        cam.stream.state.value == StreamState.STREAMING
    } catch (e: IllegalStateException) {
        false
    }

    private fun closeCamera() {
        errorWatch?.cancel()
        errorWatch = null
        try {
            camera?.stop()
            session?.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "closing camera", e)
        }
        camera = null
        session = null
    }

    /** Saves to Pictures/Glasses Camera so it shows up in Gallery and Google Photos. */
    private fun save(photo: PhotoData): Uri? {
        val name = "Glasses_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val (mime, ext) = when (photo) {
            is PhotoData.HEIC -> "image/heic" to "heic"
            else -> "image/jpeg" to "jpg"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Glasses Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                when (photo) {
                    is PhotoData.Bitmap -> photo.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    is PhotoData.HEIC -> {
                        val buffer = photo.data.duplicate()
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        out.write(bytes)
                    }
                }
            } ?: return null
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            Log.w(TAG, "saving photo", e)
            resolver.delete(uri, null, null)
            return null
        }
    }

    fun shutdown() {
        closeCamera()
    }

    companion object {
        private const val TAG = "GlassesCamera"

        // Holds the application context only, which lives as long as the app.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile
        private var shared: GlassesCamera? = null

        /** "Nearby devices" exists from Android 12; before that, Bluetooth needs no runtime permission. */
        fun hasNearbyDevicesPermission(context: Context): Boolean =
            android.os.Build.VERSION.SDK_INT < 31 ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        /** One camera for the whole app, so a watch press and a voice request share the open stream. */
        fun get(context: Context): GlassesCamera = shared ?: synchronized(this) {
            shared ?: GlassesCamera(context.applicationContext).also { shared = it }
        }
        private const val KEEP_OPEN_MS = 20_000L
        private const val CAPTURE_TIMEOUT_MS = 20_000L
        /** Connecting (20 s) + streaming (20 s) + capture (20 s), with room to spare; under the watch's 50 s wait. */
        private const val TOTAL_TIMEOUT_MS = 45_000L

        @Volatile
        private var initialized = false
    }
}
