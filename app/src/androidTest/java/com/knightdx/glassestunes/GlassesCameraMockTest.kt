package com.knightdx.glassestunes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Takes a photo through GlassesCamera against Meta's simulated glasses
 * (MockDeviceKit): session -> camera stream -> capturePhoto -> saved to the gallery.
 */
@RunWith(AndroidJUnit4::class)
class GlassesCameraMockTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val kit = MockDeviceKit.getInstance(context)

    @Before
    fun setUp() {
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation
        shell.executeShellCommand("pm grant ${context.packageName} android.permission.BLUETOOTH_CONNECT").close()
        Thread.sleep(500)
        kit.enable()
    }

    @After
    fun tearDown() = kit.disable()

    @Test
    fun takesAndSavesAPhoto() {
        // A recognizable test picture for the mock glasses to "capture": solid orange, 640x480.
        val picture = File(context.cacheDir, "mock_capture.jpg")
        Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(255, 128, 0)) }
            .compress(Bitmap.CompressFormat.JPEG, 90, picture.outputStream())

        val glasses = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
        glasses.powerOn()
        glasses.unfold()
        glasses.don()
        // The simulated camera streams a short test video and returns the picture when a photo is taken.
        val feed = File(context.cacheDir, "mock_feed.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("mock_feed.mp4").use { input ->
            feed.outputStream().use { input.copyTo(it) }
        }
        glasses.services.camera.setCameraFeed(Uri.fromFile(feed))
        glasses.services.camera.setCapturedImage(Uri.fromFile(picture))

        val camera = GlassesCamera(context)
        assertEquals(GlassesCamera.Readiness.READY, waitForReady(camera))

        val done = CountDownLatch(1)
        var result: GlassesCamera.Result? = null
        Handler(Looper.getMainLooper()).post {
            camera.takePhoto { result = it; done.countDown() }
        }
        assertTrue("no result within 60 s", done.await(60, TimeUnit.SECONDS))
        val saved = result as? GlassesCamera.Result.Saved
        assertNotNull("photo failed: $result", saved)

        // The saved file is a real image in the gallery.
        val bytes = context.contentResolver.openInputStream(saved!!.uri)!!.use { it.readBytes() }
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("saved file isn't an image", image)
        println("GlassesCameraMockTest: saved ${saved.uri} ${image.width}x${image.height}, ${bytes.size} bytes")
        Handler(Looper.getMainLooper()).post { camera.shutdown() }
    }

    private fun waitForReady(camera: GlassesCamera): GlassesCamera.Readiness {
        var state = camera.readiness()
        repeat(40) {
            if (state == GlassesCamera.Readiness.READY) return state
            Thread.sleep(250)
            state = camera.readiness()
        }
        return state
    }
}
