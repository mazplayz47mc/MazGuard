package com.mazdev.mazguard.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Cheap safety net: every 15 minutes a full health pass runs (GuardHealth
 * checks) - the tunnel is restarted silently when it is gone, and a
 * high-priority alert is raised when the accessibility gate failed to come
 * back (which a rebind after an app update is exactly the risky case).
 * JobScheduler is batched by the OS; the job never touches the network, so
 * it has no meaningful battery or network-performance cost.
 */
class WatchdogJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        // Covers the tunnel restart AND the accessibility-gate alert.
        runCatching { GuardHealth.check(this) }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 4201
        private const val HEALTH_JOB_ID = 4202

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

        /**
         * One-off re-arm verification a few seconds after a boot or a
         * self-update: the periodic job above can be up to 15 minutes away,
         * but a missed accessibility rebind must be flagged immediately.
         * NETWORK_TYPE_NONE on purpose - this never needs or wakes the data
         * connection, so it cannot affect network performance.
         */
        fun scheduleHealthCheck(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, WatchdogJobService::class.java)
            val info = JobInfo.Builder(HEALTH_JOB_ID, component)
                .setMinimumLatency(12_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
            runCatching { scheduler.schedule(info) }
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            runCatching { scheduler.cancel(JOB_ID) }
            runCatching { scheduler.cancel(HEALTH_JOB_ID) }
        }
    }
}
