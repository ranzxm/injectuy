package com.injectuy.app.security

import android.util.Base64
import com.google.gson.Gson
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedConfig(
    val version: Int = 2,
    val fileName: String = "InjectUY Config",
    val note: String = "InjectUY Locked Config",
    val serverMessage: String = "",
    val target: String = "",
    val proxy: String = "",
    val payload: String = "",
    val lockSsh: Boolean = false,
    val lockProxy: Boolean = false,
    val lockPayload: Boolean = false,
    val expireDate: Long = 0L,
    // Retained so version 1 configs remain importable.
    val isLocked: Boolean = false
)

object ConfigSecurity {
    private const val SECRET_KEY_SEED = "InjectUY_Super_Ultra_Lightweight_Key_2026"
    private const val V2_PREFIX = "INJECTUY2:"
    private const val GCM_IV_LENGTH = 12
    private val gson = Gson()

    private fun getSecretKey(): SecretKeySpec {
        val sha = MessageDigest.getInstance("SHA-256")
        val key = sha.digest(SECRET_KEY_SEED.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(key, "AES")
    }

    private fun getIv(): IvParameterSpec {
        val iv = ByteArray(16) { 0x01 }
        return IvParameterSpec(iv)
    }

    fun exportConfig(config: EncryptedConfig): String {
        val json = gson.toJson(config)
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        return V2_PREFIX + Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    fun importConfig(data: String): EncryptedConfig? {
        return try {
            val clean = data.trim()
            val base64Data = if (clean.startsWith(V2_PREFIX, ignoreCase = true)) {
                clean.substring(V2_PREFIX.length)
            } else if (clean.startsWith("INJECTUY:", ignoreCase = true)) {
                clean.substring("INJECTUY:".length)
            } else {
                clean
            }
            val encoded = Base64.decode(base64Data, Base64.NO_WRAP)
            val decryptedBytes = if (clean.startsWith(V2_PREFIX, ignoreCase = true)) {
                if (encoded.size <= GCM_IV_LENGTH) return null
                val iv = encoded.copyOfRange(0, GCM_IV_LENGTH)
                val encrypted = encoded.copyOfRange(GCM_IV_LENGTH, encoded.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(128, iv))
                cipher.doFinal(encrypted)
            } else {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), getIv())
                cipher.doFinal(encoded)
            }
            val json = String(decryptedBytes, Charsets.UTF_8)
            gson.fromJson(json, EncryptedConfig::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun isExpired(config: EncryptedConfig, now: Long = System.currentTimeMillis()): Boolean {
        return config.expireDate > 0 && now > config.expireDate
    }
}
