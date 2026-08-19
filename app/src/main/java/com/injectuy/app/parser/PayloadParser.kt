package com.injectuy.app.parser

/**
 * Payload Parser kompatibel format HTTP Injector / HTTP Custom.
 * Format tag: [crlf], [lf], [cr], [protocol], [host], [port], [host_port], [ua]
 */
object PayloadParser {
    private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36"

    fun parse(payload: String, remoteHost: String, remotePort: Int): String {
        return payload
            .replace("[host_port]", "$remoteHost:$remotePort", ignoreCase = true)
            .replace("[host]", remoteHost, ignoreCase = true)
            .replace("[port]", remotePort.toString(), ignoreCase = true)
            .replace("[protocol]", "HTTP/1.1", ignoreCase = true)
            .replace("[ua]", DEFAULT_UA, ignoreCase = true)
            .replace("[crlf]", "\r\n", ignoreCase = true)
            .replace("[cr]", "\r", ignoreCase = true)
            .replace("[lf]", "\n", ignoreCase = true)
            .replace("\\r", "\r")
            .replace("\\n", "\n")
    }

    fun defaultPayload(): String {
        return "CONNECT [host_port] [protocol][crlf]Host: [host][crlf]Connection: Keep-Alive[crlf]User-Agent: [ua][crlf][crlf]"
    }
}
