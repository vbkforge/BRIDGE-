package com.vbkforge.bridge

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/** Timestamped list of what Bridge did. Stored in a private file, newest entries kept. */
object ActivityLog {
    private const val FILE = "activity.log"
    private const val KEEP_LINES = 1000
    private const val TRIM_AT_LINES = 1300

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val lock = Any()
    private var appContext: Context? = null
    private var linesSinceTrimCheck = 0

    fun init(c: Context) {
        appContext = c.applicationContext
    }

    fun add(message: String) {
        val c = appContext ?: return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "$stamp  ${message.replace('\n', ' ')}\n"
        synchronized(lock) {
            val f = File(c.filesDir, FILE)
            f.appendText(line)
            if (++linesSinceTrimCheck >= 100) {
                linesSinceTrimCheck = 0
                val lines = f.readLines()
                if (lines.size > TRIM_AT_LINES) {
                    f.writeText(lines.takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
                }
            }
        }
        listeners.forEach { it() }
    }

    /** All entries, newest first. */
    fun readNewestFirst(c: Context): List<String> = synchronized(lock) {
        val f = File(c.applicationContext.filesDir, FILE)
        if (!f.exists()) emptyList() else f.readLines().filter { it.isNotBlank() }.reversed()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
}
