package com.vbkforge.bridge

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The heart of Bridge. Runs as an Accessibility Service, so it can read the screen,
 * take screenshots and perform gestures. It also owns the relay connection, the STOP
 * button and the safety pause, all in one place, so STOP takes effect instantly.
 */
class BridgeService : AccessibilityService(), RelayClient.Listener {

    enum class Mode { OFF, ACTIVE, PAUSED }

    companion object {
        @Volatile var instance: BridgeService? = null
            private set

        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        fun addListener(l: () -> Unit) { listeners.add(l) }
        fun removeListener(l: () -> Unit) { listeners.remove(l) }
    }

    @Volatile var mode = Mode.OFF
        private set
    @Volatile var pauseReason: String? = null
        private set
    @Volatile var connectionText = "Not connected"
        private set
    @Volatile var connected = false
        private set

    /** True while your finger is on the STOP button: no gestures are sent. */
    @Volatile var holdGestures = false

    private val main = Handler(Looper.getMainLooper())
    private lateinit var safetyThread: HandlerThread
    private lateinit var safetyHandler: Handler
    @Volatile private var worker: ExecutorService = Executors.newSingleThreadExecutor()

    lateinit var safety: SafetyGuard
        private set
    lateinit var actions: Actions
        private set
    private lateinit var mcp: McpHandler
    private lateinit var relay: RelayClient
    private lateinit var overlay: OverlayController

    // ---- Lifecycle -------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        ActivityLog.init(this)
        safety = SafetyGuard(this)
        actions = Actions(this)
        mcp = McpHandler(this)
        relay = RelayClient(this)
        overlay = OverlayController(this)
        safetyThread = HandlerThread("bridge-safety").also { it.start() }
        safetyHandler = Handler(safetyThread.looper)
        instance = this
        log("Accessibility service is on")
        notifyChanged()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown("Accessibility service was turned off")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown("Bridge service ended")
        super.onDestroy()
    }

    private fun shutdown(why: String) {
        if (instance !== this) return
        stopControl(why)
        instance = null
        if (::safetyThread.isInitialized) safetyThread.quitSafely()
        notifyChanged()
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlay.isInitialized) main.post { overlay.onScreenChanged() }
    }

    // ---- Start / stop / pause -------------------------------------------

    /** Returns null on success, or a message explaining why control could not start. */
    fun startControl(): String? {
        val url = Prefs.deviceUrl(this) ?: return "Enter your relay address first (step 3 on this screen)."
        if (mode != Mode.OFF) return null
        worker = Executors.newSingleThreadExecutor()
        holdGestures = false
        pauseReason = null
        mode = Mode.ACTIVE
        relay.connect(url)
        main.post { overlay.showStopButton() }
        log("Control started. Waiting for Claude.")
        notifyChanged()
        return null
    }

    /** Stops everything immediately. Safe to call from any thread, any number of times. */
    fun stopControl(why: String) {
        if (mode == Mode.OFF) return
        mode = Mode.OFF                 // 1. every check now refuses to act
        pauseReason = null
        worker.shutdownNow()            // 2. drop queued requests, interrupt the running one
        relay.disconnect()              // 3. cut the line to Claude
        holdGestures = false
        // Posted (not run inline) because this may be called from inside the STOP button's own touch event.
        if (::overlay.isInitialized) main.post { overlay.hideAll() }
        log("STOPPED: $why")
        notifyChanged()
    }

    fun pause(reason: String) {
        if (mode != Mode.ACTIVE) return
        mode = Mode.PAUSED
        pauseReason = reason
        log("SAFETY PAUSE: $reason. Your turn.")
        main.post { overlay.showYourTurn(reason, stillSensitive = false) }
        notifyChanged()
    }

    fun resume() {
        if (mode != Mode.PAUSED) return
        safetyHandler.post {
            val still = safety.check()
            main.post {
                if (mode != Mode.PAUSED) return@post
                if (still != null) {
                    pauseReason = still
                    overlay.showYourTurn(still, stillSensitive = true)
                    notifyChanged()
                } else {
                    mode = Mode.ACTIVE
                    pauseReason = null
                    overlay.hideYourTurn()
                    log("You resumed control")
                    notifyChanged()
                }
            }
        }
    }

    /** Called right before any gesture or key press. Throws if control is not allowed. */
    fun ensureCanAct() {
        // Wait (up to 5 s) while the user's finger is on the STOP button.
        var waited = 0
        while (holdGestures && mode == Mode.ACTIVE && waited < 5000) {
            Thread.sleep(50); waited += 50
        }
        when (mode) {
            Mode.OFF -> throw ActionError(McpHandler.STOPPED_TEXT)
            Mode.PAUSED -> throw ActionError("SAFETY PAUSE active: ${pauseReason ?: "sensitive screen"}. Wait for the user.")
            Mode.ACTIVE -> if (holdGestures) throw ActionError("The user is touching the STOP button. Wait.")
        }
    }

    fun stopButtonBounds(): Rect? = if (::overlay.isInitialized) overlay.stopBounds else null

    // ---- Relay callbacks -------------------------------------------------

    override fun onRequest(rid: String, msg: JSONObject) {
        if (mode == Mode.OFF) return
        val ex = worker
        try {
            ex.execute {
                val reply = mcp.handle(msg)
                if (mode != Mode.OFF) relay.send(rid, reply)
            }
        } catch (_: RejectedExecutionException) {
            // Stopped in the meantime: ignore.
        }
    }

    override fun onStatus(connected: Boolean, text: String) {
        this.connected = connected
        connectionText = text
        notifyChanged()
    }

    // ---- Continuous safety watch ----------------------------------------

    private val safetyCheck = Runnable {
        if (mode != Mode.ACTIVE) return@Runnable
        val reason = try { safety.check() } catch (_: Exception) { null }
        if (reason != null) main.post { pause(reason) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (mode != Mode.ACTIVE || !::safetyHandler.isInitialized) return
        // Debounce: check the screen at most a few times per second.
        if (!safetyHandler.hasCallbacks(safetyCheck)) safetyHandler.postDelayed(safetyCheck, 250)
    }

    // ---- Screen helpers --------------------------------------------------

    /** Roots of all visible windows (apps, system UI), excluding Bridge's own overlays. */
    fun screenRoots(includeKeyboard: Boolean): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                if (w.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) continue
                if (!includeKeyboard && w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                w.root?.let { result.add(it) }
            }
        } catch (_: Exception) {}
        if (result.isEmpty()) rootInActiveWindow?.let { result.add(it) }
        return result
    }

    fun foregroundAppLabel(): String {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return "unknown"
        return safety.appLabel(pkg)
    }

    fun log(message: String) = ActivityLog.add(message)

    private fun notifyChanged() {
        main.post { listeners.forEach { it() } }
    }
}
