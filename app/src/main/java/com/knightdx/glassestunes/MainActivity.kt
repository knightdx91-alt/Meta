package com.knightdx.glassestunes

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var checklist: TextView
    private lateinit var statusView: TextView
    private var listenWhenStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listenWhenStarted = intent.getBooleanExtra(EXTRA_LISTEN, false)
        setContentView(buildUi())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_LISTEN, false)) {
            listenWhenStarted = true
            startConnector()
        }
    }

    override fun onResume() {
        super.onResume()
        GlassesService.onStatus = { runOnUiThread { statusView.text = it } }
        GlassesService.instance?.refresh()
        updateChecklist()
        if (listenWhenStarted && missingPermissions().isEmpty()) startConnector()
    }

    override fun onPause() {
        GlassesService.onStatus = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateChecklist()
        if (missingPermissions().isEmpty()) startConnector()
    }

    private fun missingPermissions(): List<String> {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) wanted += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
            wanted += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            wanted += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun hasMediaAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, MediaNotificationListener::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun startConnector() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        GlassesService.start(this)
        if (listenWhenStarted) {
            listenWhenStarted = false
            statusView.postDelayed({ GlassesService.instance?.startListening() }, 800)
        }
        statusView.postDelayed({ updateChecklist() }, 1500)
    }

    private fun updateChecklist() {
        val voice = GlassesVoice(this)
        val samsung = SamsungMusic(this)
        val service = GlassesService.instance
        fun line(ok: Boolean, text: String) = (if (ok) "✅ " else "⚠️ ") + text
        checklist.text = listOf(
            line(
                voice.glassesMicConnected(),
                if (voice.glassesMicConnected()) "Glasses mic connected (${voice.glassesName() ?: "Bluetooth"})"
                else "Glasses not connected over Bluetooth — music & mic will use the phone",
            ),
            line(missingPermissions().isEmpty(), "Microphone, music & notification permissions"),
            line(hasMediaAccess(), "Media control access (lets taps & voice control Samsung Music)"),
            line(
                Settings.canDrawOverlays(this),
                "Display over other apps (auto-start when glasses connect, open apps hands-free)",
            ),
            line(samsung.isInstalled(), if (samsung.isInstalled()) "Samsung Music installed" else "Samsung Music not found — built-in player will be used"),
            line(service != null, if (service != null) "Connector running · ${service.librarySize} songs on phone" else "Connector stopped"),
            line(
                service?.jarvisStatus == "Listening for \"Jarvis\"",
                service?.jarvisStatus ?: "Jarvis starts with the connector",
            ),
        ).joinToString("\n")
        service?.let { statusView.text = it.status }
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
        fun toggle(label: String, key: String) = Switch(this).apply {
            text = label
            isChecked = Prefs.get(this@MainActivity, key)
            setPadding(0, pad / 2, 0, pad / 2)
            setOnCheckedChangeListener { _, on ->
                Prefs.set(this@MainActivity, key, on)
                GlassesService.instance?.refresh()
                statusView.postDelayed({ updateChecklist() }, 800)
            }
        }

        column.addView(text(getString(R.string.app_name), 24f))
        column.addView(text("Voice control for Samsung Music and the music on your phone, through your Ray-Ban Meta glasses."))

        checklist = text("")
        column.addView(checklist)

        column.addView(button("1 · Grant permissions & start connector") { startConnector() })
        column.addView(button("2 · Allow media control access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        column.addView(button("3 · Allow display over other apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
            )
        })
        column.addView(button("🎤 Talk now") {
            val s = GlassesService.instance
            if (s != null) s.startListening() else {
                listenWhenStarted = true
                startConnector()
            }
        })

        statusView = text("", 18f).apply { gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(statusView)

        column.addView(text("How to talk to it", 18f))
        column.addView(
            text(
                "• Say \"Jarvis\", wait for the beep, then say your command.\n" +
                    "• Or tap the glasses' temple twice (pause → play), then speak after the beep.\n" +
                    "• Or use the \"Talk\" button in the notification or the Quick Settings tile.\n\n" +
                    "Try: \"play Bohemian Rhapsody by Queen\", \"play the album Thriller\", \"shuffle Drake\", " +
                    "\"play my workout playlist\", \"shuffle everything\", \"next\", \"previous\", \"pause\", " +
                    "\"volume up\", \"what's playing\", \"open Maps\", \"talk to Gemini\"."
            )
        )

        column.addView(text("Jarvis wake word", 18f))
        column.addView(
            text(
                "Say \"Jarvis\", pause, and wait for the beep. Jarvis runs entirely on your phone " +
                    "(open-source Vosk speech model), with no account, no internet, and no audio leaving the phone."
            )
        )
        column.addView(toggle("Always listen for \"Jarvis\" while glasses are connected", Prefs.JARVIS))
        column.addView(
            toggle("Pause Jarvis while music plays (keeps music in full quality)", Prefs.JARVIS_IDLE_ONLY)
        )

        column.addView(text("Settings", 18f))
        column.addView(toggle("Tap-tap on glasses to talk", Prefs.TAP_GESTURE))
        column.addView(toggle("Start automatically when glasses connect", Prefs.AUTO_START))
        column.addView(toggle("Use Samsung Music (off = built-in player for phone files)", Prefs.PREFER_SAMSUNG))
        column.addView(toggle("Speak replies in the glasses", Prefs.SPEAK_REPLIES))

        column.addView(text("Try a command without speaking", 18f))
        val input = EditText(this).apply {
            hint = "e.g. play the album Thriller"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val run = {
            val s = GlassesService.instance
            if (s == null) statusView.text = "Start the connector first" else s.runText(input.text.toString())
        }
        input.setOnEditorActionListener { _, _, _ -> run(); true }
        column.addView(input)
        column.addView(button("Run") { run() })

        column.addView(button("Stop connector") {
            startService(Intent(this, GlassesService::class.java).setAction(GlassesService.ACTION_STOP))
            statusView.postDelayed({ updateChecklist() }, 500)
        })

        return ScrollView(this).apply { addView(column) }
    }

    companion object {
        const val EXTRA_LISTEN = "listen"
    }
}
