package com.injectuy.app

import android.app.AlertDialog
import android.app.DatePickerDialog
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.injectuy.app.databinding.ActivityMainBinding
import com.injectuy.app.parser.TargetParser
import com.injectuy.app.security.ConfigSecurity
import com.injectuy.app.security.EncryptedConfig
import com.injectuy.app.service.TunnelVpnService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isConnected = false
    private var isConnecting = false
    private var lockedTarget: String? = null
    private var lockedProxy: String? = null
    private var lockedPayload: String? = null
    private var isSshLocked = false
    private var isProxyLocked = false
    private var isPayloadLocked = false
    private var activeConfigName = "InjectUY Config"
    private var activeServerMessage = ""
    private var activeExpireDate = 0L
    private var pendingExportData: String? = null

    private companion object {
        const val MENU_IMPORT = 1
        const val MENU_EXPORT = 2
        const val MENU_CLEAR_CONFIG = 3
        const val MENU_CLEAR_LOG = 4
    }

    private val saveConfigLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val data = pendingExportData ?: return@registerForActivityResult
        pendingExportData = null
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(data) }
            appendLog("Config saved successfully.")
        } catch (e: Exception) {
            appendLog("Failed to save config: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    private val openConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (raw.isNullOrBlank()) {
                appendLog("Error: Config file is empty or unreadable.")
            } else {
                importConfig(raw)
            }
        } catch (e: Exception) {
            appendLog("Failed to open config: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

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

        appendLog("InjectUY ready.")

        setupListeners()
        restoreLockedConfigState(savedInstanceState)
        updateUiState(TunnelVpnService.isRunning)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("locked_target", lockedTarget)
        outState.putString("locked_proxy", lockedProxy)
        outState.putString("locked_payload", lockedPayload)
        outState.putBoolean("ssh_locked", isSshLocked)
        outState.putBoolean("proxy_locked", isProxyLocked)
        outState.putBoolean("payload_locked", isPayloadLocked)
        outState.putString("config_name", activeConfigName)
        outState.putString("server_message", activeServerMessage)
        outState.putLong("expire_date", activeExpireDate)
        outState.putString("pending_export_data", pendingExportData)
    }

    private fun setupListeners() {
        binding.btnConnect.setOnClickListener {
            if (isConnected || isConnecting) {
                stopTunnelService()
            } else {
                prepareAndStart()
            }
        }

        binding.btnAppInfo.setOnClickListener { showAppInfo() }
        binding.btnMenu.setOnClickListener { showOverflowMenu() }
    }

    private fun showOverflowMenu() {
        val menu = PopupMenu(this, binding.btnMenu)
        val configActionsEnabled = !isConnected && !isConnecting
        menu.menu.add(0, MENU_IMPORT, 0, "Import config").isEnabled = configActionsEnabled
        menu.menu.add(0, MENU_EXPORT, 1, "Export config").isEnabled = configActionsEnabled
        if (isSshLocked || isProxyLocked || isPayloadLocked) {
            menu.menu.add(0, MENU_CLEAR_CONFIG, 2, "Clear config").isEnabled = configActionsEnabled
        }
        menu.menu.add(0, MENU_CLEAR_LOG, 3, "Clear log")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_IMPORT -> showImportDialog()
                MENU_EXPORT -> showExportDialog()
                MENU_CLEAR_CONFIG -> clearLockedConfig()
                MENU_CLEAR_LOG -> {
                    binding.tvLog.text = ""
                    appendLog("Log cleared.")
                }
            }
            true
        }
        menu.show()
    }

    private fun showAppInfo() {
        AlertDialog.Builder(this)
            .setTitle("InjectUY")
            .setMessage("Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})\n\nCreated by bcXrefulTEAM")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showExportDialog() {
        val content = layoutInflater.inflate(R.layout.dialog_export_config, null)
        val nameInput = content.findViewById<EditText>(R.id.etConfigName)
        val expiryCheck = content.findViewById<CheckBox>(R.id.cbExpire)
        val expiryDate = content.findViewById<android.widget.TextView>(R.id.tvExpiryDate)
        val messageCheck = content.findViewById<CheckBox>(R.id.cbServerMessage)
        val messageInput = content.findViewById<EditText>(R.id.etServerMessage)
        val sshLock = content.findViewById<CheckBox>(R.id.cbLockSsh)
        val proxyLock = content.findViewById<CheckBox>(R.id.cbLockProxy)
        val payloadLock = content.findViewById<CheckBox>(R.id.cbLockPayload)
        var selectedExpiryDate = activeExpireDate

        nameInput.setText(activeConfigName)
        expiryCheck.isChecked = activeExpireDate > 0
        messageCheck.isChecked = activeServerMessage.isNotBlank()
        messageInput.setText(activeServerMessage)
        sshLock.isChecked = isSshLocked
        proxyLock.isChecked = isProxyLocked
        payloadLock.isChecked = isPayloadLocked

        fun updateExpiryDate() {
            expiryDate.text = "Expires: ${formatExpiryDate(selectedExpiryDate)}"
            expiryDate.visibility = View.VISIBLE
        }

        fun showDatePicker() {
            val calendar = Calendar.getInstance().apply {
                if (selectedExpiryDate > 0) timeInMillis = selectedExpiryDate
            }
            val dialog = DatePickerDialog(this, { _, year, month, day ->
                selectedExpiryDate = Calendar.getInstance().apply {
                    set(year, month, day, 23, 59, 59)
                    set(Calendar.MILLISECOND, 999)
                }.timeInMillis
                updateExpiryDate()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
            dialog.setOnCancelListener {
                if (selectedExpiryDate == 0L) expiryCheck.isChecked = false
            }
            dialog.show()
        }

        if (expiryCheck.isChecked) updateExpiryDate()
        messageInput.visibility = if (messageCheck.isChecked) View.VISIBLE else View.GONE
        expiryCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                showDatePicker()
            } else {
                selectedExpiryDate = 0L
                expiryDate.visibility = View.GONE
            }
        }
        messageCheck.setOnCheckedChangeListener { _, checked ->
            messageInput.visibility = if (checked) View.VISIBLE else View.GONE
        }
        expiryDate.setOnClickListener { showDatePicker() }

        fun buildConfig(): EncryptedConfig? {
            return EncryptedConfig(
                fileName = nameInput.text.toString().trim().ifEmpty { "InjectUY Config" },
                serverMessage = if (messageCheck.isChecked) messageInput.text.toString().trim() else "",
                target = currentTarget(),
                proxy = currentProxy(),
                payload = currentPayload(),
                lockSsh = sshLock.isChecked,
                lockProxy = proxyLock.isChecked,
                lockPayload = payloadLock.isChecked,
                expireDate = if (expiryCheck.isChecked) selectedExpiryDate else 0L
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Export Config")
            .setView(content)
            .setPositiveButton("COPY") { _, _ ->
                val config = buildConfig() ?: return@setPositiveButton
                val encryptedData = ConfigSecurity.exportConfig(config)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(config.fileName, encryptedData))
                appendLog("Config copied to clipboard.")
            }
            .setNeutralButton("SAVE FILE") { _, _ ->
                val config = buildConfig() ?: return@setNeutralButton
                pendingExportData = ConfigSecurity.exportConfig(config)
                saveConfigLauncher.launch(sanitizeFileName(config.fileName) + ".injectuy")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showImportDialog() {
        val content = layoutInflater.inflate(R.layout.dialog_import_config, null)
        val input = content.findViewById<EditText>(R.id.etImportData)

        AlertDialog.Builder(this)
            .setTitle("Import Config")
            .setView(content)
            .setPositiveButton("IMPORT") { _, _ ->
                importConfig(input.text.toString())
            }
            .setNeutralButton("OPEN FILE") { _, _ -> openConfigLauncher.launch(arrayOf("application/octet-stream", "text/plain", "*/*")) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importConfig(raw: String) {
        val config = ConfigSecurity.importConfig(raw)
        when {
            config == null -> {
                appendLog("Error: Invalid or corrupted config string.")
                Toast.makeText(this, "Failed to decrypt config", Toast.LENGTH_SHORT).show()
            }
            ConfigSecurity.isExpired(config) -> {
                appendLog("Error: Config has expired.")
                Toast.makeText(this, "Config has expired", Toast.LENGTH_SHORT).show()
            }
            else -> applyImportedConfig(config)
        }
    }

    private fun applyImportedConfig(config: EncryptedConfig) {
        val legacyLockAll = config.isLocked
        isSshLocked = config.lockSsh || legacyLockAll
        isProxyLocked = config.lockProxy || legacyLockAll
        isPayloadLocked = config.lockPayload || legacyLockAll
        lockedTarget = config.target.takeIf { isSshLocked }
        lockedProxy = config.proxy.takeIf { isProxyLocked }
        lockedPayload = config.payload.takeIf { isPayloadLocked }
        activeConfigName = config.fileName.ifBlank { "InjectUY Config" }
        activeServerMessage = config.serverMessage
        activeExpireDate = config.expireDate

        setConfigField(binding.etTarget, config.target, isSshLocked)
        setConfigField(binding.etProxy, config.proxy, isProxyLocked)
        setConfigField(binding.etPayload, config.payload, isPayloadLocked)
        setConfigFormEnabled(!isConnected && !isConnecting)
        updateLockedConfigStatus()

        appendLog("Config '$activeConfigName' imported successfully.")
        Toast.makeText(this, "Config Imported", Toast.LENGTH_SHORT).show()
    }

    private fun setConfigField(field: EditText, value: String, locked: Boolean) {
        field.setText(if (locked) "" else value)
        field.hint = ""
    }

    private fun restoreLockedConfigState(state: Bundle?) {
        if (state == null) return
        lockedTarget = state.getString("locked_target")
        lockedProxy = state.getString("locked_proxy")
        lockedPayload = state.getString("locked_payload")
        isSshLocked = state.getBoolean("ssh_locked")
        isProxyLocked = state.getBoolean("proxy_locked")
        isPayloadLocked = state.getBoolean("payload_locked")
        activeConfigName = state.getString("config_name") ?: activeConfigName
        activeServerMessage = state.getString("server_message") ?: activeServerMessage
        activeExpireDate = state.getLong("expire_date")
        pendingExportData = state.getString("pending_export_data")

        if (isSshLocked) setConfigField(binding.etTarget, lockedTarget.orEmpty(), true)
        if (isProxyLocked) setConfigField(binding.etProxy, lockedProxy.orEmpty(), true)
        if (isPayloadLocked) setConfigField(binding.etPayload, lockedPayload.orEmpty(), true)
        updateLockedConfigStatus()
    }

    private fun clearLockedConfig() {
        if (isConnected || isConnecting) {
            Toast.makeText(this, "Disconnect before clearing config", Toast.LENGTH_SHORT).show()
            return
        }
        lockedTarget = null
        lockedProxy = null
        lockedPayload = null
        isSshLocked = false
        isProxyLocked = false
        isPayloadLocked = false
        activeConfigName = "InjectUY Config"
        activeServerMessage = ""
        activeExpireDate = 0L
        binding.etTarget.text?.clear()
        binding.etProxy.text?.clear()
        binding.etPayload.text?.clear()
        binding.etTarget.hint = ""
        binding.etProxy.hint = ""
        binding.etPayload.hint = ""
        setConfigFormEnabled(true)
        updateLockedConfigStatus()
        appendLog("Locked config cleared.")
    }

    private fun updateLockedConfigStatus() {
        val hasLockedConfig = isSshLocked || isProxyLocked || isPayloadLocked
        binding.targetGroup.visibility = if (isSshLocked) View.GONE else View.VISIBLE
        binding.proxyGroup.visibility = if (isProxyLocked) View.GONE else View.VISIBLE
        binding.payloadGroup.visibility = if (isPayloadLocked) View.GONE else View.VISIBLE
        binding.tvConfigStatus.visibility = if (hasLockedConfig) View.VISIBLE else View.GONE
        if (hasLockedConfig) {
            val expiry = if (activeExpireDate > 0) formatExpiryDate(activeExpireDate) else "Never"
            binding.tvConfigStatus.text = "Locked config | Expires: $expiry"
        }
    }

    private fun currentTarget(): String = lockedTarget ?: binding.etTarget.text.toString().trim()

    private fun currentProxy(): String = lockedProxy ?: binding.etProxy.text.toString().trim()

    private fun currentPayload(): String = lockedPayload ?: binding.etPayload.text.toString().trim()

    private fun formatExpiryDate(expiryDate: Long): String {
        if (expiryDate <= 0) return ""
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(expiryDate))
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "InjectUY Config" }
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
        val targetRaw = currentTarget()
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
        val targetRaw = currentTarget()
        val proxyRaw = currentProxy()
        val payloadRaw = currentPayload()

        val creds = TargetParser.parse(targetRaw)
        val (proxyHost, proxyPort) = TargetParser.parseProxy(proxyRaw)

        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            putExtra(TunnelVpnService.EXTRA_SSH_HOST, creds.host)
            putExtra(TunnelVpnService.EXTRA_SSH_PORT, creds.port)
            putExtra(TunnelVpnService.EXTRA_SSH_USER, creds.user)
            putExtra(TunnelVpnService.EXTRA_SSH_PASS, creds.pass)
            putExtra(TunnelVpnService.EXTRA_SERVER_MESSAGE, activeServerMessage)
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
        setConfigFormEnabled(false)
        binding.btnConnect.text = "DISCONNECT"
        binding.btnConnect.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF5252"))
        binding.btnConnect.setTextColor(Color.WHITE)
    }

    private fun updateUiState(running: Boolean) {
        isConnected = running
        setConfigFormEnabled(!running)
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

    private fun setConfigFormEnabled(enabled: Boolean) {
        binding.etTarget.isEnabled = enabled && !isSshLocked
        binding.etProxy.isEnabled = enabled && !isProxyLocked
        binding.etPayload.isEnabled = enabled && !isPayloadLocked
    }

    private fun appendLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        if (text.trimStart().startsWith("<")) {
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
