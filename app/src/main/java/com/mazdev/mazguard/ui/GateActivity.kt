package com.mazdev.mazguard.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mazdev.mazguard.R
import com.mazdev.mazguard.core.AppPrefs
import com.mazdev.mazguard.core.PinManager

/**
 * Full-screen PIN gate shown in front of protected system screens.
 * It cannot be dismissed with Back, so the only ways out are the correct PIN
 * or leaving with the Home button.
 */
class GateActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var pinField: EditText
    private lateinit var errorView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gate)

        prefs = AppPrefs.get(this)
        pinField = findViewById(R.id.et_pin)
        errorView = findViewById(R.id.tv_pin_error)

        findViewById<Button>(R.id.btn_unlock).setOnClickListener { attempt() }
        findViewById<Button>(R.id.btn_open_app).setOnClickListener {
            attempt(openApp = true)
        }
        pinField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attempt()
                true
            } else {
                false
            }
        }

        applyReason()
        pinField.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyReason()
    }

    private fun applyReason() {
        val reason = intent?.getStringExtra(EXTRA_REASON)
        findViewById<TextView>(R.id.tv_gate_subtitle).text = when (reason) {
            REASON_SETTINGS -> getString(R.string.gate_body_settings)
            REASON_BROWSER -> getString(R.string.gate_body_browser)
            else -> getString(R.string.gate_body)
        }
    }

    private fun attempt(openApp: Boolean = false) {
        val wait = PinManager.lockRemainingMs(prefs)
        if (wait > 0) {
            showError(PinUi.errorText(prefs) ?: getString(R.string.pin_wrong))
            return
        }
        if (prefs.hasPin && PinManager.attempt(prefs, pinField.text.toString())) {
            GateSession.grant()
            errorView.visibility = View.INVISIBLE
            if (openApp) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
            finishAndRemoveTask()
        } else {
            pinField.setText("")
            showError(PinUi.errorText(prefs) ?: getString(R.string.pin_wrong))
        }
    }

    private fun showError(message: String) {
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    override fun onBackPressed() {
        // Deliberately ignored: the gate must not be dismissed without the PIN.
        Toast.makeText(this, R.string.gate_back_hint, Toast.LENGTH_SHORT).show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            findViewById<View>(R.id.et_pin).requestFocus()
        }
    }

    companion object {
        const val EXTRA_REASON = "reason"
        const val REASON_SETTINGS = "settings"
        const val REASON_BROWSER = "browser"
    }
}
