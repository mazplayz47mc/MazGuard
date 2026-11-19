package com.mazdev.mazguard.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import com.google.android.material.card.MaterialCardView
import com.mazdev.mazguard.BuildConfig
import com.mazdev.mazguard.R
import com.mazdev.mazguard.core.AppPrefs
import com.mazdev.mazguard.core.AppUpdate
import com.mazdev.mazguard.core.BlockListUpdater
import com.mazdev.mazguard.core.BlockLog
import com.mazdev.mazguard.core.FilterEngine
import com.mazdev.mazguard.service.AdminReceiver
import com.mazdev.mazguard.service.FilterVpnService
import com.mazdev.mazguard.service.GuardHealth
import com.mazdev.mazguard.service.WatchdogJobService
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var statusView: TextView
    private lateinit var statusDetailView: TextView
    private lateinit var statsView: TextView
    private lateinit var actionButton: MaterialButton
    private lateinit var checklist: LinearLayout
    private lateinit var privateDnsView: TextView
    private lateinit var quoteCard: MaterialCardView
    private lateinit var quoteArabic: TextView
    private lateinit var quoteEnglish: TextView
    private lateinit var quoteSource: TextView
    private lateinit var updateCard: MaterialCardView
    private lateinit var updateProgress: TextView
    private lateinit var installUpdateButton: MaterialButton

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

    private val quoteIndex = AtomicInteger(0)
    private val quotes = arrayOf(
        Quote(R.string.quote_1_ar, R.string.quote_1_en, R.string.quote_1_src),
        Quote(R.string.quote_2_ar, R.string.quote_2_en, R.string.quote_2_src),
        Quote(R.string.quote_3_ar, R.string.quote_3_en, R.string.quote_3_src),
        Quote(R.string.quote_4_ar, R.string.quote_4_en, R.string.quote_4_src),
        Quote(R.string.quote_5_ar, R.string.quote_5_en, R.string.quote_5_src)
    )

    private data class Quote(
        val arabic: Int,
        val english: Int,
        val source: Int
    )

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
        quoteCard = findViewById(R.id.card_quote)
        quoteArabic = findViewById(R.id.tv_quote_arabic)
        quoteEnglish = findViewById(R.id.tv_quote_english)
        quoteSource = findViewById(R.id.tv_quote_source)
        updateCard = findViewById(R.id.card_app_update)
        updateProgress = findViewById(R.id.tv_update_progress)
        installUpdateButton = findViewById(R.id.btn_install_update)

        actionButton.setOnClickListener { onAction() }
        findViewById<MaterialButton>(R.id.btn_settings).setOnClickListener { openSettings() }
        findViewById<MaterialButton>(R.id.btn_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_update).setOnClickListener { updateBlocklist() }
        installUpdateButton.setOnClickListener { installAppUpdate() }

        requestNotificationPermission()
        showNextQuote()
    }

    override fun onResume() {
        super.onResume()
        runCatching { GuardHealth.check(this) }
        refresh()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        checkAppUpdate()

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

    private fun showNextQuote() {
        val now = System.currentTimeMillis()
        val lastTime = prefs.lastQuoteTime
        val lastIdx = prefs.lastQuoteIndex

        // Only rotate if 30+ minutes have passed since last quote shown
        if (now - lastTime < QUOTE_ROTATION_MS) {
            // Show the same quote if recently shown
            val q = quotes[if (lastIdx >= 0) lastIdx else 0]
            quoteArabic.text = getString(q.arabic)
            quoteEnglish.text = getString(q.english)
            quoteSource.text = getString(q.source)
            return
        }

        // Pick a new quote different from the last one
        val candidates = quotes.indices.filter { it != lastIdx }
        val nextIdx = candidates.random()
        prefs.lastQuoteIndex = nextIdx
        prefs.lastQuoteTime = now

        val q = quotes[nextIdx]
        quoteArabic.text = getString(q.arabic)
        quoteEnglish.text = getString(q.english)
        quoteSource.text = getString(q.source)
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
            PinUi.askChangePin(this) { openSettings() }
            return
        }
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun isActive(): Boolean =
        prefs.guardEnabled && FilterVpnService.running && isAdminActive() && isAccessibilityEnabled()

    private fun refresh() {
        val active = isActive()
        val adbOn = GuardHealth.adbEnabled(this)
        val privateDnsOn = GuardHealth.privateDnsBypassing(this)
        val guardDown = prefs.hasPin && !isAccessibilityEnabled()

        if (active && !adbOn && !privateDnsOn && !guardDown) {
            statusView.setText(R.string.status_on)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.ok))
            statusDetailView.setText(R.string.status_on_detail)
        } else if (prefs.guardEnabled) {
            statusView.setText(if (active) R.string.status_on else R.string.status_degraded)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.warn))
            statusDetailView.setText(
                when {
                    adbOn -> R.string.status_warn_adb
                    privateDnsOn -> R.string.status_warn_private_dns
                    guardDown -> R.string.status_warn_guard
                    else -> if (active) R.string.status_on_detail else R.string.status_degraded_detail
                }
            )
        } else {
            statusView.setText(R.string.status_off)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.danger))
            statusDetailView.setText(R.string.status_off_detail)
        }

        actionButton.setText(if (active) R.string.open_settings else R.string.activate)
        updateStats()
        buildChecklist()

        privateDnsView.visibility = if (privateDnsOn) View.VISIBLE else View.GONE
    }

    private fun updateStats() {
        val blocked = BlockLog.totalCount()
        statsView.text = getString(R.string.stats, blocked, FilterEngine.count)
    }

    private fun updateBlocklist() {
        Toast.makeText(this, R.string.updating_list, Toast.LENGTH_SHORT).show()
        kotlin.concurrent.thread(name = "mazguard-list-update") {
            val result = runCatching { BlockListUpdater.update(this, prefs) }
            handler.post {
                if (result.isSuccess) {
                    Toast.makeText(
                        this,
                        getString(R.string.list_updated, result.getOrDefault(0)),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, R.string.list_update_failed, Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
    }

    private fun checkAppUpdate() {
        val current = BuildConfig.VERSION_NAME
        kotlin.concurrent.thread(name = "mazguard-update-check") {
            val info = runCatching { AppUpdate.check(current) }.getOrNull()
            handler.post {
                if (info != null) {
                    updateCard.visibility = View.VISIBLE
                    // Find the TextView in the card's LinearLayout and update it
                    val card = findViewById<MaterialCardView>(R.id.card_app_update)
                    val ll = (0 until (card as android.view.ViewGroup).childCount)
                        .map { (card as android.view.ViewGroup).getChildAt(it) }
                        .filter { it is LinearLayout }
                        .firstOrNull() as LinearLayout?
                    if (ll != null) {
                        val tv = ll.findViewById<TextView>(android.R.id.text1)
                        if (tv != null) tv.text = getString(R.string.app_update_available, info.version)
                    }
                }
            }
        }
    }

    private fun installAppUpdate() {
        val current = BuildConfig.VERSION_NAME
        installUpdateButton.isEnabled = false
        installUpdateButton.text = getString(R.string.update_installing)
        updateProgress.visibility = View.VISIBLE
        updateProgress.text = getString(R.string.update_downloading)

        kotlin.concurrent.thread(name = "mazguard-update-install") {
            val info = runCatching { AppUpdate.check(current) }.getOrNull()
            if (info == null) {
                handler.post {
                    updateProgress.text = getString(R.string.update_download_failed)
                    installUpdateButton.isEnabled = true
                    installUpdateButton.text = getString(R.string.install_update)
                }
                return@thread
            }

            val dest = File(getExternalFilesDir(null) ?: filesDir, "updates/Hisn-${info.version}.apk")
            runCatching { dest.parentFile?.mkdirs() }

            val success = runCatching {
                AppUpdate.downloadAndInstallWithFamilyDns(this@MainActivity, info, dest)
            }.getOrDefault(false)

            handler.post {
                if (success) {
                    // install() launches the installer; we won't reach here if successful
                } else {
                    updateProgress.text = getString(R.string.update_download_failed)
                    installUpdateButton.isEnabled = true
                    installUpdateButton.text = getString(R.string.install_update)
                }
            }
        }
    }

    private fun buildChecklist() {
        checklist.removeAllViews()
        addRow(getString(R.string.step_pin), prefs.hasPin) {
            PinUi.askChangePin(this) { refresh() }
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
            setPadding(0, PinUi.dp(this@MainActivity, 12), 0, PinUi.dp(this@MainActivity, 12))
            isClickable = true
            setOnClickListener { onClick() }
            setBackgroundResource(android.R.color.transparent)
        }
        val mark = TextView(this).apply {
            text = if (done) "\u2713" else "\u25CB"
            textSize = 20f
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (done) R.color.brand else R.color.ink_subtle
                )
            )
            setPadding(0, 0, PinUi.dp(this@MainActivity, 16), 0)
        }
        val label = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
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

    private fun isAccessibilityEnabled(): Boolean =
        GuardHealth.accessibilityEnabled(this)

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
        private const val QUOTE_ROTATION_MS = 30 * 60 * 1000L
    }
}