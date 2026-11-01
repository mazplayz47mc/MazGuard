package com.mazdev.mazguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mazdev.mazguard.core.AppPrefs
import com.mazdev.mazguard.ui.GateActivity

/**
 * Watches for the handful of system screens that would let someone disable the
 * protection, and puts a PIN gate in front of them.
 *
 * Only these screens are intercepted; the rest of the system Settings app stays
 * fully usable:
 *  - this app's own "App info" page (force stop / uninstall / clear data)
 *  - the uninstall confirmation dialog for this app
 *  - the device-admin deactivation screen (prevents silent admin removal)
 *  - this app's accessibility toggle page
 *  - the VPN screen and Private DNS screen (both are filter bypasses)
 */
class GuardAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs: AppPrefs by lazy { AppPrefs.get(this) }

    @Volatile private var lastGateAt = 0L
    @Volatile private var lastEventAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val p = prefs
        if (!p.guardEnabled || !p.hasPin) return

        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: ""

        val kind = classify(pkg, cls, p) ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastEventAt < 120) return
        lastEventAt = now

        if (kind == Kind.APP_SPECIFIC) {
            // The window content is often not fully populated yet; re-check shortly.
            handler.postDelayed({
                if (stillRelevant()) {
                    if (screenMentionsOurApp()) raiseGate()
                }
            }, 250)
        } else {
            raiseGate()
        }
    }

    private fun classify(pkg: String, cls: String, p: AppPrefs): Kind? {
        if (pkg == "com.android.settings") {
            return when {
                cls.contains("InstalledAppDetails", true) ||
                    cls.contains("AppInfoDashboard", true) ||
                    cls.contains("AppDetailsDashboard", true) -> Kind.APP_SPECIFIC

                cls.contains("DeviceAdminAdd", true) -> Kind.APP_SPECIFIC

                cls.contains("AccessibilityServiceDetails", true) ||
                    cls.contains("ToggleAccessibilityService", true) -> Kind.APP_SPECIFIC

                cls.contains("PrivateDns", true) -> if (p.protectSettings) Kind.GENERIC else null

                cls.contains("Vpn", true) -> if (p.protectVpnSettings) Kind.GENERIC else null

                else -> null
            }
        }

        if (pkg.contains("packageinstaller", true) ||
            pkg.contains("permissioncontroller", true) ||
            cls.contains("Uninstall", true)
        ) {
            return Kind.APP_SPECIFIC
        }
        return null
    }

    private fun stillRelevant(): Boolean = prefs.guardEnabled && prefs.hasPin

    private fun raiseGate() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGateAt < 1200) return
        lastGateAt = now
        try {
            val intent = Intent(this, GateActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(GateActivity.EXTRA_REASON, GateActivity.REASON_SETTINGS)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            // If the launch is blocked, the next window event will retry.
        }
    }

    /**
     * True when the current window shows this app's name. Used to avoid gating
     * another app's info page or an unrelated uninstall dialog.
     */
    private fun screenMentionsOurApp(): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return false
        return try {
            walk(root, 0)
        } catch (t: Throwable) {
            false
        }
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 14) return false
        val text = node.text?.toString()
        if (text != null && (text.contains(APP_LABEL, true) || text.contains(PACKAGE_NAME, true))) {
            return true
        }
        val description = node.contentDescription?.toString()
        if (description != null && description.contains(APP_LABEL, true)) return true
        val hint = node.hintText?.toString()
        if (hint != null && hint.contains(APP_LABEL, true)) return true

        for (i in 0 until node.childCount) {
            if (walk(node.getChild(i), depth + 1)) return true
        }
        return false
    }

    override fun onInterrupt() = Unit

    private enum class Kind { APP_SPECIFIC, GENERIC }

    companion object {
        private const val APP_LABEL = "MazGuard"
        private const val PACKAGE_NAME = "com.mazdev.mazguard"
    }
}
