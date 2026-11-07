package com.vbkforge.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/** Thrown when an action cannot be done; the message is shown to Claude. */
class ActionError(message: String) : Exception(message)

/**
 * Everything Bridge can do on the screen. All coordinates exchanged with Claude are in
 * "screenshot pixels" (the screen scaled down so the long side is at most 1280 px).
 */
class Actions(private val svc: BridgeService) {

    /** Screenshot pixels per real screen pixel. Updated by every screenshot. */
    @Volatile private var scale: Float = defaultScale()

    private val direct = Executor { it.run() }

    private fun realScreen(): Rect =
        svc.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds

    private fun defaultScale(): Float {
        val r = realScreen()
        return minOf(1f, MAX_SIDE.toFloat() / max(r.width(), r.height()))
    }

    private fun imageSize(): Pair<Int, Int> {
        val r = realScreen()
        return (r.width() * scale).roundToInt() to (r.height() * scale).roundToInt()
    }

    private fun toReal(v: Number): Float = v.toFloat() / scale
    private fun toImage(v: Int): Int = (v * scale).roundToInt()

    // ---- Screenshot ------------------------------------------------------

    data class Shot(val base64Jpeg: String, val width: Int, val height: Int)

    fun screenshot(): Shot {
        val full = captureBitmap()
        try {
            val longSide = max(full.width, full.height)
            // Keep the image small enough for Claude's tool-result limit.
            val attempts = listOf(MAX_SIDE to 70, MAX_SIDE to 50, 1024 to 50, 800 to 45)
            var last: Shot? = null
            for ((side, quality) in attempts) {
                val s = minOf(1f, side.toFloat() / longSide)
                val w = (full.width * s).roundToInt()
                val h = (full.height * s).roundToInt()
                val scaled = if (s < 1f) Bitmap.createScaledBitmap(full, w, h, true) else full
                val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                if (scaled !== full) scaled.recycle()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                scale = s
                last = Shot(b64, w, h)
                if (b64.length <= MAX_BASE64_CHARS) break
            }
            return last!!
        } finally {
            full.recycle()
        }
    }

    private fun captureBitmap(): Bitmap {
        repeat(4) {
            val latch = CountDownLatch(1)
            var bitmap: Bitmap? = null
            var errorCode = 0
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, direct, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        bitmap = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                    } finally {
                        hb.close()
                        latch.countDown()
                    }
                }

                override fun onFailure(code: Int) {
                    errorCode = code
                    latch.countDown()
                }
            })
            if (!latch.await(5, TimeUnit.SECONDS)) throw ActionError("Screenshot timed out.")
            bitmap?.let { return it }
            if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                Thread.sleep(400) // Android allows about 3 screenshots per second
            } else {
                throw ActionError("Android refused the screenshot (error code $errorCode). The screen may be protected (for example a secure app).")
            }
        }
        throw ActionError("Screenshot failed: too many screenshots in a short time. Wait a moment and try again.")
    }

    // ---- Read screen -----------------------------------------------------

    fun readScreen(): String {
        val (w, h) = imageSize()
        val sb = StringBuilder()
        sb.append("Screen ${w}x$h (screenshot pixels). Foreground app: ${svc.foregroundAppLabel()}.\n")
        sb.append("Elements: \"text\" [description] type flags center=(x,y)\n")
        var count = 0
        val rect = Rect()
        for (root in svc.screenRoots(includeKeyboard = false)) {
            if (root.packageName?.toString() == svc.packageName) continue
            sb.append("-- window: ${svc.safety.appLabel(root.packageName?.toString() ?: "?")}\n")
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            stack.addLast(root to 0)
            var visited = 0
            while (stack.isNotEmpty() && visited < 3000 && count < MAX_ELEMENTS) {
                val (node, depth) = stack.removeLast()
                visited++
                // Push children in reverse so they come out in screen order.
                if (depth < 80) for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it to depth + 1) }
                if (!node.isVisibleToUser) continue
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val interesting = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable || node.isCheckable
                if (!interesting) continue
                node.getBoundsInScreen(rect)
                if (rect.isEmpty) continue
                val flags = buildList {
                    if (node.isClickable) add("clickable")
                    if (node.isEditable) add("editable")
                    if (node.isFocused) add("focused")
                    if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                    if (node.isScrollable) add("scrollable")
                    if (!node.isEnabled) add("disabled")
                }.joinToString(",")
                val type = node.className?.toString()?.substringAfterLast('.') ?: "View"
                sb.append("\"").append(text.take(120)).append("\"")
                if (desc.isNotEmpty() && desc != text) sb.append(" [").append(desc.take(120)).append("]")
                sb.append(' ').append(type)
                if (flags.isNotEmpty()) sb.append(' ').append(flags)
                sb.append(" center=(").append(toImage(rect.centerX())).append(',').append(toImage(rect.centerY())).append(")\n")
                count++
            }
        }
        if (count >= MAX_ELEMENTS) sb.append("(list cut at $MAX_ELEMENTS elements)\n")
        if (count == 0) sb.append("(no readable elements — take a screenshot instead)\n")
        return sb.toString()
    }

    // ---- Gestures --------------------------------------------------------

    fun tap(x: Int, y: Int) {
        val (rx, ry) = checkPoint(x, y)
        val path = Path().apply { moveTo(rx, ry) }
        dispatch(path, 60)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        val (ax, ay) = checkPoint(x1, y1)
        val (bx, by) = checkPoint(x2, y2)
        val path = Path().apply { moveTo(ax, ay); lineTo(bx, by) }
        dispatch(path, durationMs.coerceIn(50, 3000))
    }

    private fun checkPoint(x: Int, y: Int): Pair<Float, Float> {
        val (w, h) = imageSize()
        if (x < 0 || y < 0 || x >= w || y >= h) throw ActionError("Point ($x, $y) is outside the screen (${w}x$h). Take a new screenshot.")
        val rx = toReal(x)
        val ry = toReal(y)
        val stop = svc.stopButtonBounds()
        if (stop != null && stop.contains(rx.roundToInt(), ry.roundToInt())) {
            throw ActionError("That point is on the user's red STOP button. Never touch it.")
        }
        return rx to ry
    }

    private fun dispatch(path: Path, durationMs: Long) {
        svc.ensureCanAct()
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        val started = svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { ok = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription) { latch.countDown() }
        }, null)
        if (!started) throw ActionError("Android did not accept the gesture.")
        if (!latch.await(durationMs + 3000, TimeUnit.MILLISECONDS)) throw ActionError("Gesture timed out.")
        if (!ok) throw ActionError("The gesture was cancelled (the user may have touched the screen).")
        settle()
    }

    // ---- Typing ----------------------------------------------------------

    fun typeText(text: String, submit: Boolean) {
        svc.ensureCanAct()
        val field = svc.screenRoots(includeKeyboard = false).firstNotNullOfOrNull { root ->
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        } ?: throw ActionError("No text field is focused. Tap the text field first, then call type_text.")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw ActionError("The focused field did not accept the text.")
        }
        if (submit) {
            svc.ensureCanAct()
            if (!field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                throw ActionError("Text was typed, but this field has no Enter/Search action. Tap the on-screen button instead.")
            }
        }
        settle()
    }

    // ---- Global actions --------------------------------------------------

    fun back() {
        svc.ensureCanAct()
        if (!svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) throw ActionError("Back did not work.")
        settle()
    }

    fun home() {
        svc.ensureCanAct()
        if (!svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) throw ActionError("Home did not work.")
        settle()
    }

    // ---- Apps ------------------------------------------------------------

    data class App(val label: String, val pkg: String)

    @Suppress("DEPRECATION")
    fun installedApps(): List<App> {
        val pm = svc.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun findApp(name: String): App? {
        val q = name.trim().lowercase()
        if (q.isEmpty()) return null
        val apps = installedApps()
        return apps.firstOrNull { it.label.lowercase() == q || it.pkg.lowercase() == q }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.label.lowercase().contains(q) }
    }

    fun launch(app: App) {
        svc.ensureCanAct()
        val intent = svc.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: throw ActionError("${app.label} cannot be opened from a launcher.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            svc.startActivity(intent)
        } catch (e: Exception) {
            throw ActionError("Could not open ${app.label}: ${e.message}")
        }
        Thread.sleep(1200) // give the app time to appear
    }

    /** Short wait so the next screenshot shows the result of the action. */
    private fun settle() = Thread.sleep(SETTLE_MS)

    companion object {
        const val MAX_SIDE = 1280
        const val MAX_BASE64_CHARS = 120_000 // Claude.ai tool results are limited to ~150,000 characters
        const val MAX_ELEMENTS = 250
        const val SETTLE_MS = 500L
    }
}
