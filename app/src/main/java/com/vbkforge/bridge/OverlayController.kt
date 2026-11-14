package com.vbkforge.bridge

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The floating red STOP button and the "Your turn" banner.
 *
 * Both are accessibility overlays (TYPE_ACCESSIBILITY_OVERLAY): they are drawn by the
 * accessibility service itself, sit above every app, and need no separate overlay permission.
 * All methods must be called on the main thread.
 */
class OverlayController(private val svc: BridgeService) {

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val density = svc.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private var stopView: View? = null
    private var stopParams: WindowManager.LayoutParams? = null
    private var banner: View? = null
    private var bannerText: TextView? = null

    /** Stop-button bounds in real screen pixels, readable from any thread. */
    @Volatile var stopBounds: Rect? = null
        private set

    // ---- Stop button -----------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    fun showStopButton() {
        if (stopView != null) return
        val size = dp(76)
        val view = TextView(svc).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFC62828.toInt())
                setStroke(dp(3), Color.WHITE)
            }
            elevation = dp(8).toFloat()
            contentDescription = "Stop Bridge control"
        }
        val screen = wm.maximumWindowMetrics.bounds
        val saved = Prefs.stopButtonPosition(svc)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, // screen stays on while control runs
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = saved?.first ?: (screen.width() - size - dp(24))
            y = saved?.second ?: (screen.height() / 3)
        }
        clamp(params, size)

        val slop = dp(14)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Freeze Claude's gestures while your finger is on the button, so an
                    // injected gesture can never cancel your tap.
                    svc.holdGestures = true
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        clamp(params, size)
                        try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        Prefs.saveStopButtonPosition(svc, params.x, params.y)
                        updateBounds(v)
                        svc.holdGestures = false
                    } else {
                        // A tap (or an interrupted tap) always stops. Fail safe.
                        svc.stopControl("You tapped the STOP button")
                    }
                    true
                }
                else -> true
            }
        }

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            svc.log("Could not show the STOP button: ${e.message}")
            return
        }
        stopView = view
        stopParams = params
        view.post { updateBounds(view) }
    }

    private fun clamp(p: WindowManager.LayoutParams, size: Int) {
        val screen = wm.maximumWindowMetrics.bounds
        p.x = p.x.coerceIn(0, maxOf(0, screen.width() - size))
        p.y = p.y.coerceIn(0, maxOf(0, screen.height() - size))
    }

    private fun updateBounds(v: View) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val pad = dp(12) // keep Claude's taps a little away from the button too
        stopBounds = Rect(loc[0] - pad, loc[1] - pad, loc[0] + v.width + pad, loc[1] + v.height + pad)
    }

    /** Re-clamps the button after a rotation so it never ends up off-screen. */
    fun onScreenChanged() {
        val v = stopView ?: return
        val p = stopParams ?: return
        clamp(p, p.width)
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
        v.post { updateBounds(v) }
    }

    fun hideStopButton() {
        stopView?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        stopView = null
        stopParams = null
        stopBounds = null
    }

    // ---- "Your turn" banner ---------------------------------------------

    fun showYourTurn(reason: String, stillSensitive: Boolean) {
        val message = (if (stillSensitive) "This screen is still sensitive. Leave it first, then tap Resume.\n\n" else "") +
            "Bridge paused because it found $reason.\n" +
            "Bridge will not look at or touch this screen. Finish this step yourself, then tap Resume " +
            "and ask Claude to continue."
        bannerText?.let { it.text = message; return }

        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFFFFE082.toInt())
                setStroke(dp(2), 0xFF5D4037.toInt())
            }
            elevation = dp(10).toFloat()
        }
        val title = TextView(svc).apply {
            text = "✋  YOUR TURN"
            setTextColor(0xFF3E2723.toInt())
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        val body = TextView(svc).apply {
            text = message
            setTextColor(0xFF3E2723.toInt())
            textSize = 16f
            setPadding(0, dp(6), 0, dp(10))
        }
        val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val stop = Button(svc).apply {
            text = "Stop"
            setOnClickListener { svc.stopControl("You tapped Stop on the safety message") }
        }
        val resume = Button(svc).apply {
            text = "Resume"
            setOnClickListener { svc.resume() }
        }
        row.addView(stop)
        row.addView(resume)
        box.addView(title)
        box.addView(body)
        box.addView(row)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
            horizontalMargin = 0.04f
        }
        try {
            wm.addView(box, params)
            banner = box
            bannerText = body
        } catch (e: Exception) {
            svc.log("Could not show the safety message: ${e.message}")
        }
    }

    fun hideYourTurn() {
        banner?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        banner = null
        bannerText = null
    }

    fun hideAll() {
        hideYourTurn()
        hideStopButton()
    }
}
