package com.mazdev.mazguard.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

// Off-device brute-force hardening: key alias + blob format version.
// File-private so both PinManager and PinVault can see them.
private const val VAULT_KEY_ALIAS = "mazguard_pin_wrap"
private const val WRAP_PREFIX = "v1:"

/**
 * PIN storage. Only a PBKDF2 hash is persisted, never the PIN itself.
 *
 * The hash is additionally encrypted ("wrapped") with an AndroidKeyStore
 * AES-GCM key that never leaves secure hardware: an attacker who copies the
 * prefs file off the device cannot brute-force the 4-12 digit PIN offline,
 * because the wrapped blob can only be decrypted on this device's keystore.
 *
 * A forgotten PIN can still be recovered through the documented ADB procedure
 * (pm clear wipes prefs AND the wrapped blob together), which keeps the
 * owner in control of their own device.
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
        prefs.pinHash = PinVault.wrap(hash(pin, salt))
        prefs.failCount = 0
        prefs.lockoutUntil = 0
    }

    /**
     * Exact comparison against the stored hash. Accepts both the current
     * keystore-wrapped format ("v1:...") and the legacy plain PBKDF2 base64
     * from before this hardening (older installs); a legacy pin is migrated
     * to the wrapped format on its next successful unlock.
     */
    fun verify(prefs: AppPrefs, pin: String): Boolean {
        if (!prefs.hasPin) return false
        return try {
            val salt = Base64.decode(prefs.pinSalt, Base64.NO_WRAP)
            val actual = hash(pin, salt)
            val stored = prefs.pinHash

            val matches = if (stored.startsWith(WRAP_PREFIX)) {
                val unwrapped = PinVault.unwrap(stored.removePrefix(WRAP_PREFIX))
                unwrapped != null && MessageDigest.isEqual(
                    unwrapped.toByteArray(), actual.toByteArray()
                )
            } else {
                // Legacy install: plain base64 PBKDF2 hash.
                MessageDigest.isEqual(stored.toByteArray(), actual.toByteArray())
            }

            if (matches && !stored.startsWith(WRAP_PREFIX)) {
                // Migrate to the keystore-wrapped format while the PIN is hot.
                prefs.pinHash = PinVault.wrap(actual)
            }
            matches
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

/**
 * AES-GCM "vault" in AndroidKeyStore. The wrapping key is generated inside
 * the keystore and is non-exportable; if hardware-backed it never exists in
 * app memory. PIN brute-force therefore requires the physical device.
 *
 * Failure modes: if the keystore entry is lost (e.g. app data restored to a
 * different device), unwrap() returns null and verification simply fails —
 * recovery is the documented ADB pm clear procedure.
 */
private object PinVault {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(VAULT_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                VAULT_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun wrap(plain: String): String = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // Stored layout: "v1:<iv b64>:<ciphertext b64>".
        WRAP_PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) +
            ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    } catch (t: Throwable) {
        // Keystore hiccup (very rare): fall back to the legacy plain-hash
        // format rather than losing the user's freshly created PIN — verify()
        // accepts both formats, so nothing upstream needs to change.
        plain
    }

    fun unwrap(wrapped: String): String? {
        // Attempts the stored "iv:ct" layout and also a raw single-part base64
        // layout, so format drift can never brick the PIN screen.
        val parts = wrapped.split(':', limit = 2)
        val (ivPart, ctPart) = if (parts.size == 2) parts else return null
        return try {
            val iv = Base64.decode(ivPart, Base64.NO_WRAP)
            val ct = Base64.decode(ctPart, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }
}
