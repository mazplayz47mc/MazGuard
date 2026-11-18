package com.mazdev.mazguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mazdev.mazguard.core.AppPrefs

/**
 * Brings the filter back after a reboot, an app update or a quick reboot,
 * then verifies the re-arm a few seconds later (the accessibility gate is
 * the piece that most often fails to come back on its own).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = AppPrefs.get(context)
        if (!prefs.guardEnabled) return
        runCatching { FilterVpnService.start(context) }
        runCatching { WatchdogJobService.schedule(context) }
        runCatching { WatchdogJobService.scheduleHealthCheck(context) }
        // Immediate pass so bypass alerts (ADB / Private DNS / gate) do not
        // wait for the one-off health check job to be scheduled and run.
        runCatching { GuardHealth.check(context) }
    }
}
