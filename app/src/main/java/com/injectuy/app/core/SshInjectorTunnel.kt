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
import java.io.InputStream
import java.io.OutputStream
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
 * Format log persis seperti HTTP Custom / HTTP Injector:
 * - Connecting to proxy {host} port {port}
 * - Sending Payload: ...
 * - Response: HTTP/1.1 ...
 * - Server Version (SSH-2.0-...)
 * - Server Banner Message (Colored ANSI/HTML)
 * - Auth complete & Connected
 * - Periodic HTTP Ping tester
 */
class SshInjectorTunnel(
    private val sshHost: String,
    private val sshPort: Int,
    private val sshUser: String,
    private val sshPass: String,
    private val proxyHost: String,
    private val proxyPort: Int,
    private val payload: String,
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

            // Listener untuk Server Banner Message
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
                private var rawSocket: Socket? = null

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

                    rawSocket = activeSocket

                    if (payload.isNotEmpty()) {
                        val out = activeSocket.getOutputStream()
                        val `in` = activeSocket.getInputStream()

                        val parsed = PayloadParser.parse(payload, host, port)
                        val chunks = parsed.split("[split]")
                        for (chunk in chunks) {
                            onLog("Sending Payload: $chunk")
                            out.write(chunk.toByteArray(StandardCharsets.UTF_8))
                            out.flush()
                            Thread.sleep(50)
                        }

                        val buffer = ByteArray(2048)
                        val read = `in`.read(buffer)
                        if (read > 0) {
                            val res = String(buffer, 0, read)
                            for (line in res.lines()) {
                                if (line.startsWith("HTTP/", ignoreCase = true)) {
                                    onLog("Response: $line")
                                } else if (line.startsWith("SSH-", ignoreCase = true)) {
                                    onLog(line)
                                }
                            }
                        }
                    }

                    return activeSocket
                }

                override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
                override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
            })

            session?.connect(20000)

            if (session?.isConnected == true) {
                val serverVersion = session?.serverVersion ?: "SSH-2.0"
                onLog(serverVersion)
                onLog("Auth complete")
                session?.setPortForwardingL(localSocksPort, "127.0.0.1", localSocksPort)
                onLog("Connected")
                isConnected = true

                // Start HTTP Ping Loop
                startHttpPing()
            } else {
                throw Exception("SSH Connection failed")
            }
        } catch (e: Exception) {
            onLog("SSH Error: ${e.localizedMessage ?: e.message}")
            disconnect()
            throw e
        }
    }

    private fun startHttpPing() {
        pingJob?.cancel()
        pingJob = tunnelScope.launch {
            delay(5000)
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
                } catch (e: Exception) {
                    onLog("HTTP Ping timeout")
                }
                delay(5000)
            }
        }
    }

    fun disconnect() {
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
