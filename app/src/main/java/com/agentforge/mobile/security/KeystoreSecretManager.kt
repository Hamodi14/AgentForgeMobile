package com.agentforge.mobile.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed (when supported by device TEE/StrongBox) AES-256-GCM encryption manager
 * using the official AndroidKeyStore provider.
 *
 * Security boundary note:
 * Android Keystore protects keys against extraction from normal app sandboxes and off-device
 * backups (allowBackup=false). However, on a rooted or compromised device where an attacker
 * has kernel/instrumentation control over the running process, in-memory secrets can still
 * be intercepted when decrypted for API requests.
 */
interface SecretStore {
    fun saveApiKey(providerId: String, plainKey: String)
    fun getApiKey(providerId: String): String?
    fun deleteApiKey(providerId: String)
    fun hasApiKey(providerId: String): Boolean
    fun getMaskedApiKey(providerId: String): String?
}

class KeystoreSecretManager(context: Context) : SecretStore {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "AgentForgeMasterKey_v1"
        private const val PREFS_NAME = "agentforge_encrypted_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingEntry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingEntry != null) {
            return existingEntry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun saveApiKey(providerId: String, plainKey: String) {
        val trimmed = plainKey.trim()
        if (trimmed.isEmpty()) {
            deleteApiKey(providerId)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(trimmed.toByteArray(StandardCharsets.UTF_8))

        val combined = ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()

        val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
        prefs.edit().putString("key_$providerId", encoded).apply()
    }

    override fun getApiKey(providerId: String): String? {
        val encoded = prefs.getString("key_$providerId", null) ?: return null
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(combined)
            val ivSize = buffer.get().toInt() and 0xFF
            if (ivSize <= 0 || ivSize > 32 || combined.size <= 1 + ivSize) return null

            val iv = ByteArray(ivSize)
            buffer.get(iv)
            val ciphertext = ByteArray(buffer.remaining())
            buffer.get(ciphertext)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            )
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    override fun deleteApiKey(providerId: String) {
        prefs.edit().remove("key_$providerId").apply()
    }

    override fun hasApiKey(providerId: String): Boolean {
        return !getApiKey(providerId).isNullOrBlank()
    }

    override fun getMaskedApiKey(providerId: String): String? {
        val raw = getApiKey(providerId) ?: return null
        if (raw.length <= 8) return "••••••••"
        return "${raw.take(4)}••••••••${raw.takeLast(4)}"
    }

    /**
     * Redacts any stored API keys from logs or error messages before persisting or displaying.
     */
    fun redactSecrets(input: String, providerIds: Collection<String>): String {
        var sanitized = input
        for (id in providerIds) {
            val secret = getApiKey(id)
            if (!secret.isNullOrBlank() && secret.length >= 6) {
                sanitized = sanitized.replace(secret, "[REDACTED_KEY_$id]")
            }
        }
        // Also redact common bearer/sk-/AIza patterns
        sanitized = sanitized.replace(Regex("(sk-[A-Za-z0-9_-]{16,})"), "[REDACTED_API_KEY]")
        sanitized = sanitized.replace(Regex("(gsk_[A-Za-z0-9_-]{16,})"), "[REDACTED_GROQ_KEY]")
        sanitized = sanitized.replace(Regex("(hf_[A-Za-z0-9_-]{16,})"), "[REDACTED_HF_KEY]")
        sanitized = sanitized.replace(Regex("(ghp_[A-Za-z0-9_-]{16,}|github_pat_[A-Za-z0-9_]{16,})"), "[REDACTED_GITHUB_TOKEN]")
        sanitized = sanitized.replace(Regex("(AIza[0-9A-Za-z-_]{25,})"), "[REDACTED_GEMINI_KEY]")
        return sanitized
    }
}
