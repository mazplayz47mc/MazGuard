package com.mazdev.mazguard.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.mazdev.mazguard.R
import com.mazdev.mazguard.core.BlockLog
import java.text.DateFormat
import java.util.Date

class LogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        findViewById<MaterialButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btn_clear).setOnClickListener {
            BlockLog.clearRecent()
            render()
        }
        render()
    }

    private fun render() {
        val container = findViewById<LinearLayout>(R.id.log_container)
        val empty = findViewById<TextView>(R.id.tv_empty)
        container.removeAllViews()

        val entries = BlockLog.recent()
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        val formatter = DateFormat.getTimeInstance(DateFormat.SHORT)
        for (entry in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, PinUi.dp(this@LogActivity, 9), 0, PinUi.dp(this@LogActivity, 9))
            }
            val host = TextView(this).apply {
                text = entry.host
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val time = TextView(this).apply {
                text = formatter.format(Date(entry.time))
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            }
            row.addView(host)
            row.addView(time)
            container.addView(row)
        }

        findViewById<TextView>(R.id.tv_total).text =
            getString(R.string.log_total, BlockLog.totalCount())
    }
}
