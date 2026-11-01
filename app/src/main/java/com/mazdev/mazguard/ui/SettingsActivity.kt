package com.mazdev.mazguard.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mazdev.mazguard.BuildConfig
import com.mazdev.mazguard.R
import com.mazdev.mazguard.core.AppPrefs
import com.mazdev.mazguard.core.BlockListUpdater
import com.mazdev.mazguard.core.FilterEngine
import com.mazdev.mazguard.core.PinManager
import com.mazdev.mazguard.service.AdminReceiver
import com.mazdev.mazguard.service.FilterVpnService
import com.mazdev.mazguard.service.WatchdogJobService
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var lockContainer: View
    private lateinit var contentContainer: View
    private lateinit var pinField: EditText
    private lateinit var pinError: TextView

    private lateinit var swSafeSearch: SwitchMaterial
    private lateinit var swKeywords: SwitchMaterial
    private lateinit var swDoh: SwitchMaterial
    private lateinit var swProtectSettings: SwitchMaterial
    private lateinit var swProtectVpn: SwitchMaterial
    private lateinit var swAutoUpdate: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = AppPrefs.get(this)
        lockContainer = findViewById(R.id.lock_container)
        contentContainer = findViewById(R.id.settings_container)
        pinField = findViewById(R.id.et_pin)
        pinError = findViewById(R.id.tv_pin_error)

        findViewById<Button>(R.id.btn_unlock).setOnClickListener { attemptUnlock() }
        pinField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptUnlock()
                true
            } else {
                false
            }
        }

        swSafeSearch = findViewById(R.id.sw_safe_search)
        swKeywords = findViewById(R.id.sw_keywords)
        swDoh = findViewById(R.id.sw_doh)
        swProtectSettings = findViewById(R.id.sw_protect_settings)
        swProtectVpn = findViewById(R.id.sw_protect_vpn)
        swAutoUpdate = findViewById(R.id.sw_autoupdate)

        if (prefs.hasPin) {
            showLock()
        } else {
            unlock()
        }
    }

    private fun showLock() {
        lockContainer.visibility = View.VISIBLE
        contentContainer.visibility = View.GONE
        pinField.requestFocus()
    }

    private fun attemptUnlock() {
        val wait = PinManager.lockRemainingMs(prefs)
        if (wait > 0) {
            pinError.text = PinUi.errorText(prefs)
            return
        }
        val pin = pinField.text.toString()
        if (PinManager.attempt(prefs, pin)) {
            pinField.setText("")
            unlock()
        } else {
            pinField.setText("")
            pinError.text = PinUi.errorText(prefs) ?: getString(R.string.pin_wrong)
        }
    }

    private fun unlock() {
        lockContainer.visibility = View.GONE
        contentContainer.visibility = View.VISIBLE
        bindSwitches()
        bindButtons()
        updateInfo()
    }

    private fun bindSwitches() {
        swSafeSearch.isChecked = prefs.safeSearch
        swKeywords.isChecked = prefs.keywordFilter
        swDoh.isChecked = prefs.blockDoh
        swProtectSettings.isChecked = prefs.protectSettings
        swProtectVpn.isChecked = prefs.protectVpnSettings
        swAutoUpdate.isChecked = prefs.autoUpdate

        swSafeSearch.setOnCheckedChangeListener { _, value ->
            prefs.safeSearch = value
            FilterEngine.refreshRules(prefs)
        }
        swKeywords.setOnCheckedChangeListener { _, value ->
            prefs.keywordFilter = value
            FilterEngine.refreshRules(prefs)
        }
        swDoh.setOnCheckedChangeListener { _, value ->
            prefs.blockDoh = value
            restartTunnel()
        }
        swProtectSettings.setOnCheckedChangeListener { _, value -> prefs.protectSettings = value }
        swProtectVpn.setOnCheckedChangeListener { _, value -> prefs.protectVpnSettings = value }
        swAutoUpdate.setOnCheckedChangeListener { _, value -> prefs.autoUpdate = value }
    }

    private fun bindButtons() {
        findViewById<Button>(R.id.btn_add_allow).setOnClickListener {
            askDomain(getString(R.string.allow_prompt)) { domain ->
                prefs.addAllowed(domain)
                FilterEngine.refreshRules(prefs)
                updateInfo()
            }
        }

        findViewById<Button>(R.id.btn_clear_allow).setOnClickListener {
            prefs.allowedDomains = HashSet()
            FilterEngine.refreshRules(prefs)
            updateInfo()
        }

        findViewById<Button>(R.id.btn_change_pin).setOnClickListener {
            PinUi.askNewPin(this) {
                Toast.makeText(this, R.string.pin_changed, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_update_list).setOnClickListener { updateList() }

        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (t: Throwable) {
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }

        findViewById<Button>(R.id.btn_deactivate).setOnClickListener { confirmDeactivate() }
    }

    private fun updateInfo() {
        findViewById<TextView>(R.id.tv_allow).text =
            getString(R.string.allow_count, prefs.allowedDomains.size)

        val updated = if (prefs.listUpdatedAt == 0L) {
            getString(R.string.never)
        } else {
            android.text.format.DateFormat.format("d MMM yyyy HH:mm", prefs.listUpdatedAt).toString()
        }
        findViewById<TextView>(R.id.tv_list_status).text =
            getString(R.string.list_status, prefs.listCount, updated)

        findViewById<TextView>(R.id.tv_version).text =
            getString(R.string.version_line, BuildConfig.VERSION_NAME)
    }

    private fun askDomain(title: String, onDomain: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "example.com"
            isSingleLine = true
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val domain = FilterEngine.normalizeDomain(input.text.toString())
                if (domain == null) {
                    input.error = getString(R.string.invalid_domain)
                    return@setOnClickListener
                }
                onDomain(domain)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateList() {
        Toast.makeText(this, R.string.updating_list, Toast.LENGTH_SHORT).show()
        thread(name = "mazguard-list-update") {
            val result = runCatching { BlockListUpdater.update(this, prefs) }
            runOnUiThread {
                if (result.isSuccess) {
                    Toast.makeText(
                        this,
                        getString(R.string.list_updated, result.getOrDefault(0)),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, R.string.list_update_failed, Toast.LENGTH_LONG).show()
                }
                updateInfo()
            }
        }
    }

    private fun restartTunnel() {
        if (!prefs.guardEnabled) return
        FilterVpnService.start(this)
    }

    private fun confirmDeactivate() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.deactivate_title)
            .setMessage(R.string.deactivate_message)
            .setPositiveButton(R.string.deactivate) { _, _ -> deactivate() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deactivate() {
        prefs.guardEnabled = false
        FilterVpnService.stop(this)
        WatchdogJobService.cancel(this)
        val dpm = getSystemService(DevicePolicyManager::class.java)
        runCatching {
            dpm?.removeActiveAdmin(ComponentName(this, AdminReceiver::class.java))
        }
        Toast.makeText(this, R.string.deactivated, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onBackPressed() {
        if (contentContainer.visibility != View.VISIBLE) {
            finish()
        } else {
            super.onBackPressed()
        }
    }
}
