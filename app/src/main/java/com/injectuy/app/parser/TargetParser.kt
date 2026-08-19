package com.injectuy.app.parser

/**
 * Parses target string in format: host:port@user:password
 * Example: hi.xham.web.id:80@devdd117:117
 */
data class TargetCredentials(
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val pass: String = ""
)

object TargetParser {
    fun parse(target: String): TargetCredentials {
        val trimmed = target.trim()
        if (!trimmed.contains("@")) {
            val parts = trimmed.split(":")
            return TargetCredentials(
                host = parts.getOrNull(0) ?: "",
                port = parts.getOrNull(1)?.toIntOrNull() ?: 22
            )
        }

        val atParts = trimmed.split("@", limit = 2)
        val hostPart = atParts[0]
        val authPart = atParts.getOrElse(1) { "" }

        val hostParts = hostPart.split(":")
        val host = hostParts.getOrNull(0) ?: ""
        val port = hostParts.getOrNull(1)?.toIntOrNull() ?: 22

        val authParts = authPart.split(":")
        val user = authParts.getOrNull(0) ?: ""
        val pass = authParts.getOrNull(1) ?: ""

        return TargetCredentials(host, port, user, pass)
    }

    /**
     * Parses proxy string: host:port (e.g. 104.17.70.206:80)
     */
    fun parseProxy(proxy: String): Pair<String, Int> {
        val parts = proxy.trim().split(":")
        val host = parts.getOrNull(0) ?: ""
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 8080
        return Pair(host, port)
    }
}
