package com.injectuy.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.injectuy.app.databinding.ActivityMainBinding
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

        setupListeners()
        updateUiState(TunnelVpnService.isRunning)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(TunnelVpnService.BROADCAST_LOG)
            addAction(TunnelVpnService.BROADCAST_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(serviceReceiver)
    }

    private fun setupListeners() {
        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbSsh) {
                binding.layoutSsh.visibility = View.VISIBLE
                binding.layoutVmess.visibility = View.GONE
            } else {
                binding.layoutSsh.visibility = View.GONE
                binding.layoutVmess.visibility = View.VISIBLE
            }
        }

        binding.btnConnect.setOnClickListener {
            if (isConnected) {
                stopTunnelService()
            } else {
                prepareAndStart()
            }
        }
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
        val isSsh = binding.rbSsh.isChecked
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            if (isSsh) {
                val host = binding.etSshHost.text.toString().trim()
                if (host.isEmpty()) {
                    Toast.makeText(this@MainActivity, "SSH Host required", Toast.LENGTH_SHORT).show()
                    return
                }
                putExtra(TunnelVpnService.EXTRA_MODE, "SSH")
                putExtra(TunnelVpnService.EXTRA_SSH_HOST, host)
                putExtra(TunnelVpnService.EXTRA_SSH_PORT, binding.etSshPort.text.toString().toIntOrNull() ?: 22)
                putExtra(TunnelVpnService.EXTRA_SSH_USER, binding.etSshUser.text.toString().trim())
                putExtra(TunnelVpnService.EXTRA_SSH_PASS, binding.etSshPass.text.toString().trim())
                putExtra(TunnelVpnService.EXTRA_PROXY_HOST, binding.etProxyHost.text.toString().trim())
                putExtra(TunnelVpnService.EXTRA_PROXY_PORT, binding.etProxyPort.text.toString().toIntOrNull() ?: 8080)
                putExtra(TunnelVpnService.EXTRA_PAYLOAD, binding.etPayload.text.toString().trim())
            } else {
                val vmessLink = binding.etVmessLink.text.toString().trim()
                if (vmessLink.isEmpty()) {
                    Toast.makeText(this@MainActivity, "VMess Link required", Toast.LENGTH_SHORT).show()
                    return
                }
                putExtra(TunnelVpnService.EXTRA_MODE, "VMESS")
                putExtra(TunnelVpnService.EXTRA_CONFIG, vmessLink)
            }
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
            binding.tvStatus.text = "CONNECTED"
            binding.tvStatus.setTextColor(Color.parseColor("#00E676"))
            binding.btnConnect.text = "STOP"
            binding.btnConnect.setBackgroundColor(Color.parseColor("#D32F2F"))
        } else {
            binding.tvStatus.text = "DISCONNECTED"
            binding.tvStatus.setTextColor(Color.parseColor("#FF5252"))
            binding.btnConnect.text = "START"
            binding.btnConnect.setBackgroundColor(Color.parseColor("#1E88E5"))
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
