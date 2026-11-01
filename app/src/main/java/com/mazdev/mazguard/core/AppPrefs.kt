package com.mazdev.mazguard.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for persisted configuration.
 * A process-wide singleton because the VPN thread reads these values on every DNS query.
 */
class AppPrefs private constructor(private val sp: SharedPreferences) {

    var guardEnabled: Boolean
        get() = sp.getBoolean(K_GUARD, false)
        set(value) = sp.edit().putBoolean(K_GUARD, value).apply()

    var pinHash: String
        get() = sp.getString(K_PIN_HASH, "") ?: ""
        set(value) = sp.edit().putString(K_PIN_HASH, value).apply()

    var pinSalt: String
        get() = sp.getString(K_PIN_SALT, "") ?: ""
        set(value) = sp.edit().putString(K_PIN_SALT, value).apply()

    val hasPin: Boolean
        get() = pinHash.isNotEmpty() && pinSalt.isNotEmpty()

    var safeSearch: Boolean
        get() = sp.getBoolean(K_SAFE_SEARCH, true)
        set(value) = sp.edit().putBoolean(K_SAFE_SEARCH, value).apply()

    var keywordFilter: Boolean
        get() = sp.getBoolean(K_KEYWORDS, true)
        set(value) = sp.edit().putBoolean(K_KEYWORDS, value).apply()

    var blockDoh: Boolean
        get() = sp.getBoolean(K_BLOCK_DOH, true)
        set(value) = sp.edit().putBoolean(K_BLOCK_DOH, value).apply()

    var protectSettings: Boolean
        get() = sp.getBoolean(K_PROTECT_SETTINGS, true)
        set(value) = sp.edit().putBoolean(K_PROTECT_SETTINGS, value).apply()

    var protectVpnSettings: Boolean
        get() = sp.getBoolean(K_PROTECT_VPN, true)
        set(value) = sp.edit().putBoolean(K_PROTECT_VPN, value).apply()

    var autoUpdate: Boolean
        get() = sp.getBoolean(K_AUTO_UPDATE, true)
        set(value) = sp.edit().putBoolean(K_AUTO_UPDATE, value).apply()

    var totalBlocked: Long
        get() = sp.getLong(K_TOTAL_BLOCKED, 0L)
        set(value) = sp.edit().putLong(K_TOTAL_BLOCKED, value).apply()

    var activatedAt: Long
        get() = sp.getLong(K_ACTIVATED_AT, 0L)
        set(value) = sp.edit().putLong(K_ACTIVATED_AT, value).apply()

    var listUpdatedAt: Long
        get() = sp.getLong(K_LIST_UPDATED, 0L)
        set(value) = sp.edit().putLong(K_LIST_UPDATED, value).apply()

    var listCount: Int
        get() = sp.getInt(K_LIST_COUNT, 0)
        set(value) = sp.edit().putInt(K_LIST_COUNT, value).apply()

    var failCount: Int
        get() = sp.getInt(K_FAIL_COUNT, 0)
        set(value) = sp.edit().putInt(K_FAIL_COUNT, value).apply()

    var lockoutUntil: Long
        get() = sp.getLong(K_LOCKOUT_UNTIL, 0L)
        set(value) = sp.edit().putLong(K_LOCKOUT_UNTIL, value).apply()

    /** Domains the user explicitly allowed (bypass the blocklist). */
    var allowedDomains: MutableSet<String>
        get() = HashSet(sp.getStringSet(K_ALLOWED, emptySet()) ?: emptySet())
        set(value) = sp.edit().putStringSet(K_ALLOWED, value).apply()

    /** Domains the user explicitly blocked. */
    var blockedDomains: MutableSet<String>
        get() = HashSet(sp.getStringSet(K_BLOCKED_EXTRA, emptySet()) ?: emptySet())
        set(value) = sp.edit().putStringSet(K_BLOCKED_EXTRA, value).apply()

    fun addAllowed(domain: String) {
        val set = allowedDomains
        set.add(domain.lowercase())
        allowedDomains = set
    }

    fun removeAllowed(domain: String) {
        val set = allowedDomains
        set.remove(domain.lowercase())
        allowedDomains = set
    }

    fun addBlocked(domain: String) {
        val set = blockedDomains
        set.add(domain.lowercase())
        blockedDomains = set
    }

    fun removeBlocked(domain: String) {
        val set = blockedDomains
        set.remove(domain.lowercase())
        blockedDomains = set
    }

    companion object {
        private const val FILE = "mazguard_prefs"

        private const val K_GUARD = "guard_enabled"
        private const val K_PIN_HASH = "pin_hash"
        private const val K_PIN_SALT = "pin_salt"
        private const val K_SAFE_SEARCH = "safe_search"
        private const val K_KEYWORDS = "keyword_filter"
        private const val K_BLOCK_DOH = "block_doh"
        private const val K_PROTECT_SETTINGS = "protect_settings"
        private const val K_PROTECT_VPN = "protect_vpn_settings"
        private const val K_AUTO_UPDATE = "auto_update"
        private const val K_TOTAL_BLOCKED = "total_blocked"
        private const val K_ACTIVATED_AT = "activated_at"
        private const val K_LIST_UPDATED = "list_updated_at"
        private const val K_LIST_COUNT = "list_count"
        private const val K_FAIL_COUNT = "fail_count"
        private const val K_LOCKOUT_UNTIL = "lockout_until"
        private const val K_ALLOWED = "allowed_domains"
        private const val K_BLOCKED_EXTRA = "blocked_domains"

        @Volatile
        private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                return AppPrefs(sp).also { instance = it }
            }
        }
    }
}
