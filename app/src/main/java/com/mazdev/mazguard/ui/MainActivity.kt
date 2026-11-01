package com.mazdev.mazguard.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.mazdev.mazguard.R
import com.mazdev.mazguard.core.AppPrefs
import com.mazdev.mazguard.core.BlockLog
import com.mazdev.mazguard.core.FilterEngine
import com.mazdev.mazguard.service.AdminReceiver
import com.mazdev.mazguard.service.FilterVpnService
import com.mazdev.mazguard.service.GuardAccessibilityService
import com.mazdev.mazguard.service.WatchdogJobService

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var statusView: TextView
    private lateinit var statusDetailView: TextView
    private lateinit var statsView: TextView
    private lateinit var actionButton: MaterialButton
    private lateinit var checklist: LinearLayout
    private lateinit var privateDnsView: TextView
    private lateinit var noteView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var waitingForUser = false
    private var lastStep = ""
    private var lastStepAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPrefs.get(this)
        BlockLog.attach(prefs)

        statusView = findViewById(R.id.tv_status)
        statusDetailView = findViewById(R.id.tv_status_detail)
        statsView = findViewById(R.id.tv_stats)
        actionButton = findViewById(R.id.btn_action)
        checklist = findViewById(R.id.checklist)
        privateDnsView = findViewById(R.id.tv_private_dns)
        noteView = findViewById(R.id.tv_note)

        actionButton.setOnClickListener { onAction() }
        findViewById<MaterialButton>(R.id.btn_settings).setOnClickListener { openSettings() }
        findViewById<MaterialButton>(R.id.btn_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.removeCallbacks(ticker)
        handler.post(ticker)

        if (waitingForUser) {
            handler.postDelayed({
                if (waitingForUser) {
                    waitingForUser = false
                    continueSetup()
                }
            }, 700)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        BlockLog.flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun onAction() {
        if (isActive()) {
            openSettings()
            return
        }
        continueSetup()
    }

    private fun continueSetup() {
        if (!prefs.hasPin) {
            PinUi.askNewPin(this) { handler.post { continueSetup() } }
            return
        }

        val consent = vpnConsent()
        if (consent != null) {
            if (!stepOnce("vpn")) return
            waitingForUser = true
            runCatching { startActivityForResult(consent, REQ_VPN) }
            return
        }

        if (!isAdminActive()) {
            if (!stepOnce("admin")) return
            waitingForUser = true
            runCatching { startActivityForResult(adminIntent(), REQ_ADMIN) }
            return
        }

        if (!isAccessibilityEnabled()) {
            if (!stepOnce("a11y")) return
            waitingForUser = true
            Toast.makeText(this, R.string.toast_enable_a11y, Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            return
        }

        activate()
    }

    private fun activate() {
        prefs.guardEnabled = true
        if (prefs.activatedAt == 0L) prefs.activatedAt = System.currentTimeMillis()
        FilterVpnService.start(this)
        WatchdogJobService.schedule(this)
        Toast.makeText(this, R.string.toast_activated, Toast.LENGTH_LONG).show()
        handler.postDelayed({ refresh() }, 1500)
    }

    private fun stepOnce(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (key == lastStep && now - lastStepAt < 1800) return false
        lastStep = key
        lastStepAt = now
        return true
    }

    private fun openSettings() {
        if (!prefs.hasPin) {
            PinUi.askNewPin(this) { openSettings() }
            return
        }
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun isActive(): Boolean =
        prefs.guardEnabled && FilterVpnService.running && isAdminActive() && isAccessibilityEnabled()

    private fun refresh() {
        val active = isActive()
        val running = FilterVpnService.running

        if (active) {
            statusView.setText(R.string.status_on)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.ok))
            statusDetailView.setText(R.string.status_on_detail)
        } else if (prefs.guardEnabled && !running) {
            statusView.setText(R.string.status_degraded)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.warn))
            statusDetailView.setText(R.string.status_degraded_detail)
        } else {
            statusView.setText(R.string.status_off)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.danger))
            statusDetailView.setText(R.string.status_off_detail)
        }

        actionButton.setText(if (active) R.string.open_settings else R.string.activate)
        updateStats()
        buildChecklist()

        privateDnsView.visibility = if (privateDnsEnabled()) View.VISIBLE else View.GONE
        noteView.text = getString(
            R.string.footnote,
            prefs.listCount,
            if (prefs.listUpdatedAt == 0L) getString(R.string.never) else android.text.format.DateFormat.format("d MMM yyyy", prefs.listUpdatedAt).toString()
        )
    }

    private fun updateStats() {
        val blocked = BlockLog.totalCount()
        statsView.text = getString(R.string.stats, blocked, FilterEngine.count)
    }

    private fun buildChecklist() {
        checklist.removeAllViews()
        addRow(getString(R.string.step_pin), prefs.hasPin) {
            PinUi.askNewPin(this) { refresh() }
        }
        addRow(getString(R.string.step_vpn), vpnConsent() == null) { grantVpn() }
        addRow(getString(R.string.step_admin), isAdminActive()) { grantAdmin() }
        addRow(getString(R.string.step_accessibility), isAccessibilityEnabled()) {
            Toast.makeText(this, R.string.toast_enable_a11y, Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
    }

    private fun addRow(title: String, done: Boolean, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, PinUi.dp(this@MainActivity, 10), 0, PinUi.dp(this@MainActivity, 10))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val mark = TextView(this).apply {
            text = if (done) "\u2713" else "\u25CB"
            textSize = 17f
            setTextColor(ContextCompat.getColor(this@MainActivity, if (done) R.color.ok else R.color.warn))
            setPadding(0, 0, PinUi.dp(this@MainActivity, 12), 0)
        }
        val label = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val state = TextView(this).apply {
            text = if (done) getString(R.string.done) else getString(R.string.fix)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.brand))
        }
        row.addView(mark)
        row.addView(label)
        row.addView(state)
        checklist.addView(row)
    }

    private fun grantVpn() {
        val consent = vpnConsent()
        if (consent == null) {
            refresh()
            return
        }
        runCatching { startActivityForResult(consent, REQ_VPN) }
    }

    private fun grantAdmin() {
        runCatching { startActivityForResult(adminIntent(), REQ_ADMIN) }
    }

    private fun adminIntent(): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, AdminReceiver::class.java))
        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explanation))
    }

    private fun vpnConsent(): Intent? = try {
        VpnService.prepare(this)
    } catch (t: Throwable) {
        null
    }

    private fun isAdminActive(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java) ?: return false
        return runCatching {
            dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
        }.getOrDefault(false)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val component = ComponentName(this, GuardAccessibilityService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    private fun privateDnsEnabled(): Boolean {
        val mode = runCatching {
            Settings.Global.getString(contentResolver, "private_dns_mode")
        }.getOrNull() ?: "off"
        return mode != "off"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATIONS
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN || requestCode == REQ_ADMIN) {
            handler.postDelayed({ refresh() }, 400)
        }
    }

    companion object {
        private const val REQ_VPN = 100
        private const val REQ_ADMIN = 101
        private const val REQ_NOTIFICATIONS = 102
    }
}
