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

    /** Nice to have: glasses detection, calls, texts. The connector runs without them. */
    private fun optionalPermissions(): List<String> {
        val wanted = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.SEND_SMS,
        )
        if (Build.VERSION.SDK_INT >= 31) wanted += Manifest.permission.BLUETOOTH_CONNECT
        return wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun missingPermissions(): List<String> {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
            wanted += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            wanted += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun hasScreenControl(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, ScreenControlService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun hasMediaAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, MediaNotificationListener::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private var askedOptional = false

    private fun startConnector() {
        val missing = missingPermissions()
        val optional = if (askedOptional) emptyList() else optionalPermissions()
        if (missing.isNotEmpty() || optional.isNotEmpty()) {
            askedOptional = true
            requestPermissions((missing + optional).toTypedArray(), 1)
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
            line(optionalPermissions().isEmpty(), "Contacts, calls & texts (for \"call Mom\", \"text Mom…\")"),
            line(hasMediaAccess(), "Notification access (Samsung Music control, reading & replying to messages)"),
            line(hasScreenControl(), "Screen control (tap, scroll, type, WhatsApp send)"),
            line(
                Settings.canDrawOverlays(this),
                "Display over other apps (auto-start when glasses connect, open apps hands-free)",
            ),
            line(samsung.isInstalled(), if (samsung.isInstalled()) "Samsung Music installed" else "Samsung Music not found — built-in player will be used"),
            line(
                Prefs.geminiKey(this).isNotBlank(),
                if (Prefs.geminiKey(this).isNotBlank()) "Questions: Gemini key added" else "Questions: add a Gemini key below",
            ),
            line(
                cameraAppInstalled(),
                if (cameraAppInstalled()) "Glasses Camera app installed (\"Jarvis, take a photo\")"
                else "Glasses Camera app not installed (optional, for photos)",
            ),
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

        CrashReport.report(this)?.let { report -> column.addView(crashPanel(report, pad)) }

        checklist = text("")
        column.addView(checklist)

        column.addView(button("1 · Grant permissions & start connector") { startConnector() })
        column.addView(button("2 · Allow notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        column.addView(button("3 · Allow display over other apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
            )
        })
        column.addView(button("4 · Turn on screen control (optional)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        column.addView(
            text(
                "If Android says \"Restricted setting\" for steps 2 or 4: open Settings → Apps → Glasses Tunes → " +
                    "⋮ (top right) → Allow restricted settings, then try again. Android does this for every app " +
                    "installed outside the Play Store.",
                13f,
            )
        )
        column.addView(button("App info (for restricted settings)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
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
                    "\"volume up\", \"what's playing\", \"open Maps\", \"talk to Gemini\".\n\n" +
                    "Phone & messages: \"call Mom\", \"text Mom I'm on my way\", \"WhatsApp John see you soon\", " +
                    "\"read my messages\", \"reply sounds good\", \"answer\".\n\n" +
                    "Screen: \"tap Send\", \"scroll down\", \"type hello\", \"go home\", \"press back\"."
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

        column.addView(text("Photos", 18f))
        column.addView(
            text(
                "\"Jarvis, take a photo\" uses the separate Glasses Camera app, which also lets your Galaxy Watch " +
                    "take photos. Install and set it up to use it."
            )
        )

        column.addView(text("Ask questions", 18f))
        column.addView(
            text(
                "Say \"Jarvis\", then ask anything (\"who won the Lakers game?\", \"how long do I boil an egg?\"), " +
                    "or say \"google…\" / \"look up…\". Answers come from Google's Gemini with Google Search and are " +
                    "spoken in your glasses. Needs a free Gemini API key: tap below, sign in with Google, " +
                    "tap \"Create API key\", copy it and paste it here. Your questions are sent to Google; on the free " +
                    "tier Google may use them to improve its products."
            )
        )
        val geminiInput = EditText(this).apply {
            hint = "Gemini API key"
            setText(Prefs.geminiKey(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }
        column.addView(geminiInput)
        column.addView(button("Save Gemini key") {
            Prefs.setGeminiKey(this, geminiInput.text.toString())
            statusView.text = "Gemini key saved. Try: \"Jarvis\" … \"what's the capital of Australia?\""
            updateChecklist()
        })
        column.addView(button("Get a free Gemini key") {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/apikey")))
        })
        val cityInput = EditText(this).apply {
            hint = "Your city (optional, for weather and \"near me\")"
            setText(Prefs.homeCity(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
        }
        column.addView(cityInput)
        column.addView(button("Save city") {
            Prefs.setHomeCity(this, cityInput.text.toString())
            statusView.text = "City saved"
        })

        column.addView(text("Settings", 18f))
        column.addView(toggle("Tap-tap on glasses to talk", Prefs.TAP_GESTURE))
        column.addView(toggle("Start automatically when glasses connect", Prefs.AUTO_START))
        column.addView(toggle("Use Samsung Music (off = built-in player for phone files)", Prefs.PREFER_SAMSUNG))
        column.addView(toggle("Speak replies in the glasses", Prefs.SPEAK_REPLIES))
        column.addView(toggle("Read new messages aloud as they arrive", Prefs.ANNOUNCE_MESSAGES))

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

    private fun cameraAppInstalled() = try {
        packageManager.getPackageInfo("com.knightdx.glassescamera", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun crashPanel(report: String, pad: Int): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setBackgroundColor(0x33FF5252)
        }
        panel.addView(TextView(this).apply {
            text = "⚠️ Glasses Tunes crashed recently. Tap Share and send the report so it can be fixed."
            textSize = 15f
        })
        panel.addView(TextView(this).apply {
            text = report.lines().take(12).joinToString("\n")
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, pad / 2)
        })
        panel.addView(Button(this).apply {
            text = "Share crash report"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Glasses Tunes crash report")
                    .putExtra(Intent.EXTRA_TEXT, report)
                startActivity(Intent.createChooser(send, "Share crash report"))
            }
        })
        panel.addView(Button(this).apply {
            text = "Dismiss"
            setOnClickListener {
                CrashReport.clear(this@MainActivity)
                panel.visibility = View.GONE
            }
        })
        return panel
    }

    companion object {
        const val EXTRA_LISTEN = "listen"
    }
}
