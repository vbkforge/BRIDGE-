package com.vbkforge.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Answers Claude's MCP (Model Context Protocol) JSON-RPC requests that arrive through the relay.
 * This is what Claude sees as the "Bridge" connector and its tools.
 */
class McpHandler(private val svc: BridgeService) {

    private val supportedVersions = setOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")

    private val instructions = """
        Bridge lets you control the user's Android tablet. Work in small steps:
        1) call screenshot (and/or read_screen for exact text and element centers),
        2) do ONE action: tap, swipe, type_text, go_back, go_home or open_app,
        3) take a new screenshot to check the result before the next action.
        All x/y coordinates are pixels of the most recent screenshot image.
        A red round STOP button floats on the screen: it belongs to the user. Never tap or drag it.
        If a tool result says SAFETY PAUSE or STOPPED, stop calling tools and tell the user it is their turn.
        Never try to get around a safety pause. Bridge pauses on password, OTP, payment and banking screens on purpose.
    """.trimIndent()

    fun handle(msg: JSONObject): JSONObject {
        val id = msg.opt("id")
        val method = msg.optString("method")
        val params = msg.optJSONObject("params") ?: JSONObject()
        return try {
            when (method) {
                "initialize" -> ok(id, initialize(params))
                "ping" -> ok(id, JSONObject())
                "tools/list" -> ok(id, JSONObject().put("tools", toolList()))
                "tools/call" -> ok(id, callTool(params))
                "resources/list" -> ok(id, JSONObject().put("resources", JSONArray()))
                "prompts/list" -> ok(id, JSONObject().put("prompts", JSONArray()))
                else -> error(id, -32601, "Method not found: $method")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ok(id, textResult(STOPPED_TEXT, isError = true))
        } catch (e: Exception) {
            error(id, -32603, "Internal error: ${e.message}")
        }
    }

    private fun initialize(params: JSONObject): JSONObject {
        val requested = params.optString("protocolVersion")
        val version = if (requested in supportedVersions) requested else "2025-06-18"
        return JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "bridge-tablet").put("version", BuildConfig.VERSION_NAME))
            .put("instructions", instructions)
    }

    // ---- Tools -----------------------------------------------------------

    private fun toolList(): JSONArray = JSONArray()
        .put(tool("screenshot",
            "Take a screenshot of the tablet screen. Returns a JPEG image and its size. Use the image's pixel coordinates for tap and swipe.",
            props()))
        .put(tool("read_screen",
            "List the visible on-screen elements (text, buttons, fields) with their center coordinates in screenshot pixels. Faster and more exact than a screenshot for finding where to tap.",
            props()))
        .put(tool("tap",
            "Tap one point on the screen. x and y are pixels of the latest screenshot.",
            props("x" to intProp("X in screenshot pixels"), "y" to intProp("Y in screenshot pixels")),
            "x", "y"))
        .put(tool("swipe",
            "Swipe or drag from (x1,y1) to (x2,y2) in screenshot pixels. To scroll a page DOWN, swipe UP (y2 < y1). " +
                "Use the same start and end point with duration_ms 800 for a long-press.",
            props(
                "x1" to intProp("Start X"), "y1" to intProp("Start Y"),
                "x2" to intProp("End X"), "y2" to intProp("End Y"),
                "duration_ms" to intProp("Duration in milliseconds, 50 to 3000 (default 400)")
            ),
            "x1", "y1", "x2", "y2"))
        .put(tool("type_text",
            "Replace the text of the currently focused text field (tap the field first). Set submit=true to press the keyboard's Enter/Search/Go action afterwards.",
            props(
                "text" to JSONObject().put("type", "string").put("description", "Text to put in the field"),
                "submit" to JSONObject().put("type", "boolean").put("description", "Press Enter/Search after typing (default false)")
            ),
            "text"))
        .put(tool("go_back", "Press the Android Back button.", props()))
        .put(tool("go_home", "Go to the Android home screen.", props()))
        .put(tool("open_app",
            "Open an installed app by its name as shown on the home screen (for example \"Chrome\", \"Settings\", \"YouTube\").",
            props("name" to JSONObject().put("type", "string").put("description", "App name")),
            "name"))

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()

        // Gate 1: control must be on and not paused.
        when (svc.mode) {
            BridgeService.Mode.OFF -> return textResult(STOPPED_TEXT, isError = true)
            BridgeService.Mode.PAUSED -> return textResult(pausedText(svc.pauseReason), isError = true)
            BridgeService.Mode.ACTIVE -> Unit
        }
        // Gate 2: never look at or touch a sensitive screen.
        svc.safety.check()?.let { reason ->
            svc.pause(reason)
            return textResult(pausedText(reason), isError = true)
        }

        return try {
            when (name) {
                "screenshot" -> {
                    val shot = svc.actions.screenshot()
                    svc.log("Claude: screenshot")
                    JSONObject().put("content", JSONArray()
                        .put(JSONObject().put("type", "image").put("data", shot.base64Jpeg).put("mimeType", "image/jpeg"))
                        .put(JSONObject().put("type", "text").put("text",
                            "Screenshot ${shot.width}x${shot.height} pixels. Foreground app: ${svc.foregroundAppLabel()}. " +
                                "Use these pixel coordinates for tap/swipe.")))
                }
                "read_screen" -> {
                    val text = svc.actions.readScreen()
                    svc.log("Claude: read screen")
                    textResult(text)
                }
                "tap" -> {
                    val x = args.reqInt("x"); val y = args.reqInt("y")
                    svc.log("Claude: tap ($x, $y)")
                    svc.actions.tap(x, y)
                    textResult("Tapped ($x, $y).")
                }
                "swipe" -> {
                    val x1 = args.reqInt("x1"); val y1 = args.reqInt("y1")
                    val x2 = args.reqInt("x2"); val y2 = args.reqInt("y2")
                    val d = args.optLong("duration_ms", 400)
                    svc.log("Claude: swipe ($x1, $y1) → ($x2, $y2), $d ms")
                    svc.actions.swipe(x1, y1, x2, y2, d)
                    textResult("Swiped ($x1, $y1) → ($x2, $y2).")
                }
                "type_text" -> {
                    if (!args.has("text")) throw ActionError("Missing argument: text")
                    val text = args.optString("text")
                    val submit = args.optBoolean("submit", false)
                    svc.log("Claude: type text (${text.length} characters)${if (submit) " + Enter" else ""}")
                    svc.actions.typeText(text, submit)
                    textResult("Typed ${text.length} characters${if (submit) " and pressed Enter" else ""}.")
                }
                "go_back" -> { svc.log("Claude: back"); svc.actions.back(); textResult("Pressed Back.") }
                "go_home" -> { svc.log("Claude: home"); svc.actions.home(); textResult("Went to the home screen.") }
                "open_app" -> {
                    val q = args.optString("name")
                    val app = svc.actions.findApp(q) ?: run {
                        val names = svc.actions.installedApps().joinToString(", ") { it.label }
                        throw ActionError("No app named \"$q\". Installed apps: $names")
                    }
                    if (svc.safety.isFinancialApp(app.pkg)) {
                        val reason = "Claude tried to open ${app.label}, a banking or payment app"
                        svc.pause(reason)
                        return textResult(pausedText(reason), isError = true)
                    }
                    svc.log("Claude: open app ${app.label}")
                    svc.actions.launch(app)
                    textResult("Opened ${app.label}.")
                }
                else -> textResult("Unknown tool: $name", isError = true)
            }
        } catch (e: ActionError) {
            if (svc.mode == BridgeService.Mode.OFF) return textResult(STOPPED_TEXT, isError = true)
            svc.log("Action failed: ${e.message}")
            textResult(e.message ?: "Action failed.", isError = true)
        }
    }

    // ---- Helpers ---------------------------------------------------------

    private fun pausedText(reason: String?): String =
        "SAFETY PAUSE: Bridge found ${reason ?: "a sensitive screen"}. Control has been handed back to the user. " +
            "Stop calling tools now. Tell the user it is their turn: they should finish this step themselves, " +
            "tap Resume in Bridge, and then ask you to continue."

    private fun tool(name: String, description: String, properties: JSONObject, vararg required: String): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", properties)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
        return JSONObject().put("name", name).put("description", description).put("inputSchema", schema)
    }

    private fun props(vararg p: Pair<String, JSONObject>): JSONObject =
        JSONObject().apply { p.forEach { put(it.first, it.second) } }

    private fun intProp(description: String) = JSONObject().put("type", "integer").put("description", description)

    private fun JSONObject.reqInt(key: String): Int {
        if (!has(key)) throw ActionError("Missing argument: $key")
        val v = optDouble(key, Double.NaN)
        if (v.isNaN()) throw ActionError("Argument $key must be a number")
        return v.toInt()
    }

    private fun textResult(text: String, isError: Boolean = false): JSONObject {
        val r = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        if (isError) r.put("isError", true)
        return r
    }

    private fun ok(id: Any?, result: JSONObject) =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)

    private fun error(id: Any?, code: Int, message: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    companion object {
        const val STOPPED_TEXT =
            "STOPPED: the user pressed STOP in Bridge, so control of the tablet is off. Stop calling tools. " +
                "If the user wants to continue they will tap Start in Bridge and ask you."
    }
}
