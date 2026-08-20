package com.injectuy.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.injectuy.app.MainActivity
import com.injectuy.app.core.SshInjectorTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class TunnelVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var sshTunnel: SshInjectorTunnel? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    companion object {
        const val ACTION_START = "com.injectuy.START"
        const val ACTION_STOP = "com.injectuy.STOP"
        const val BROADCAST_LOG = "com.injectuy.LOG"
        const val BROADCAST_STATE = "com.injectuy.STATE"

        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_PROXY_HOST = "proxy_host"
        const val EXTRA_PROXY_PORT = "proxy_port"
        const val EXTRA_SSH_HOST = "ssh_host"
        const val EXTRA_SSH_PORT = "ssh_port"
        const val EXTRA_SSH_USER = "ssh_user"
        const val EXTRA_SSH_PASS = "ssh_pass"

        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(1, buildNotification("Connecting..."))
                handleStart(intent)
            }
            ACTION_STOP -> {
                handleStop()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val payload = intent.getStringExtra(EXTRA_PAYLOAD) ?: ""
        val proxyHost = intent.getStringExtra(EXTRA_PROXY_HOST) ?: ""
        val proxyPort = intent.getIntExtra(EXTRA_PROXY_PORT, 8080)
        val sshHost = intent.getStringExtra(EXTRA_SSH_HOST) ?: ""
        val sshPort = intent.getIntExtra(EXTRA_SSH_PORT, 22)
        val sshUser = intent.getStringExtra(EXTRA_SSH_USER) ?: ""
        val sshPass = intent.getStringExtra(EXTRA_SSH_PASS) ?: ""

        startJob?.cancel()
        sshTunnel?.disconnect()
        vpnInterface?.close()
        sshTunnel = null
        vpnInterface = null

        startJob = serviceScope.launch {
            try {
                sshTunnel = SshInjectorTunnel(
                    sshHost = sshHost,
                    sshPort = sshPort,
                    sshUser = sshUser,
                    sshPass = sshPass,
                    proxyHost = proxyHost,
                    proxyPort = proxyPort,
                    payload = payload,
                    localSocksPort = 10808,
                    onLog = { broadcastLog(it) }
                )
                sshTunnel?.connect()
                currentCoroutineContext().ensureActive()

                establishVpn()
                isRunning = true
                broadcastState(true)
                broadcastLog("Tunnel connected. Device traffic is bypassed until a packet forwarder is available.")
                updateNotification("Tunnel connected - traffic bypassed")
            } catch (e: Exception) {
                broadcastLog("Connection failed: ${e.localizedMessage ?: "Unknown error"}")
                handleStop()
            }
        }
    }

    private fun establishVpn() {
        val builder = Builder()
            .setSession("InjectUY")
            .setMtu(1500)
            .addAddress("172.19.0.1", 30)

        vpnInterface = builder.establish()
    }

    private fun handleStop() {
        startJob?.cancel()
        startJob = null
        sshTunnel?.disconnect()
        sshTunnel = null
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}

        isRunning = false
        broadcastState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcastLog(msg: String) {
        val intent = Intent(BROADCAST_LOG).apply {
            putExtra("msg", msg)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastState(running: Boolean) {
        val intent = Intent(BROADCAST_STATE).apply {
            putExtra("running", running)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "vpn_channel",
                "InjectUY VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, "vpn_channel")
            .setContentTitle("InjectUY Tunnel")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, buildNotification(status))
    }

    override fun onDestroy() {
        handleStop()
        serviceScope.cancel()
        super.onDestroy()
    }
}
