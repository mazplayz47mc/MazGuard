package com.mazdev.mazguard.core

import java.util.concurrent.atomic.AtomicLong

/**
 * Ring buffer of recently blocked domains plus a persisted lifetime counter.
 * Kept in memory so the DNS path never touches disk.
 */
object BlockLog {

    data class Entry(val host: String, val time: Long)

    private const val MAX_RECENT = 200
    private const val FLUSH_EVERY = 25L

    private val recent = ArrayList<Entry>(MAX_RECENT)
    private val total = AtomicLong(0)

    @Volatile private var persisted = 0L
    @Volatile private var prefs: AppPrefs? = null

    fun attach(p: AppPrefs) {
        prefs = p
        val stored = p.totalBlocked
        if (total.get() < stored) total.set(stored)
        persisted = total.get()
    }

    fun record(host: String) {
        synchronized(recent) {
            recent.add(0, Entry(host, System.currentTimeMillis()))
            while (recent.size > MAX_RECENT) {
                recent.removeAt(recent.size - 1)
            }
        }
        val now = total.incrementAndGet()
        if (now - persisted >= FLUSH_EVERY) flush()
    }

    fun flush() {
        val p = prefs ?: return
        val value = total.get()
        if (value == persisted) return
        p.totalBlocked = value
        persisted = value
    }

    fun totalCount(): Long = total.get()

    fun recent(): List<Entry> = synchronized(recent) { ArrayList(recent) }

    fun clearRecent() = synchronized(recent) { recent.clear() }
}
