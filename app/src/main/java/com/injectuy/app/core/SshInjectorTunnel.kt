package com.injectuy.app.core

import com.injectuy.app.parser.PayloadParser
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Native SSH SSL/SNI + HTTP Custom Payload Tunnel Engine.
 * Menangani HTTP payload injection tanpa menelan buffer SSH identification header.
 */
class SshInjectorTunnel(
    private val sshHost: String,
    private val sshPort: Int,
    private val sshUser: String,
    private val sshPass: String,
    private val proxyHost: String,
    private val proxyPort: Int,
    private val payload: String,
    private val serverMessage: String = "",
    private val sniHost: String = "",
    private val localSocksPort: Int = 10808,
    private val onLog: (String) -> Unit
) {
    private var session: Session? = null
    private var isConnected = false
    private var pingJob: Job? = null
    private val tunnelScope = CoroutineScope(Dispatchers.IO)

    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            val jsch = JSch()
            session = jsch.getSession(sshUser, sshHost, sshPort)
            session?.setPassword(sshPass)
            session?.setConfig("StrictHostKeyChecking", "no")
            session?.setConfig("compression.s2c", "none")
            session?.setConfig("compression.c2s", "none")

            session?.userInfo = object : UserInfo {
                override fun getPassphrase(): String? = null
                override fun getPassword(): String = sshPass
                override fun promptPassword(message: String?): Boolean = true
                override fun promptPassphrase(message: String?): Boolean = true
                override fun promptYesNo(message: String?): Boolean = true
                override fun showMessage(message: String?) {
                    if (!message.isNullOrEmpty()) {
                        onLog("Server Message:\n$message")
                    }
                }
            }

            session?.setSocketFactory(object : SocketFactory {
                private var wrappedInputStream: InputStream? = null
                private var rawOutputStream: OutputStream? = null

                override fun createSocket(host: String, port: Int): Socket {
                    val targetServer = if (proxyHost.isNotEmpty()) proxyHost else host
                    val targetPortNum = if (proxyHost.isNotEmpty()) proxyPort else port

                    onLog("Connecting to proxy $targetServer port $targetPortNum")
                    val baseSocket = Socket()
                    baseSocket.connect(InetSocketAddress(targetServer, targetPortNum), 15000)

                    val activeSocket: Socket = if (sniHost.isNotEmpty() || targetPortNum == 443) {
                        val serverName = if (sniHost.isNotEmpty()) sniHost else targetServer
                        onLog("SSL/TLS Handshake: $serverName")
                        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                        val sslSocket = sslFactory.createSocket(baseSocket, targetServer, targetPortNum, true) as SSLSocket
                        val params = SSLParameters()
                        params.serverNames = listOf(SNIHostName(serverName))
                        sslSocket.sslParameters = params
                        sslSocket.startHandshake()
                        sslSocket
                    } else {
                        baseSocket
                    }

                    val out = activeSocket.getOutputStream()
                    val `in` = activeSocket.getInputStream()
                    rawOutputStream = out

                    if (payload.isNotEmpty()) {
                        val parsed = PayloadParser.parse(payload, host, port)
                        val chunks = parsed.split("[split]")
                        for (chunk in chunks) {
                            onLog("Sending Payload: $chunk")
                            out.write(chunk.toByteArray(StandardCharsets.UTF_8))
                            out.flush()
                            Thread.sleep(50)
                        }

                        // Baca HTTP response header secara persis baris per baris tanpa menelan buffer SSH
                        val headerBuffer = StringBuilder()
                        var sshBuffer = ByteArray(0)
                        val readBuffer = ByteArray(1024)
                        var isHeaderComplete = false

                        while (!isHeaderComplete) {
                            val read = `in`.read(readBuffer)
                            if (read <= 0) break
                            val chunkStr = String(readBuffer, 0, read, StandardCharsets.ISO_8859_1)
                            headerBuffer.append(chunkStr)

                            val doubleCrlfIdx = headerBuffer.indexOf("\r\n\r\n")
                            if (doubleCrlfIdx != -1) {
                                isHeaderComplete = true
                                val fullHeader = headerBuffer.substring(0, doubleCrlfIdx)
                                for (line in fullHeader.lines()) {
                                    if (line.isNotBlank()) onLog("Response: $line")
                                }

                                val leftoverBytes = headerBuffer.substring(doubleCrlfIdx + 4)
                                    .toByteArray(StandardCharsets.ISO_8859_1)
                                if (leftoverBytes.isNotEmpty()) {
                                    sshBuffer = leftoverBytes
                                    val leftoverStr = String(leftoverBytes, StandardCharsets.ISO_8859_1)
                                    val firstLine = leftoverStr.lines().firstOrNull() ?: ""
                                    if (firstLine.startsWith("SSH-", ignoreCase = true)) {
                                        onLog(firstLine)
                                    }
                                }
                            }
                        }

                        if (serverMessage.isNotBlank()) {
                            onLog("Server Message:")
                            onLog(serverMessage)
                        }

                        wrappedInputStream = if (sshBuffer.isNotEmpty()) {
                            SequenceInputStream(ByteArrayInputStream(sshBuffer), `in`)
                        } else {
                            `in`
                        }
                    } else {
                        wrappedInputStream = `in`
                    }

                    return activeSocket
                }

                override fun getInputStream(socket: Socket): InputStream = wrappedInputStream ?: socket.getInputStream()
                override fun getOutputStream(socket: Socket): OutputStream = rawOutputStream ?: socket.getOutputStream()
            })

            session?.connect(20000)

            if (session?.isConnected == true) {
                val serverVersion = session?.serverVersion ?: "SSH-2.0"
                onLog(serverVersion)
                onLog("Auth complete")
                session?.setPortForwardingL(localSocksPort, "127.0.0.1", localSocksPort)
                onLog("Connected")
                isConnected = true

                startHttpPing()
            } else {
                throw Exception("SSH Connection failed")
            }
        } catch (e: Exception) {
            val err = e.localizedMessage ?: e.message ?: "Unknown error"
            onLog("SSH Error: $err")
            throw e
        }
    }

    private fun startHttpPing() {
        pingJob?.cancel()
        pingJob = tunnelScope.launch {
            delay(3000)
            while (isActive && isConnected) {
                try {
                    val start = System.currentTimeMillis()
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localSocksPort))
                    val url = URL("http://cp.cloudflare.com/generate_204")
                    val conn = url.openConnection(proxy) as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    val latency = System.currentTimeMillis() - start
                    val statusText = if (code in 200..204) "200 OK" else "$code"
                    onLog("HTTP Ping $statusText (${latency}ms)")
                    conn.disconnect()
                } catch (_: Exception) {
                }
                delay(5000)
            }
        }
    }

    fun disconnect() {
        if (!isConnected && session == null) return
        onLog("Closing client connection")
        pingJob?.cancel()
        pingJob = null
        try {
            if (session?.isConnected == true) {
                session?.delPortForwardingL(localSocksPort)
                session?.disconnect()
            }
        } catch (_: Exception) {}
        session = null
        isConnected = false
        onLog("Client connection closed")
        onLog("Disconnected")
    }
}
