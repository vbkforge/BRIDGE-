package com.vbkforge.bridge

import android.content.Context
import java.security.SecureRandom

/** Small saved settings: relay address, secret token, stop-button position. */
object Prefs {
    private const val FILE = "bridge"
    private const val KEY_HOST = "relay_host"
    private const val KEY_TOKEN = "token"
    private const val KEY_X = "stop_x"
    private const val KEY_Y = "stop_y"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Host only, e.g. "bridge-relay.yourname.workers.dev". */
    fun relayHost(c: Context): String = sp(c).getString(KEY_HOST, "") ?: ""

    fun setRelayHost(c: Context, raw: String) {
        sp(c).edit().putString(KEY_HOST, normalizeHost(raw)).apply()
    }

    fun normalizeHost(raw: String): String =
        raw.trim()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("wss://").removePrefix("ws://")
            .substringBefore('/')
            .trim()
            .lowercase()

    /** Long random secret that ties this tablet to your Claude connector. Created once. */
    fun token(c: Context): String {
        val existing = sp(c).getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        val token = (1..32).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        sp(c).edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    fun connectorUrl(c: Context): String? {
        val host = relayHost(c)
        return if (host.isBlank()) null else "https://$host/mcp/${token(c)}"
    }

    fun deviceUrl(c: Context): String? {
        val host = relayHost(c)
        return if (host.isBlank()) null else "wss://$host/device/${token(c)}"
    }

    /** Saved stop-button position, or null if never moved. */
    fun stopButtonPosition(c: Context): Pair<Int, Int>? {
        val p = sp(c)
        if (!p.contains(KEY_X) || !p.contains(KEY_Y)) return null
        return p.getInt(KEY_X, 0) to p.getInt(KEY_Y, 0)
    }

    fun saveStopButtonPosition(c: Context, x: Int, y: Int) {
        // commit() (not apply) so the position survives even if the app is killed right after.
        sp(c).edit().putInt(KEY_X, x).putInt(KEY_Y, y).commit()
    }
}
