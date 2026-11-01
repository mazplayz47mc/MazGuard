package com.mazdev.mazguard.ui

import android.os.SystemClock

/**
 * Short grace window after a successful PIN entry.
 *
 * Without it the accessibility guard would immediately re-lock the screen the
 * moment the gate activity finishes, trapping the owner out of their own device.
 */
object GateSession {

    private const val GRACE_MS = 30_000L

    @Volatile private var unlockedUntil = 0L

    fun grant(durationMs: Long = GRACE_MS) {
        unlockedUntil = SystemClock.elapsedRealtime() + durationMs
    }

    fun isGranted(): Boolean = SystemClock.elapsedRealtime() < unlockedUntil

    fun revoke() {
        unlockedUntil = 0L
    }
}
