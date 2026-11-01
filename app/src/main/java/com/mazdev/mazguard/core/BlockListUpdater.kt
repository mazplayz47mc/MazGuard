package com.mazdev.mazguard.core

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a maintained adult-content domain list and stores it in the app's
 * private storage. The bundled seed list keeps the filter useful offline and
 * before the first successful download.
 */
object BlockListUpdater {

    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn/hosts"

    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val MIN_ENTRIES = 500

    fun maybeAutoUpdate(context: Context, prefs: AppPrefs) {
        if (!prefs.autoUpdate) return
        val stale = System.currentTimeMillis() - prefs.listUpdatedAt > WEEK_MS
        if (stale) runCatching { update(context, prefs) }
    }

    fun update(context: Context, prefs: AppPrefs): Int {
        val conn = URL(DEFAULT_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MazGuard/1.0 (Android)")
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val tmp = File(context.filesDir, "blocklist.tmp")
            val out = File(context.filesDir, "blocklist.txt")
            var entries = 0
            tmp.bufferedWriter().use { writer ->
                conn.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val domain = FilterEngine.normalizeDomain(line) ?: return@forEach
                        writer.write(domain)
                        writer.newLine()
                        if (++entries > 500_000) return@forEach
                    }
                }
            }
            if (entries < MIN_ENTRIES) {
                tmp.delete()
                throw IllegalStateException("Downloaded list looked invalid ($entries entries)")
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw IllegalStateException("Could not store list")
            prefs.listUpdatedAt = System.currentTimeMillis()
            FilterEngine.reload(context, prefs)
            prefs.listCount = FilterEngine.count
            return entries
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
