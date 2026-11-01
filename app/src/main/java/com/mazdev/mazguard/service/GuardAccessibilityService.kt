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
import com.mazdev.mazguard.ui.GateSession

/**
 * Watches for the handful of system screens that would let someone disable the
 * protection, and puts a PIN gate in front of them.
 *
 * Detection is content-based rather than class-based on purpose: Android 13+ and
 * 14 moved most Settings pages into `com.android.settings.spa.SpaActivity`, so
 * matching activity names alone silently fails. We look at what the screen says.
 *
 * Only these screens are intercepted; the rest of the Settings app stays usable:
 *  - this app's own "App info" page (force stop / uninstall / clear data)
 *  - the uninstall confirmation dialog for this app
 *  - the device-admin deactivation screen
 *  - the VPN screen when our profile is listed
 *  - the Private DNS screen
 */
class GuardAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs: AppPrefs by lazy { AppPrefs.get(this) }

    @Volatile private var lastGateAt = 0L
    @Volatile private var lastEvaluateAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val p = prefs
        if (!p.guardEnabled || !p.hasPin) return

        val pkg = e.packageName?.toString() ?: return
        if (!isWatchedPackage(pkg)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastEvaluateAt < 150) return
        lastEvaluateAt = now

        // Content is usually not populated yet when the window-state event fires.
        handler.postDelayed({ evaluate(pkg) }, 220)
    }

    private fun isWatchedPackage(pkg: String): Boolean =
        pkg == "com.android.settings" ||
            pkg.contains("packageinstaller", true) ||
            pkg.contains("permissioncontroller", true)

    private fun evaluate(pkg: String) {
        val p = prefs
        if (!p.guardEnabled || !p.hasPin) return
        if (GateSession.isGranted()) return
        if (isProtectedScreen(pkg)) raiseGate()
    }

    private fun isProtectedScreen(pkg: String): Boolean {
        val p = prefs
        val signature = screenSignature()

        val mentionsUs = signature.contains(APP_LABEL, true) ||
            signature.contains(PACKAGE_NAME, true)

        if (mentionsUs) {
            // App info / force stop / uninstall / clear data.
            if (signature.contains("force stop", true) ||
                signature.contains("uninstall", true) ||
                signature.contains("storage & cache", true) ||
                signature.contains("clear storage", true) ||
                signature.contains("clear data", true)
            ) {
                return true
            }
            // Device admin deactivation, and the admin list containing our entry.
            if (signature.contains("deactivate", true) ||
                signature.contains("remove admin", true) ||
                signature.contains("device admin", true)
            ) {
                return true
            }
            // The accessibility toggle is what powers every other gate, so it is
            // locked with the rest.
            if (signature.contains("accessibility", true)) {
                return true
            }
            // VPN list containing our profile.
            if (p.protectVpnSettings && signature.contains("vpn", true)) {
                return true
            }
        }

        // Private DNS is a filtering bypass on its own.
        if (p.protectSettings && signature.contains("private dns", true)) {
            return true
        }

        // Uninstaller flow can hide the app name behind a generic title.
        if (pkg.contains("packageinstaller", true) && signature.contains("uninstall", true)) {
            return true
        }

        return false
    }

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
     * Joins the visible text of the active window, lower-cased. Bounded in size
     * so this stays cheap on every Settings window change.
     */
    private fun screenSignature(): String {
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return ""
        val sb = StringBuilder(256)
        val budget = intArrayOf(80)
        try {
            collect(root, 0, sb, budget)
        } catch (t: Throwable) {
            // partial signature is fine
        }
        return sb.toString().lowercase()
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        depth: Int,
        sb: StringBuilder,
        budget: IntArray
    ) {
        if (node == null || depth > 16 || budget[0] <= 0) return
        budget[0]--

        node.text?.toString()?.let { if (it.isNotBlank()) sb.append(it).append('|') }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) sb.append(it).append('|') }

        for (i in 0 until node.childCount) {
            if (budget[0] <= 0) return
            collect(node.getChild(i), depth + 1, sb, budget)
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val APP_LABEL = "MazGuard"
        private const val PACKAGE_NAME = "com.mazdev.mazguard"
    }
}
