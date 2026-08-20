package com.injectuy.app

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.Manifest
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
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.injectuy.app.databinding.ActivityMainBinding
import com.injectuy.app.parser.TargetParser
import com.injectuy.app.security.ConfigSecurity
import com.injectuy.app.security.EncryptedConfig
import com.injectuy.app.service.TunnelVpnService
import java.text.SimpleDateFormat
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
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
    private var isApplyingConfig = false

    private companion object {
        const val MENU_IMPORT = 1
        const val MENU_EXPORT = 2
        const val MENU_CLEAR_CONFIG = 3
        const val MENU_CLEAR_LOG = 4
        const val PREFS_NAME = "injectuy_config"
        const val PREF_CONFIG = "saved_config"
        const val MAX_CONFIG_BYTES = 64 * 1024
    }

    private val saveConfigLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val data = pendingExportData ?: return@registerForActivityResult
        pendingExportData = null
        if (uri == null) return@registerForActivityResult
        try {
            requireNotNull(contentResolver.openOutputStream(uri)) { "Unable to open destination" }
                .bufferedWriter()
                .use { it.write(data) }
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
            val raw = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (output.size() + read > MAX_CONFIG_BYTES) {
                        throw IllegalArgumentException("Config file is too large")
                    }
                    output.write(buffer, 0, read)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }
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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVpnPreparation()
        } else {
            Toast.makeText(this, "Notification permission is required to control the tunnel", Toast.LENGTH_LONG).show()
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
        restorePersistedConfig()
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
        listOf(binding.etTarget, binding.etProxy, binding.etPayload).forEach { field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) {
                    if (!isApplyingConfig) saveConfigState()
                }
            })
        }
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
        if (isSshLocked || isProxyLocked || isPayloadLocked) {
            AlertDialog.Builder(this)
                .setTitle("Config locked")
                .setMessage("Locked configs cannot be exported or modified. Clear the config first if you need to create a new one.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
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
        if (raw.length > MAX_CONFIG_BYTES) {
            appendLog("Error: Config is too large.")
            return
        }
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

    private fun applyImportedConfig(config: EncryptedConfig, announce: Boolean = true, persist: Boolean = true) {
        isApplyingConfig = true
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
        isApplyingConfig = false

        if (persist) saveConfigState()
        if (announce) {
            appendLog("Config '$activeConfigName' imported successfully.")
            Toast.makeText(this, "Config Imported", Toast.LENGTH_SHORT).show()
        }
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

    private fun restorePersistedConfig() {
        val savedConfig = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_CONFIG, null) ?: return
        val config = ConfigSecurity.importConfig(savedConfig)
        if (config == null || ConfigSecurity.isExpired(config)) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_CONFIG).apply()
            return
        }
        applyImportedConfig(config, announce = false, persist = false)
    }

    private fun saveConfigState() {
        if (!::binding.isInitialized) return
        val config = EncryptedConfig(
            fileName = activeConfigName,
            serverMessage = activeServerMessage,
            target = currentTarget(),
            proxy = currentProxy(),
            payload = currentPayload(),
            lockSsh = isSshLocked,
            lockProxy = isProxyLocked,
            lockPayload = isPayloadLocked,
            expireDate = activeExpireDate
        )
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_CONFIG, ConfigSecurity.exportConfig(config))
            .apply()
    }

    private fun clearLockedConfig() {
        if (isConnected || isConnecting) {
            Toast.makeText(this, "Disconnect before clearing config", Toast.LENGTH_SHORT).show()
            return
        }
        clearConfigState()
        appendLog("Locked config cleared.")
    }

    private fun clearConfigState() {
        isApplyingConfig = true
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
        isApplyingConfig = false
        setConfigFormEnabled(true)
        updateLockedConfigStatus()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_CONFIG).apply()
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
        if (activeExpireDate > 0 && System.currentTimeMillis() > activeExpireDate) {
            clearConfigState()
            appendLog("Config has expired and was cleared.")
            Toast.makeText(this, "Config has expired", Toast.LENGTH_SHORT).show()
            return
        }
        val targetRaw = currentTarget()
        if (targetRaw.isEmpty()) {
            Toast.makeText(this, "Target is required", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        startVpnPreparation()
    }

    private fun startVpnPreparation() {
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
            putExtra(TunnelVpnService.EXTRA_CONFIG_EXPIRY, activeExpireDate)
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
        if (binding.tvLog.length > 20_000) {
            binding.tvLog.text = binding.tvLog.text.subSequence(binding.tvLog.length - 16_000, binding.tvLog.length)
        }
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
