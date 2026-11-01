package com.mazdev.mazguard.core

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN storage. Only a PBKDF2 hash is persisted, never the PIN itself.
 * A forgotten PIN can still be recovered through the documented ADB procedure,
 * which keeps the owner in control of their own device.
 */
object PinManager {

    private const val ITERATIONS = 60_000
    private const val KEY_BITS = 256
    private const val MAX_FAILS_BEFORE_LOCKOUT = 5
    private const val LOCKOUT_MS = 60_000L

    fun isValidFormat(pin: String): Boolean =
        pin.length in 4..12 && pin.all { it.isDigit() }

    fun setPin(prefs: AppPrefs, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefs.pinHash = hash(pin, salt)
        prefs.failCount = 0
        prefs.lockoutUntil = 0
    }

    fun verify(prefs: AppPrefs, pin: String): Boolean {
        if (!prefs.hasPin) return false
        return try {
            val salt = Base64.decode(prefs.pinSalt, Base64.NO_WRAP)
            val actual = hash(pin, salt)
            MessageDigest.isEqual(prefs.pinHash.toByteArray(), actual.toByteArray())
        } catch (t: Throwable) {
            false
        }
    }

    /** Attempt a PIN with brute-force throttling. Returns true on success. */
    fun attempt(prefs: AppPrefs, pin: String): Boolean {
        if (lockRemainingMs(prefs) > 0) return false
        if (verify(prefs, pin)) {
            prefs.failCount = 0
            prefs.lockoutUntil = 0
            return true
        }
        val fails = prefs.failCount + 1
        prefs.failCount = fails
        if (fails >= MAX_FAILS_BEFORE_LOCKOUT) {
            val steps = fails - MAX_FAILS_BEFORE_LOCKOUT
            val wait = LOCKOUT_MS * (1L shl steps.coerceAtMost(5))
            prefs.lockoutUntil = System.currentTimeMillis() + wait
        }
        return false
    }

    fun lockRemainingMs(prefs: AppPrefs): Long =
        (prefs.lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    fun lockMessage(prefs: AppPrefs): String? {
        val ms = lockRemainingMs(prefs)
        if (ms <= 0) return null
        val seconds = (ms + 999) / 1000
        return "Too many attempts. Try again in ${seconds}s."
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            Base64.encodeToString(factory.generateSecret(spec).encoded, Base64.NO_WRAP)
        } finally {
            spec.clearPassword()
        }
    }
}
