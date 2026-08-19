package com.injectuy.app.core

import android.util.Log
import com.injectuy.app.parser.PayloadParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Lightweight local HTTP Injector Proxy.
 * Menerima koneksi lokal -> inject HTTP Payload -> teruskan ke Proxy/SSH Host.
 */
class HttpInjectorProxy(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val targetHost: String,
    private val targetPort: Int,
    private val payloadTemplate: String,
    private val listenPort: Int = 8989,
    private val onLog: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    suspend fun start() = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket(listenPort)
            isRunning = true
            onLog("Payload Injector listening on 127.0.0.1:$listenPort")

            while (isRunning) {
                val clientSocket = serverSocket?.accept() ?: break
                Thread { handleClient(clientSocket) }.start()
            }
        } catch (e: Exception) {
            if (isRunning) onLog("Injector Error: ${e.localizedMessage}")
        }
    }

    private fun handleClient(client: Socket) {
        var remoteSocket: Socket? = null
        try {
            onLog("Client connected. Injecting payload...")
            remoteSocket = Socket()
            remoteSocket.connect(InetSocketAddress(proxyHost, proxyPort), 10000)

            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()
            val remoteIn = remoteSocket.getInputStream()
            val remoteOut = remoteSocket.getOutputStream()

            // Kirim parsed payload ke server/proxy
            val injected = PayloadParser.parse(payloadTemplate, targetHost, targetPort)
            remoteOut.write(injected.toByteArray(StandardCharsets.UTF_8))
            remoteOut.flush()
            onLog("Payload sent -> Response waiting...")

            // Baca response header
            val buffer = ByteArray(4096)
            val read = remoteIn.read(buffer)
            if (read > 0) {
                val res = String(buffer, 0, read)
                val statusLine = res.lines().firstOrNull() ?: ""
                onLog("Server Status: $statusLine")

                // Relay 2-arah
                val t1 = Thread { pipeStream(clientIn, remoteOut) }
                val t2 = Thread { pipeStream(remoteIn, clientOut) }
                t1.start()
                t2.start()
                t1.join()
                t2.join()
            }
        } catch (e: Exception) {
            onLog("Tunnel Error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { remoteSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun pipeStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            var len: Int
            while (input.read(buffer).also { len = it } != -1) {
                output.write(buffer, 0, len)
                output.flush()
            }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }
}
