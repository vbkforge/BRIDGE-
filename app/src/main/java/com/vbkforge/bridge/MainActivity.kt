package com.vbkforge.bridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var connectionText: TextView
    private lateinit var a11yText: TextView
    private lateinit var hostInput: EditText
    private lateinit var connectorUrl: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var resumeButton: Button

    private val refresh: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityLog.init(this)
        setContentView(R.layout.activity_main)
        applySystemBarPadding(findViewById(R.id.root))

        statusText = findViewById(R.id.statusText)
        connectionText = findViewById(R.id.connectionText)
        a11yText = findViewById(R.id.a11yText)
        hostInput = findViewById(R.id.hostInput)
        connectorUrl = findViewById(R.id.connectorUrl)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        resumeButton = findViewById(R.id.resumeButton)

        hostInput.setText(Prefs.relayHost(this))
        findViewById<TextView>(R.id.versionText).text = "Bridge version ${BuildConfig.VERSION_NAME}"

        startButton.setOnClickListener {
            val svc = BridgeService.instance
            if (svc == null) {
                toast("Turn on Bridge in Accessibility settings first (step 1).")
                return@setOnClickListener
            }
            saveHostIfChanged()
            svc.startControl()?.let { toast(it) }
            render()
        }
        stopButton.setOnClickListener {
            BridgeService.instance?.stopControl("You tapped Stop in the Bridge app")
            render()
        }
        resumeButton.setOnClickListener { BridgeService.instance?.resume() }
        findViewById<Button>(R.id.logButton).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<Button>(R.id.a11yButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.appInfoButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.saveHostButton).setOnClickListener {
            saveHostIfChanged()
            toast("Saved")
            render()
        }
        findViewById<Button>(R.id.copyButton).setOnClickListener {
            val url = Prefs.connectorUrl(this)
            if (url == null) {
                toast("Save your relay address first.")
            } else {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Bridge connector URL", url))
                toast("Copied")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BridgeService.addListener(refresh)
        render()
    }

    override fun onPause() {
        BridgeService.removeListener(refresh)
        super.onPause()
    }

    private fun saveHostIfChanged() {
        val typed = Prefs.normalizeHost(hostInput.text.toString())
        if (typed != Prefs.relayHost(this)) {
            if (BridgeService.instance?.mode != BridgeService.Mode.OFF && BridgeService.instance != null) {
                BridgeService.instance?.stopControl("Relay address changed")
            }
            Prefs.setRelayHost(this, typed)
            ActivityLog.add("Relay address set to ${typed.ifBlank { "(empty)" }}")
        }
        hostInput.setText(typed)
    }

    private fun render() {
        val svc = BridgeService.instance
        a11yText.text = if (svc != null) {
            "✅ Bridge accessibility service is ON."
        } else {
            "❌ OFF. Tap the button below, open \"Bridge\" (it may be under \"Downloaded apps\" or \"Installed apps\") and turn it on. " +
                "If the switch is greyed out: open Bridge app info, tap ⋮ (top right) → \"Allow restricted settings\", then try again."
        }

        connectorUrl.text = Prefs.connectorUrl(this) ?: "(save your relay address first)"

        val mode = svc?.mode ?: BridgeService.Mode.OFF
        statusText.text = when {
            svc == null -> "Status: accessibility service is off"
            mode == BridgeService.Mode.ACTIVE -> "Status: CONTROL ON — tap the red STOP button to stop"
            mode == BridgeService.Mode.PAUSED -> "Status: PAUSED — your turn (${svc?.pauseReason ?: "sensitive screen"})"
            else -> "Status: stopped. Claude cannot control the tablet."
        }
        statusText.setBackgroundColor(
            when (mode) {
                BridgeService.Mode.ACTIVE -> 0x331B7F3B
                BridgeService.Mode.PAUSED -> 0x55FFC107
                BridgeService.Mode.OFF -> 0x22888888
            }
        )
        connectionText.text = when {
            svc == null || mode == BridgeService.Mode.OFF -> ""
            else -> "Relay: ${svc.connectionText}"
        }
        startButton.isEnabled = svc != null && mode == BridgeService.Mode.OFF
        stopButton.isEnabled = svc != null && mode != BridgeService.Mode.OFF
        resumeButton.visibility = if (mode == BridgeService.Mode.PAUSED) View.VISIBLE else View.GONE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

/** Keeps content clear of the status and navigation bars (Android 15+ draws edge-to-edge). */
fun applySystemBarPadding(view: View) {
    val base = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
    view.setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        v.setPadding(base[0] + bars.left, base[1] + bars.top, base[2] + bars.right, base[3] + bars.bottom)
        insets
    }
}
