package com.mazdev.mazguard

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.mazdev.mazguard.core.AppPrefs
import com.mazdev.mazguard.core.BlockListUpdater
import com.mazdev.mazguard.core.BlockLog
import com.mazdev.mazguard.core.FilterEngine
import com.mazdev.mazguard.service.WatchdogJobService
import kotlin.concurrent.thread

class MazGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannel()

        val prefs = AppPrefs.get(this)
        BlockLog.attach(prefs)
        WatchdogJobService.schedule(this)

        // Loading an 80k-entry list takes a moment; keep it off the main thread.
        // The VPN service waits for FilterEngine before accepting queries.
        thread(name = "mazguard-init") {
            runCatching { FilterEngine.reload(this, prefs) }
            if (prefs.guardEnabled) {
                runCatching { BlockListUpdater.maybeAutoUpdate(this, prefs) }
            }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_protection),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_protection_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        nm.createNotificationChannel(channel)

        val alert = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.channel_alerts),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_alerts_desc)
        }
        nm.createNotificationChannel(alert)
    }

    companion object {
        const val CHANNEL_ID = "mazguard_protection"
        const val CHANNEL_ALERTS = "mazguard_alerts"
    }
}
