package com.mazdev.mazguard.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.mazdev.mazguard.R

/**
 * Active device admin makes the app "unremovable" until an admin deactivation
 * is performed in system settings, and that screen is PIN-gated.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)

    override fun onDisabled(context: Context, intent: Intent) {
        // Leverage is gone; the watchdog will keep the VPN alive regardless.
        super.onDisabled(context, intent)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }
}
