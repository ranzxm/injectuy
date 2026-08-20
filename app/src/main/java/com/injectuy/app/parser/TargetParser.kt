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
        val atIndex = trimmed.indexOf('@')
        val hostPart = if (atIndex >= 0) trimmed.substring(0, atIndex) else trimmed
        val authPart = if (atIndex >= 0) trimmed.substring(atIndex + 1) else ""
        val (host, port) = parseHostPort(hostPart, 22)
        val colonIndex = authPart.indexOf(':')
        val user = if (colonIndex >= 0) authPart.substring(0, colonIndex) else authPart
        val pass = if (colonIndex >= 0) authPart.substring(colonIndex + 1) else ""

        return TargetCredentials(host, port, user, pass)
    }

    /**
     * Parses proxy string: host:port (e.g. 104.17.70.206:80)
     */
    fun parseProxy(proxy: String): Pair<String, Int> {
        return parseHostPort(proxy.trim(), 8080)
    }

    private fun parseHostPort(value: String, defaultPort: Int): Pair<String, Int> {
        if (value.startsWith("[")) {
            val closingBracket = value.indexOf(']')
            if (closingBracket > 0) {
                val host = value.substring(1, closingBracket)
                val port = value.substring(closingBracket + 1)
                    .removePrefix(":")
                    .toValidPortOrNull()
                    ?: defaultPort
                return host to port
            }
        }

        val colonIndex = value.lastIndexOf(':')
        if (colonIndex > 0 && value.indexOf(':') == colonIndex) {
            return value.substring(0, colonIndex) to (value.substring(colonIndex + 1).toValidPortOrNull() ?: defaultPort)
        }
        return value to defaultPort
    }

    private fun String.toValidPortOrNull(): Int? = toIntOrNull()?.takeIf { it in 1..65535 }
}
