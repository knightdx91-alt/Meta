package com.knightdx.glassestunes

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
class GlassesCamera(private val context: Context) {

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
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
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

    fun takePhoto(done: (Result) -> Unit) {
        scope.launch {
            val result = try {
                if (busy.isLocked) Result.Failed("Still taking the last photo") else busy.withLock { capture() }
            } catch (e: TimeoutCancellationException) {
                closeCamera()
                Result.Failed("The glasses didn't respond. Make sure they're on and connected.")
            } catch (e: Exception) {
                Log.w(TAG, "photo failed", e)
                CrashReport.recordProblem(context, "taking a photo", e)
                closeCamera()
                Result.Failed("The photo didn't work")
            }
            done(result)
        }
    }

    private suspend fun capture(): Result {
        when (readiness()) {
            Readiness.NEEDS_BLUETOOTH_PERMISSION ->
                return Result.Failed("Allow Nearby devices for Glasses Tunes so it can use the glasses camera")
            Readiness.NOT_CONNECTED, Readiness.CONNECTING ->
                return Result.Failed("Connect the glasses camera in the Glasses Tunes app first")
            Readiness.READY -> Unit
        }
        val permission = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
        if (permission != PermissionStatus.Granted) {
            return Result.Failed("Allow the glasses camera in the Glasses Tunes app first")
        }
        idleClose?.cancel()
        val cam = openCamera() ?: return Result.Failed("I couldn't reach the glasses camera")
        val photo = cam.stream.capturePhoto()
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
        camera?.let { if (it.stream.state.value == StreamState.STREAMING) return it }
        closeCamera()
        val newSession = Wearables.createSession(AutoDeviceSelector()).getOrNull() ?: return null
        session = newSession
        newSession.start()
        withTimeout(20_000) { newSession.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED } }
        if (newSession.state.value != DeviceSessionState.STARTED) return null
        val cam = newSession.addCamera(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 15)).getOrNull()
            ?: return null
        camera = cam
        if (cam.stream.start().isFailure) return null
        withTimeout(20_000) {
            cam.stream.state.first { it == StreamState.STREAMING || it == StreamState.STOPPED || it == StreamState.CLOSED }
        }
        return cam.takeIf { it.stream.state.value == StreamState.STREAMING }
    }

    private fun closeCamera() {
        try {
            camera?.stop()
            session?.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "closing camera", e)
        }
        camera = null
        session = null
    }

    /** Saves to Pictures/Glasses Tunes so it shows up in Gallery and Google Photos. */
    private fun save(photo: PhotoData): Uri? {
        val name = "Glasses_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val (mime, ext) = when (photo) {
            is PhotoData.HEIC -> "image/heic" to "heic"
            else -> "image/jpeg" to "jpg"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Glasses Tunes")
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
        private const val KEEP_OPEN_MS = 20_000L

        @Volatile
        private var initialized = false
    }
}
