package com.knightdx.glassescamera

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus

/** Setup and a test button. Day to day you just use the watch (or "Jarvis, take a photo"). */
class MainActivity : ComponentActivity() {

    private lateinit var checklist: TextView
    private lateinit var status: TextView
    private val camera get() = GlassesCamera.get(this)

    /** Camera permission is granted in the Meta AI app, which this opens. */
    private val glassesCameraPermission = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        status.text = if (result.getOrNull() == PermissionStatus.Granted) "Glasses camera allowed" else "Glasses camera wasn't allowed"
        updateChecklist()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        updateChecklist()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateChecklist()
    }

    /** The most recent photo in Pictures/Glasses Camera (this app saved them, so it can read them). */
    private fun newestPhoto(): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/Glasses Camera%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c -> if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(collection, c.getLong(0)) }
        return null
    }

    private fun hasNearbyDevices() = GlassesCamera.hasNearbyDevicesPermission(this)

    private fun updateChecklist() {
        fun line(ok: Boolean, text: String) = (if (ok) "✅ " else "⚠️ ") + text
        val readiness = camera.readiness()
        checklist.text = listOf(
            line(hasNearbyDevices(), "Nearby devices permission (to reach the glasses)"),
            line(
                readiness == GlassesCamera.Readiness.READY,
                when (readiness) {
                    GlassesCamera.Readiness.READY -> "Connected to the glasses through Meta AI"
                    GlassesCamera.Readiness.CONNECTING -> "Finish connecting in the Meta AI app"
                    else -> "Not connected to the glasses yet (step 2)"
                },
            ),
            "ℹ️ Developer Mode must stay on in the Meta AI app.",
        ).joinToString("\n")
    }

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(s: String, size: Float = 15f) = TextView(this).apply {
            text = s
            textSize = size
            setPadding(0, pad / 2, 0, pad / 2)
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

        column.addView(text(getString(R.string.app_name), 24f))
        column.addView(
            text(
                "Take photos with your Ray-Ban Meta glasses by double-pressing your Galaxy Watch's Home button, " +
                    "or by saying \"Jarvis, take a photo\" in Glasses Tunes. Photos are saved to " +
                    "Gallery → Pictures → Glasses Camera."
            )
        )
        checklist = text("")
        column.addView(checklist)

        column.addView(button("1 · Allow Nearby devices") {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            } else {
                status.text = "Not needed on this Android version"
            }
        })
        column.addView(button("2 · Connect to the glasses (opens Meta AI)") {
            if (camera.ensureInitialized()) Wearables.startRegistration(this) else status.text = "Do step 1 first"
        })
        column.addView(button("3 · Allow the glasses camera (opens Meta AI)") {
            if (camera.ensureInitialized()) glassesCameraPermission.launch(Permission.CAMERA) else status.text = "Do step 1 first"
        })
        column.addView(button("📸 Take a test photo") {
            status.text = "Taking a photo…"
            camera.takePhoto { result ->
                when (result) {
                    is GlassesCamera.Result.Saved -> {
                        status.text = "Photo saved. Tap \"Show latest photo\"."
                    }
                    is GlassesCamera.Result.Failed -> status.text = result.reason
                }
            }
        })
        column.addView(button("🖼️ Show latest photo") {
            // Includes photos taken from the watch or Glasses Tunes, not just this screen.
            val uri = newestPhoto() ?: return@button run { status.text = "No glasses photos yet" }
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        })
        status = text("", 18f)
        column.addView(status)

        column.addView(text("On your Galaxy Watch", 18f))
        column.addView(
            text(
                "Install the Glasses Camera watch app, then on the watch: Settings → Advanced features → " +
                    "Customize keys → Home key → Double press → Glasses Camera. Each double-press takes a photo; " +
                    "the watch shows ✅ and buzzes when it's saved."
            )
        )
        column.addView(
            text("Your photos are in Gallery → Albums → Glasses Camera (or Google Photos → On this device → Glasses Camera).")
        )
        column.addView(button("Open Gallery") {
            startActivity(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        })
        return ScrollView(this).apply { addView(column) }
    }
}
