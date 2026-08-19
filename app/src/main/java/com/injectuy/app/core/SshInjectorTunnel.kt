package com.injectuy.app.core

import com.jcraft.jsch.JSch
import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Native SSH Dynamic Port Forwarding (SOCKS5 Proxy).
 * Alur SSH Injector:
 * 1. Buka Socket ke Bug / Proxy Host
 * 2. Kirim HTTP Payload (dengan support [split], [host], dll)
 * 3. Terima response HTTP 101 Switching Protocols / 200 OK
 * 4. Lanjutkan SSH Handshake & Autentikasi di dalam stream socket tersebut
 * 5. Buka SOCKS5 proxy lokal (misal port 10808) untuk routing seluruh traffic Android via VpnService.
 */
class SshInjectorTunnel(
    private val sshHost: String,
    private val sshPort: Int,
    private val sshUser: String,
    private val sshPass: String,
    private val proxyHost: String,
    private val proxyPort: Int,
    private val payload: String,
    private val localSocksPort: Int = 10808,
    private val onLog: (String) -> Unit
) {
    private var session: Session? = null
    private var isConnected = false

    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            onLog("Initializing SSH tunnel engine...")
            val jsch = JSch()
            session = jsch.getSession(sshUser, sshHost, sshPort)
            session?.setPassword(sshPass)
            session?.setConfig("StrictHostKeyChecking", "no")
            session?.setConfig("compression.s2c", "none")
            session?.setConfig("compression.c2s", "none")

            // Custom socket factory untuk inject HTTP payload sebelum SSH handshake
            session?.setSocketFactory(object : SocketFactory {
                private var rawSocket: Socket? = null

                override fun createSocket(host: String, port: Int): Socket {
                    val socket = Socket()
                    rawSocket = socket

                    val targetServer = if (proxyHost.isNotEmpty()) proxyHost else host
                    val targetPortNum = if (proxyHost.isNotEmpty()) proxyPort else port

                    onLog("Connecting to Proxy: $targetServer:$targetPortNum")
                    socket.connect(InetSocketAddress(targetServer, targetPortNum), 15000)

                    if (payload.isNotEmpty()) {
                        onLog("Injecting HTTP Payload...")
                        val out = socket.getOutputStream()
                        val `in` = socket.getInputStream()

                        val parsedPayload = com.injectuy.app.parser.PayloadParser.parse(payload, host, port)
                        val chunks = parsedPayload.split("[split]")
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
                    return socket
                }

                override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
                override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
            })

            onLog("Starting SSH Handshake...")
            session?.connect(20000)

            if (session?.isConnected == true) {
                onLog("SSH Authenticated successfully!")
                session?.setPortForwardingD(localSocksPort)
                onLog("Dynamic SOCKS5 running on 127.0.0.1:$localSocksPort")
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
                session?.delPortForwardingD(localSocksPort)
                session?.disconnect()
            }
        } catch (_: Exception) {}
        session = null
        isConnected = false
        onLog("SSH Tunnel closed.")
    }
}
