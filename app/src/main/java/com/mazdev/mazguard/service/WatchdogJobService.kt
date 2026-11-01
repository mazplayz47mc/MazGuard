package com.mazdev.mazguard.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.mazdev.mazguard.core.AppPrefs

/**
 * Cheap safety net: every 15 minutes, if protection is enabled but the tunnel
 * is gone (process killed, force-stopped by the system, VPN stolen by another
 * app), start it again. JobScheduler is batched by the OS, so this has no
 * meaningful battery cost.
 */
class WatchdogJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val prefs = AppPrefs.get(this)
        if (prefs.guardEnabled && !FilterVpnService.running) {
            runCatching { FilterVpnService.start(this) }
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 4201

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, WatchdogJobService::class.java)
            val info = JobInfo.Builder(JOB_ID, component)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
            runCatching { scheduler.schedule(info) }
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            runCatching { scheduler.cancel(JOB_ID) }
        }
    }
}
