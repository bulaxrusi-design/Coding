package com.ttclab.chatbridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("chatgpt_device_lab", Context.MODE_PRIVATE)
    private val alias = "chatgpt_device_lab_token_key"

    var owner: String
        get() = prefs.getString("owner", "bulaxrusi-design") ?: "bulaxrusi-design"
        set(value) = prefs.edit().putString("owner", value.trim()).apply()

    var repo: String
        get() = prefs.getString("repo", "ttc-live-relay") ?: "ttc-live-relay"
        set(value) = prefs.edit().putString("repo", value.trim()).apply()

    var branch: String
        get() = prefs.getString("branch", "main") ?: "main"
        set(value) = prefs.edit().putString("branch", value.trim().ifBlank { "main" }).apply()

    var allowedPackage: String
        get() = prefs.getString("allowed_package", "com.easybrain.number.puzzle.game")
            ?: "com.easybrain.number.puzzle.game"
        set(value) = prefs.edit().putString("allowed_package", value.trim()).apply()

    var pollSeconds: Double
        get() = java.lang.Double.longBitsToDouble(
            prefs.getLong("poll_seconds", java.lang.Double.doubleToRawLongBits(3.0))
        )
        set(value) = prefs.edit().putLong(
            "poll_seconds",
            java.lang.Double.doubleToRawLongBits(value.coerceIn(2.0, 30.0))
        ).apply()

    var currentSession: String
        get() = prefs.getString("current_session", "") ?: ""
        set(value) = prefs.edit().putString("current_session", value).apply()

    var lastSequence: Long
        get() = prefs.getLong("last_sequence", 0L)
        set(value) = prefs.edit().putLong("last_sequence", value).apply()

    var bridgeStatus: String
        get() = prefs.getString("bridge_status", "Stopped") ?: "Stopped"
        set(value) = prefs.edit().putString("bridge_status", value).apply()

    fun setToken(token: String) {
        if (token.isBlank()) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
            .putInt(cipher.iv.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()
        prefs.edit().putString("token", Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun getToken(): String {
        val encoded = prefs.getString("token", null) ?: return ""
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(payload)
            val ivLength = buffer.int
            require(ivLength in 12..32)
            val iv = ByteArray(ivLength).also(buffer::get)
            val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
