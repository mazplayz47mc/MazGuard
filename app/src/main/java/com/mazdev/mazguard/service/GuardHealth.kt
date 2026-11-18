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
 *  - USB debugging (ADB) and a configured Private DNS are user-level bypass
 *    vectors the filter cannot stop on its own; both raise a persistent
 *    alert while protection is active.
 *
 * Runs from WatchdogJobService (periodic), from BootReceiver right after a
 * boot or a self-update, and from MainActivity (dashboard refresh).
 *
 * Deliberately light: a few boolean reads and at most one notification per
 * condition. There is no network I/O anywhere in this file, so it can never
 * compete with the DNS tunnel for bandwidth or add work to the packet path.
 */
object GuardHealth {

    private const val ALERT_ACCESSIBILITY = 4203
    private const val ALERT_ADB = 4204
    private const val ALERT_PRIVATE_DNS = 4205

    fun check(context: Context) {
        val prefs = AppPrefs.get(context)
        if (!prefs.guardEnabled) {
            cancelAlert(context, ALERT_ACCESSIBILITY)
            cancelAlert(context, ALERT_ADB)
            cancelAlert(context, ALERT_PRIVATE_DNS)
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
            postAlert(
                context,
                ALERT_ACCESSIBILITY,
                R.string.alert_gate_title,
                R.string.alert_gate_text,
                Settings.ACTION_ACCESSIBILITY_SETTINGS
            )
        } else {
            cancelAlert(context, ALERT_ACCESSIBILITY)
        }

        // 3) USB debugging: ADB can remove the device admin and clear app
        //    data without the PIN.
        if (adbEnabled(context)) {
            postAlert(
                context,
                ALERT_ADB,
                R.string.alert_adb_title,
                R.string.alert_adb_text,
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            )
        } else {
            cancelAlert(context, ALERT_ADB)
        }

        // 4) Private DNS: a configured resolver can bypass the tunnel.
        if (privateDnsBypassing(context)) {
            postAlert(
                context,
                ALERT_PRIVATE_DNS,
                R.string.alert_private_dns_title,
                R.string.private_dns_warning,
                Settings.ACTION_WIRELESS_SETTINGS
            )
        } else {
            cancelAlert(context, ALERT_PRIVATE_DNS)
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

    /**
     * True when USB debugging (ADB) is on. Any read failure degrades to
     * false (unknown), never an alarm.
     */
    fun adbEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    }.getOrDefault(false)

    /**
     * True when Private DNS is set to an explicit DoT hostname, which can
     * bypass the filter. "opportunistic" is Android's stock default and
     * never resolved by name, so it is not flagged. An unreadable setting
     * counts as unknown, i.e. no alarm.
     */
    fun privateDnsBypassing(context: Context): Boolean {
        val mode = runCatching {
            Settings.Global.getString(context.contentResolver, "private_dns_mode")
        }.getOrNull()
        return mode == "hostname"
    }

    private fun postAlert(
        context: Context,
        id: Int,
        titleRes: Int,
        textRes: Int,
        settingsAction: String
    ) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val open = PendingIntent.getActivity(
                context,
                id,
                Intent(settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            nm.notify(id, buildAlert(context, titleRes, textRes, open))
        }
    }

    private fun buildAlert(
        context: Context,
        titleRes: Int,
        textRes: Int,
        contentIntent: PendingIntent
    ) = NotificationCompat.Builder(context, MazGuardApp.CHANNEL_ALERTS)
        .setSmallIcon(R.drawable.ic_shield_off)
        .setContentTitle(context.getString(titleRes))
        .setContentText(context.getString(textRes))
        .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(textRes)))
        .setCategory(NotificationCompat.CATEGORY_ERROR)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentIntent)
        .setAutoCancel(false)
        .build()

    private fun cancelAlert(context: Context, id: Int) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(id)
        }
    }
}
