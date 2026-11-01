package com.mazdev.mazguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mazdev.mazguard.core.AppPrefs

/**
 * Brings the filter back after a reboot, an app update or a quick reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = AppPrefs.get(context)
        if (!prefs.guardEnabled) return
        runCatching { FilterVpnService.start(context) }
        runCatching { WatchdogJobService.schedule(context) }
    }
}
