package com.mazdev.mazguard.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.mazdev.mazguard.MazGuardApp
import com.mazdev.mazguard.R
import com.mazdev.mazguard.core.AppPrefs

/**
 * Central health check for everything that can silently lapse:
 *
 *  - the VPN tunnel dies when the process is killed, when another app steals
 *    the VPN slot, or in the seconds between a self-update finishing and the
 *    new build's tunnel coming up. The fix is a plain restart, silent here.
 *  - the accessibility gate dies when the system fails to rebind it after an
 *    update (common on aggressive OEM ROMs). It is a user consent, so it can
 *    only be alerted about, never re-enabled from code.
 *
 * Runs from WatchdogJobService (periodic), from BootReceiver right after a
 * boot or a self-update, and from MainActivity (dashboard refresh).
 *
 * Deliberately light: two boolean reads and at most one notification. There
 * is no network I/O anywhere in this file, so it can never compete with the
 * DNS tunnel for bandwidth or add work to the packet path.
 */
object GuardHealth {

    private const val ALERT_ACCESSIBILITY = 4203

    fun check(context: Context) {
        val prefs = AppPrefs.get(context)
        if (!prefs.guardEnabled) {
            cancelAccessibilityAlert(context)
            return
        }

        // 1) Tunnel: silent self-heal. FilterVpnService.start() foregrounds
        //    itself, which is why this must only run from visible/allowed
        //    contexts (jobs scheduled this way are exempt after packages
        //    replace, same as the boot receiver).
        if (!FilterVpnService.running) {
            runCatching { FilterVpnService.start(context) }
        }

        // 2) Gate: alert when the accessibility service is missing, clear
        //    the alert when it is healthy. A missing PIN means there is no
        //    gate contract to protect, so the alert clears too.
        if (prefs.hasPin && !accessibilityEnabled(context)) {
            alertAccessibility(context)
        } else {
            cancelAccessibilityAlert(context)
        }
    }

    /** True when this app's accessibility service is enabled in system settings. */
    fun accessibilityEnabled(context: Context): Boolean {
        val component = ComponentName(context, GuardAccessibilityService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull() ?: return false
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    private fun alertAccessibility(context: Context) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            nm.notify(ALERT_ACCESSIBILITY, buildAlert(context, open))
        }
    }

    private fun buildAlert(context: Context, contentIntent: PendingIntent) =
        NotificationCompat.Builder(context, MazGuardApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_shield_off)
            .setContentTitle(context.getString(R.string.alert_gate_title))
            .setContentText(context.getString(R.string.alert_gate_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.alert_gate_text)))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .build()

    private fun cancelAccessibilityAlert(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(ALERT_ACCESSIBILITY)
        }
    }
}
