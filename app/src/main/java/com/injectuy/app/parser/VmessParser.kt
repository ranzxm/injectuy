package com.injectuy.app.parser

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets

data class VmessBean(
    val add: String = "",
    val port: Int = 443,
    val id: String = "",
    val aid: Int = 0,
    val scy: String = "auto",
    val net: String = "ws",
    val type: String = "none",
    val host: String = "",
    val path: String = "",
    val tls: String = "tls",
    val sni: String = "",
    val ps: String = ""
)

object VmessParser {
    private val gson = Gson()

    fun parse(vmessUri: String): VmessBean? {
        return try {
            val cleanUri = vmessUri.trim()
            if (!cleanUri.startsWith("vmess://", ignoreCase = true)) return null

            val base64Data = cleanUri.substring(8)
            val decodedJson = String(Base64.decode(base64Data, Base64.DEFAULT), StandardCharsets.UTF_8)
            val jsonObject = gson.fromJson(decodedJson, JsonObject::class.java)

            val address = jsonObject.get("add")?.asString?.trim().orEmpty()
            val port = jsonObject.get("port")?.asString?.toIntOrNull() ?: 443
            val id = jsonObject.get("id")?.asString?.trim().orEmpty()
            if (address.isBlank() || id.isBlank() || port !in 1..65535) return null

            VmessBean(
                add = address,
                port = port,
                id = id,
                aid = jsonObject.get("aid")?.asString?.toIntOrNull() ?: 0,
                scy = jsonObject.get("scy")?.asString ?: "auto",
                net = jsonObject.get("net")?.asString ?: "ws",
                type = jsonObject.get("type")?.asString ?: "none",
                host = jsonObject.get("host")?.asString ?: "",
                path = jsonObject.get("path")?.asString ?: "",
                tls = jsonObject.get("tls")?.asString ?: "",
                sni = jsonObject.get("sni")?.asString ?: "",
                ps = jsonObject.get("ps")?.asString ?: ""
            )
        } catch (e: Exception) {
            null
        }
    }
}
