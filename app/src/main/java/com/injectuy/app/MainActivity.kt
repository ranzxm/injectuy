package com.injectuy.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.injectuy.app.databinding.ActivityMainBinding
import com.injectuy.app.parser.TargetParser
import com.injectuy.app.service.TunnelVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isConnected = false

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startTunnelService()
        } else {
            appendLog("VPN Permission Rejected.")
        }
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TunnelVpnService.BROADCAST_LOG -> {
                    val msg = intent.getStringExtra("msg") ?: return
                    appendLog(msg)
                }
                TunnelVpnService.BROADCAST_STATE -> {
                    val running = intent.getBooleanExtra("running", false)
                    updateUiState(running)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val deviceModel = Build.MODEL
        val deviceProduct = Build.PRODUCT
        val release = Build.VERSION.RELEASE
        val sdk = Build.VERSION.SDK_INT
        val id = Build.ID

        appendLog("Running on $deviceModel ($deviceProduct), Android $release ($id) API $sdk. Version 1.0.0 Build 1.")

        binding.btnConnect.setOnClickListener {
            if (isConnected) {
                stopTunnelService()
            } else {
                prepareAndStart()
            }
        }

        updateUiState(TunnelVpnService.isRunning)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(TunnelVpnService.BROADCAST_LOG)
            addAction(TunnelVpnService.BROADCAST_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(serviceReceiver)
    }

    private fun prepareAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            startTunnelService()
        }
    }

    private fun startTunnelService() {
        val targetRaw = binding.etTarget.text.toString().trim()
        val proxyRaw = binding.etProxy.text.toString().trim()
        val payloadRaw = binding.etPayload.text.toString().trim()

        if (targetRaw.isEmpty()) {
            Toast.makeText(this, "Target is required", Toast.LENGTH_SHORT).show()
            return
        }

        val creds = TargetParser.parse(targetRaw)
        val (proxyHost, proxyPort) = TargetParser.parseProxy(proxyRaw)

        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            putExtra(TunnelVpnService.EXTRA_MODE, "SSH")
            putExtra(TunnelVpnService.EXTRA_SSH_HOST, creds.host)
            putExtra(TunnelVpnService.EXTRA_SSH_PORT, creds.port)
            putExtra(TunnelVpnService.EXTRA_SSH_USER, creds.user)
            putExtra(TunnelVpnService.EXTRA_SSH_PASS, creds.pass)
            putExtra(TunnelVpnService.EXTRA_PROXY_HOST, proxyHost)
            putExtra(TunnelVpnService.EXTRA_PROXY_PORT, proxyPort)
            putExtra(TunnelVpnService.EXTRA_PAYLOAD, payloadRaw)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopTunnelService() {
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun updateUiState(running: Boolean) {
        isConnected = running
        if (running) {
            binding.btnConnect.text = "DISCONNECT"
            binding.btnConnect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF5252"))
            binding.btnConnect.setTextColor(Color.WHITE)
        } else {
            binding.btnConnect.text = "CONNECT"
            binding.btnConnect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#A87FFB"))
            binding.btnConnect.setTextColor(Color.parseColor("#121214"))
        }
    }

    private fun appendLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        binding.tvLog.append("[$time] $text\n")
        binding.scrollLogs.post {
            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
        }
    }
}
