package com.injectuy.app.security

import android.util.Base64
import com.google.gson.Gson
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedConfig(
    val version: Int = 1,
    val note: String = "InjectUY Locked Config",
    val target: String = "",
    val proxy: String = "",
    val payload: String = "",
    val isLocked: Boolean = true,
    val expireDate: Long = 0L
)

object ConfigSecurity {
    private const val SECRET_KEY_SEED = "InjectUY_Super_Ultra_Lightweight_Key_2026"
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
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(), getIv())
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        return "INJECTUY:" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun importConfig(data: String): EncryptedConfig? {
        return try {
            val clean = data.trim()
            val base64Data = if (clean.startsWith("INJECTUY:", ignoreCase = true)) {
                clean.substring("INJECTUY:".length)
            } else {
                clean
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), getIv())
            val decryptedBytes = cipher.doFinal(Base64.decode(base64Data, Base64.NO_WRAP))
            val json = String(decryptedBytes, Charsets.UTF_8)
            gson.fromJson(json, EncryptedConfig::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
