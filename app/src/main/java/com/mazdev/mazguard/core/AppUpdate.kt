package com.mazdev.mazguard.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater for the APK itself.
 *
 * Runs without the PIN on purpose: installing an update can only ever
 * strengthen the filter, never weaken it - Android refuses to install anything
 * over this package unless it is signed with the same key, and the APK comes
 * only from this project's GitHub Releases.
 */
object AppUpdate {

    private const val API =
        "https://api.github.com/repos/mazplayz47mc/MazGuard/releases/latest"
    private const val OWNER_PACKAGE = "com.mazdev.mazguard"

    data class Info(val version: String, val apkUrl: String)

    /** Latest published release, or null when offline/unchanged/broken. */
    fun check(currentVersion: String): Info? {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val tag = regexField(body, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"") ?: return null
            val latest = tag.removePrefix("v").removePrefix("V")
            if (compareVersions(latest, currentVersion) <= 0) return null
            val apkUrl = regexField(
                body,
                "\"browser_download_url\"\\s*:\\s*\"([^\"]*MazGuard-[^\"]*\\.apk)\""
            ) ?: return null
            return Info(latest, apkUrl)
        } catch (t: Throwable) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK to a cache file. Blocking; call off the main thread. */
    fun download(info: Info, dest: File): File? {
        return runCatching {
            val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            try {
                if (conn.responseCode != 200) return null
                dest.outputStream().use { out ->
                    conn.inputStream.use { it.copyTo(out, 64 * 1024) }
                }
            } finally {
                conn.disconnect()
            }
            dest
        }.getOrNull()
    }

    /** Launches the system package installer for the downloaded file. */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "$OWNER_PACKAGE.update", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun canInstall(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.packageManager.canRequestPackageInstalls()
        } else true

    private fun regexField(body: String, pattern: String): String? =
        Regex(pattern).find(body)?.groupValues?.get(1)

    /** 1.0.10 vs 1.0.9 style comparison. */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}