package com.vbkforge.bridge

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Simple list of what Bridge did, newest first, with timestamps. */
class LogActivity : Activity() {

    private lateinit var logText: TextView
    private val refresh: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityLog.init(this)
        setContentView(R.layout.activity_log)
        applySystemBarPadding(findViewById(R.id.root))
        logText = findViewById(R.id.logText)
    }

    override fun onResume() {
        super.onResume()
        ActivityLog.addListener(refresh)
        render()
    }

    override fun onPause() {
        ActivityLog.removeListener(refresh)
        super.onPause()
    }

    private fun render() {
        val lines = ActivityLog.readNewestFirst(this)
        logText.text = if (lines.isEmpty()) "Nothing yet." else lines.joinToString("\n")
    }
}
