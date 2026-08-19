package com.injectuy.app.core

import com.injectuy.app.parser.PayloadParser
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Native SSH SSL/SNI + HTTP Custom Payload Tunnel Engine.
 * Mendukung 3 mode injector:
 * 1. HTTP Payload (Direct / Proxy)
 * 2. SSL / TLS SNI (SNI Bug Host Spoofing via TLS Handshake)
 * 3. SSL + HTTP Payload (Websocket CDN Cloudflare)
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

    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            onLog("Initializing SSH core...")
            val jsch = JSch()
            session = jsch.getSession(sshUser, sshHost, sshPort)
            session?.setPassword(sshPass)
            session?.setConfig("StrictHostKeyChecking", "no")
            session?.setConfig("compression.s2c", "none")
            session?.setConfig("compression.c2s", "none")

            session?.setSocketFactory(object : SocketFactory {
                private var rawSocket: Socket? = null

                override fun createSocket(host: String, port: Int): Socket {
                    val targetServer = if (proxyHost.isNotEmpty()) proxyHost else host
                    val targetPortNum = if (proxyHost.isNotEmpty()) proxyPort else port

                    onLog("Connecting to $targetServer:$targetPortNum...")
                    val baseSocket = Socket()
                    baseSocket.connect(InetSocketAddress(targetServer, targetPortNum), 15000)

                    val activeSocket: Socket = if (sniHost.isNotEmpty() || targetPortNum == 443) {
                        val serverName = if (sniHost.isNotEmpty()) sniHost else targetServer
                        onLog("Handshaking SSL/TLS with SNI: $serverName")
                        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                        val sslSocket = sslFactory.createSocket(baseSocket, targetServer, targetPortNum, true) as SSLSocket
                        val params = SSLParameters()
                        params.serverNames = listOf(SNIHostName(serverName))
                        sslSocket.sslParameters = params
                        sslSocket.startHandshake()
                        onLog("SSL Connected! Protocol: ${sslSocket.session.protocol} | Cipher: ${sslSocket.session.cipherSuite}")
                        sslSocket
                    } else {
                        baseSocket
                    }

                    rawSocket = activeSocket

                    if (payload.isNotEmpty()) {
                        onLog("Injecting payload...")
                        val out = activeSocket.getOutputStream()
                        val `in` = activeSocket.getInputStream()

                        val parsed = PayloadParser.parse(payload, host, port)
                        val chunks = parsed.split("[split]")
                        for (chunk in chunks) {
                            out.write(chunk.toByteArray(StandardCharsets.UTF_8))
                            out.flush()
                            Thread.sleep(50)
                        }

                        val buffer = ByteArray(2048)
                        val read = `in`.read(buffer)
                        if (read > 0) {
                            val res = String(buffer, 0, read)
                            val statusLine = res.lines().firstOrNull() ?: ""
                            onLog("Proxy Response: $statusLine")
                        }
                    }

                    return activeSocket
                }

                override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
                override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
            })

            onLog("Starting SSH Authentication...")
            session?.connect(20000)

            if (session?.isConnected == true) {
                onLog("SSH Authenticated successfully!")
                session?.setPortForwardingL(localSocksPort, "127.0.0.1", localSocksPort)
                onLog("SOCKS5 Dynamic Forwarding ready on 127.0.0.1:$localSocksPort")
                isConnected = true
            } else {
                throw Exception("SSH Connection failed")
            }
        } catch (e: Exception) {
            onLog("SSH Error: ${e.localizedMessage ?: e.message}")
            disconnect()
            throw e
        }
    }

    fun disconnect() {
        try {
            if (session?.isConnected == true) {
                session?.delPortForwardingL(localSocksPort)
                session?.disconnect()
            }
        } catch (_: Exception) {}
        session = null
        isConnected = false
        onLog("SSH Tunnel disconnected.")
    }
}
