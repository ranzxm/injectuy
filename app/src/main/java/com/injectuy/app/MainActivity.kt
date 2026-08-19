package com.injectuy.app

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.injectuy.app.databinding.ActivityMainBinding
import com.injectuy.app.parser.TargetParser
import com.injectuy.app.security.ConfigSecurity
import com.injectuy.app.security.EncryptedConfig
import com.injectuy.app.service.TunnelVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isConnected = false
    private var isConnecting = false

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startTunnelService()
        } else {
            isConnecting = false
            updateUiState(false)
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
                    isConnecting = false
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

        appendLog("Running on $deviceModel ($deviceProduct), Android $release ($id) API $sdk. Version 1.0.1 Build 2.")

        setupListeners()
        updateUiState(TunnelVpnService.isRunning)
    }

    private fun setupListeners() {
        binding.btnConnect.setOnClickListener {
            if (isConnected || isConnecting) {
                stopTunnelService()
            } else {
                prepareAndStart()
            }
        }

        binding.btnClearLog.setOnClickListener {
            binding.tvLog.text = ""
            appendLog("Log cleared.")
        }

        binding.btnExport.setOnClickListener {
            showExportDialog()
        }

        binding.btnImport.setOnClickListener {
            showImportDialog()
        }
    }

    private fun showExportDialog() {
        val target = binding.etTarget.text.toString().trim()
        val proxy = binding.etProxy.text.toString().trim()
        val payload = binding.etPayload.text.toString().trim()

        val config = EncryptedConfig(
            target = target,
            proxy = proxy,
            payload = payload,
            isLocked = true
        )
        val encryptedData = ConfigSecurity.exportConfig(config)

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("InjectUY Config", encryptedData)
        clipboard.setPrimaryClip(clip)

        AlertDialog.Builder(this)
            .setTitle("Config Exported")
            .setMessage("Config terenkripsi AES-256 berhasil disalin ke clipboard:\n\n$encryptedData")
            .setPositiveButton("OK", null)
            .show()
        appendLog("Config successfully exported & copied to clipboard.")
    }

    private fun showImportDialog() {
        val input = EditText(this)
        input.hint = "Paste encrypted config (INJECTUY:...)"

        AlertDialog.Builder(this)
            .setTitle("Import Config")
            .setView(input)
            .setPositiveButton("IMPORT") { _, _ ->
                val raw = input.text.toString().trim()
                val config = ConfigSecurity.importConfig(raw)
                if (config != null) {
                    binding.etTarget.setText(config.target)
                    binding.etProxy.setText(config.proxy)
                    binding.etPayload.setText(config.payload)
                    appendLog("Config imported successfully!")
                    Toast.makeText(this, "Config Imported", Toast.LENGTH_SHORT).show()
                } else {
                    appendLog("Error: Invalid or corrupted config string!")
                    Toast.makeText(this, "Failed to decrypt config", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val targetRaw = binding.etTarget.text.toString().trim()
        if (targetRaw.isEmpty()) {
            Toast.makeText(this, "Target is required", Toast.LENGTH_SHORT).show()
            return
        }

        isConnecting = true
        setConnectingUiState()

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
        isConnecting = false
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun setConnectingUiState() {
        binding.btnConnect.text = "DISCONNECT"
        binding.btnConnect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF5252"))
        binding.btnConnect.setTextColor(Color.WHITE)
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
        if (text.startsWith("<") || text.contains("<font")) {
            val formatted = com.injectuy.app.util.LogFormatter.format(text)
            binding.tvLog.append(formatted)
            binding.tvLog.append("\n")
        } else {
            val rawEntry = "[$time] $text\n"
            val formatted = com.injectuy.app.util.LogFormatter.format(rawEntry)
            binding.tvLog.append(formatted)
        }
        binding.scrollLogs.post {
            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
        }
    }
}
