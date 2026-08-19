package com.alite.ssh

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import com.alite.ssh.databinding.ActivityMainBinding
import com.alite.ssh.databinding.DialogAddMappingBinding
import com.alite.ssh.databinding.DialogAdvancedBinding
import com.alite.ssh.databinding.ItemPortMappingBinding
import com.alite.ssh.databinding.SheetLogsBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity(), TunnelHub.Observer {
    private lateinit var binding: ActivityMainBinding
    private val mappings = mutableListOf<PortMapping>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private var trustOnFirstUse = true
    private var ignoreHostKeyMismatch = false
    private var lastUiState: String? = null
    private var logViewRef: TextView? = null
    private var logScrollRef: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        applyWindowInsets()
        restoreForm()
        updateAuthFields()
        renderMappings()
        applyStatus(TunnelHub.snapshot())

        binding.authGroup.addOnButtonCheckedListener { _, _, _ -> updateAuthFields() }
        binding.connectButton.setOnClickListener { toggleTunnel() }
        binding.addMappingButton.setOnClickListener { showAddMappingDialog() }
        binding.hostInput.doAfterTextChanged { renderMappings() }
        requestNotificationPermission()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.home, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logs -> {
                showLogs()
                true
            }
            R.id.action_advanced -> {
                showAdvanced()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        TunnelHub.addObserver(this)
    }

    override fun onStop() {
        TunnelHub.removeObserver(this)
        super.onStop()
    }

    override fun onTunnelEvent(snapshot: TunnelHub.Snapshot) {
        runOnUiThread {
            applyStatus(snapshot)
            updateLogSheet(snapshot)
            if (snapshot.state != lastUiState) {
                if (snapshot.state == "error" && lastUiState != null) {
                    snack(R.string.err_connect_failed, R.string.log_title) { showLogs() }
                }
                lastUiState = snapshot.state
                updateAuthFields()
                renderMappings()
            }
        }
    }

    private fun toggleTunnel() {
        if (isBusyState(TunnelHub.state)) {
            startService(
                Intent(this, SshTunnelService::class.java).setAction(SshTunnelService.ACTION_STOP),
            )
            return
        }
        val config = readForm() ?: return
        persistForm()
        TunnelHub.config = config
        TunnelHub.resetLogs()
        TunnelHub.appendLog("${timeFmt.format(Date())} 开始连接")
        TunnelHub.setState("connecting")
        ContextCompat.startForegroundService(
            this,
            Intent(this, SshTunnelService::class.java).setAction(SshTunnelService.ACTION_START),
        )
    }

    private fun readForm(): TunnelConfig? {
        val host = binding.hostInput.text.toString().trim()
        val username = binding.userInput.text.toString().trim()
        val sshPort = binding.portInput.text.toString().toIntOrNull() ?: 22
        binding.hostLayout.error = if (host.isEmpty()) getString(R.string.err_required_host) else null
        binding.userLayout.error = if (username.isEmpty()) getString(R.string.err_required_user) else null
        if (host.isEmpty() || username.isEmpty()) {
            if (host.isEmpty()) binding.hostInput.requestFocus() else binding.userInput.requestFocus()
            return null
        }
        if (mappings.none { it.enabled }) {
            snack(R.string.err_need_mapping)
            return null
        }
        val useKey = binding.authKey.isChecked
        val password = binding.passwordInput.text.toString().ifEmpty { null }
        val privateKey = binding.keyInput.text.toString().ifEmpty { null }
        binding.passwordLayout.error = null
        binding.keyLayout.error = null
        if (useKey && privateKey.isNullOrBlank()) {
            binding.keyLayout.error = getString(R.string.err_need_key)
            binding.keyInput.requestFocus()
            return null
        }
        if (!useKey && password.isNullOrEmpty()) {
            binding.passwordLayout.error = getString(R.string.err_need_password)
            binding.passwordInput.requestFocus()
            return null
        }
        return TunnelConfig(
            host = host,
            port = sshPort,
            username = username,
            password = if (useKey) null else password,
            privateKey = if (useKey) privateKey else null,
            passphrase = binding.passphraseInput.text.toString().ifEmpty { null },
            mappings = mappings.toList(),
            trustOnFirstUse = trustOnFirstUse,
            ignoreHostKeyMismatch = ignoreHostKeyMismatch,
        )
    }

    private fun persistForm() {
        prefs().edit {
            putString(KEY_HOST, binding.hostInput.text.toString())
            putString(KEY_PORT, binding.portInput.text.toString())
            putString(KEY_USER, binding.userInput.text.toString())
            putBoolean(KEY_USE_KEY, binding.authKey.isChecked)
            putString(KEY_MAPPINGS, encodeMappings(mappings))
            putBoolean(KEY_TOFU, trustOnFirstUse)
            putBoolean(KEY_SKIP, ignoreHostKeyMismatch)
        }
    }

    private fun restoreForm() {
        val p = prefs()
        binding.hostInput.setText(p.getString(KEY_HOST, ""))
        binding.portInput.setText(p.getString(KEY_PORT, "22"))
        binding.userInput.setText(p.getString(KEY_USER, ""))
        trustOnFirstUse = p.getBoolean(KEY_TOFU, true)
        ignoreHostKeyMismatch = p.getBoolean(KEY_SKIP, false)
        binding.authGroup.check(
            if (p.getBoolean(KEY_USE_KEY, false)) R.id.authKey else R.id.authPassword,
        )
        mappings.clear()
        mappings.addAll(decodeMappings(p.getString(KEY_MAPPINGS, null), p))
    }

    private fun renderMappings() {
        val host = binding.hostInput.text?.toString()?.trim().orEmpty()
            .ifBlank { getString(R.string.ssh_group_title) }
        val inflater = LayoutInflater.from(this)
        binding.mappingEmpty.isVisible = mappings.isEmpty()
        binding.mappingList.isVisible = mappings.isNotEmpty()
        binding.mappingList.removeAllViews()
        mappings.toList().forEach { mapping ->
            val row = ItemPortMappingBinding.inflate(inflater, binding.mappingList, false)
            row.mappingNameView.text = mapping.title()
            row.mappingRouteView.text = getString(
                R.string.mapping_route,
                mapping.localPort,
                host,
                mapping.remotePort,
            )
            row.root.alpha = if (mapping.enabled) 1f else 0.62f
            row.enableButton.setOnClickListener(null)
            row.enableButton.isChecked = mapping.enabled
            row.enableButton.text = getString(
                if (mapping.enabled) R.string.mapping_on else R.string.mapping_off,
            )
            row.enableButton.setOnClickListener {
                val idx = mappings.indexOfFirst { it.id == mapping.id }
                if (idx < 0) {
                    return@setOnClickListener
                }
                val checked = !mappings[idx].enabled
                mappings[idx] = mappings[idx].copy(enabled = checked)
                persistForm()
                renderMappings()
                pushForwardsIfRunning()
                if (isBusyState(TunnelHub.state) && mappings.none { it.enabled }) {
                    snack(R.string.warn_no_enabled_mapping)
                }
            }
            row.openButton.setOnClickListener { openMapping(mapping) }
            row.deleteButton.setOnClickListener { confirmDelete(mapping) }
            binding.mappingList.addView(row.root)
        }
    }

    private fun openMapping(mapping: PortMapping) {
        when {
            !isConnectedState(TunnelHub.state) -> snack(R.string.err_open_need_connect)
            !mapping.enabled -> snack(R.string.err_open_need_enable)
            else -> try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${mapping.localPort}/")),
                )
            } catch (_: ActivityNotFoundException) {
                snack(R.string.err_no_browser)
            }
        }
    }

    private fun confirmDelete(mapping: PortMapping) {
        val host = binding.hostInput.text?.toString()?.trim().orEmpty()
        val message = buildString {
            append(mapping.display(host))
            if (isBusyState(TunnelHub.state)) {
                append("\n\n")
                append(getString(R.string.delete_mapping_running))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_mapping_title)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                mappings.removeAll { it.id == mapping.id }
                persistForm()
                renderMappings()
                pushForwardsIfRunning()
                snack(R.string.msg_mapping_deleted)
            }
            .show()
    }

    private fun showAddMappingDialog() {
        if (mappings.size >= MAX_MAPPINGS) {
            snack(R.string.err_too_many_mappings)
            return
        }
        val view = DialogAddMappingBinding.inflate(layoutInflater)
        view.localPortInput.setText(suggestedLocalPort().toString())
        view.remotePortInput.setText(suggestedRemotePort().toString())
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_add_mapping)
            .setView(view.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_add, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = view.nameInput.text.toString().trim()
                val local = view.localPortInput.text.toString().toIntOrNull()
                val remote = view.remotePortInput.text.toString().toIntOrNull()
                view.localPortLayout.error = null
                view.remotePortLayout.error = null
                if (local == null || local !in 1..65535) {
                    view.localPortLayout.error = getString(R.string.err_bad_port)
                    return@setOnClickListener
                }
                if (remote == null || remote !in 1..65535) {
                    view.remotePortLayout.error = getString(R.string.err_bad_port)
                    return@setOnClickListener
                }
                if (mappings.any { it.localPort == local }) {
                    view.localPortLayout.error = getString(R.string.err_dup_local_port)
                    return@setOnClickListener
                }
                val mapping = PortMapping(
                    name = name,
                    localPort = local,
                    remotePort = remote,
                    enabled = true,
                )
                mappings.add(mapping)
                persistForm()
                renderMappings()
                pushForwardsIfRunning()
                dialog.dismiss()
                snack(getString(R.string.msg_mapping_added, mapping.title()))
            }
        }
        dialog.show()
        view.nameInput.requestFocus()
    }

    private fun showAdvanced() {
        val view = DialogAdvancedBinding.inflate(layoutInflater)
        val connected = isBusyState(TunnelHub.state)
        view.tofuSwitch.isChecked = trustOnFirstUse
        view.skipMismatchSwitch.isChecked = ignoreHostKeyMismatch
        view.tofuSwitch.isEnabled = !connected
        view.skipMismatchSwitch.isEnabled = !connected
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.advanced_title)
            .setView(view.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                if (!connected) {
                    trustOnFirstUse = view.tofuSwitch.isChecked
                    ignoreHostKeyMismatch = view.skipMismatchSwitch.isChecked
                    persistForm()
                }
            }
            .show()
    }

    private fun showLogs() {
        val dialog = BottomSheetDialog(this)
        val sheet = SheetLogsBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)
        logViewRef = sheet.logView
        logScrollRef = sheet.logScroll
        updateLogSheet(TunnelHub.snapshot())
        sheet.copyLogsButton.setOnClickListener { copyLogs() }
        sheet.clearLogsButton.setOnClickListener { TunnelHub.resetLogs() }
        dialog.setOnDismissListener {
            logViewRef = null
            logScrollRef = null
        }
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private fun copyLogs() {
        val text = TunnelHub.snapshot().logs.joinToString("\n")
        if (text.isBlank()) {
            snack(R.string.log_empty)
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.log_title), text))
        snack(R.string.msg_logs_copied)
    }

    private fun updateLogSheet(snapshot: TunnelHub.Snapshot) {
        val view = logViewRef ?: return
        view.text = snapshot.logs.joinToString("\n").ifBlank { getString(R.string.log_empty) }
        logScrollRef?.post { logScrollRef?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun applyStatus(snapshot: TunnelHub.Snapshot) {
        val phase = uiPhase(snapshot.state)
        binding.connectProgress.isVisible = phase == UiPhase.Connecting
        binding.connectButton.text = when (phase) {
            UiPhase.Connected -> getString(R.string.action_disconnect)
            UiPhase.Connecting -> getString(R.string.action_cancel_connect)
            UiPhase.Error -> getString(R.string.action_retry)
            UiPhase.Idle -> getString(R.string.action_connect)
        }
        binding.heroStatusTitle.text = when (snapshot.state) {
            "connecting" -> getString(R.string.hero_connecting_title)
            "authenticating" -> getString(R.string.hero_auth_title)
            "listening" -> getString(R.string.hero_connected_title)
            "error" -> getString(R.string.hero_error_title)
            else -> getString(R.string.hero_idle_title)
        }
        val dot = when (phase) {
            UiPhase.Connected -> R.color.status_connected
            UiPhase.Connecting -> R.color.status_connecting
            UiPhase.Error -> R.color.status_error
            UiPhase.Idle -> R.color.status_idle
        }
        ImageViewCompat.setImageTintList(
            binding.heroStatusDot,
            ColorStateList.valueOf(ContextCompat.getColor(this, dot)),
        )
        setSshFormEnabled(phase == UiPhase.Idle || phase == UiPhase.Error)
    }

    private fun pushForwardsIfRunning() {
        if (!isBusyState(TunnelHub.state)) {
            return
        }
        val current = TunnelHub.config ?: return
        TunnelHub.config = current.copy(mappings = mappings.toList())
        startService(
            Intent(this, SshTunnelService::class.java).setAction(SshTunnelService.ACTION_UPDATE_FORWARDS),
        )
    }

    private fun updateAuthFields() {
        val key = binding.authKey.isChecked
        val hideSecrets = uiPhase(TunnelHub.state) == UiPhase.Connected
        binding.passwordLayout.isVisible = !key && !hideSecrets
        binding.keyLayout.isVisible = key && !hideSecrets
        binding.passphraseLayout.isVisible = key && !hideSecrets
    }

    private fun setSshFormEnabled(enabled: Boolean) {
        binding.hostInput.isEnabled = enabled
        binding.portInput.isEnabled = enabled
        binding.userInput.isEnabled = enabled
        binding.passwordInput.isEnabled = enabled
        binding.keyInput.isEnabled = enabled
        binding.passphraseInput.isEnabled = enabled
        binding.authPassword.isEnabled = enabled
        binding.authKey.isEnabled = enabled
        binding.authGroup.isEnabled = enabled
        binding.hostLayout.isEnabled = enabled
        binding.portLayout.isEnabled = enabled
        binding.userLayout.isEnabled = enabled
        binding.passwordLayout.isEnabled = enabled
        binding.keyLayout.isEnabled = enabled
        binding.passphraseLayout.isEnabled = enabled
    }

    private fun suggestedLocalPort(): Int {
        val used = mappings.map { it.localPort }.toSet()
        var port = 8080
        while (port in used && port < 65535) {
            port++
        }
        return port
    }

    private fun suggestedRemotePort(): Int = mappings.lastOrNull()?.remotePort ?: 80

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { v, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = status.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.scroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime(),
            )
            v.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun snack(message: Int, action: Int? = null, onAction: (() -> Unit)? = null) {
        val bar = Snackbar.make(binding.coordinator, message, Snackbar.LENGTH_LONG)
        if (action != null && onAction != null) {
            bar.setAction(action) { onAction() }
        }
        bar.show()
    }

    private fun snack(message: String) {
        Snackbar.make(binding.coordinator, message, Snackbar.LENGTH_LONG).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun prefs() = getSharedPreferences("form", MODE_PRIVATE)

    private enum class UiPhase { Idle, Connecting, Connected, Error }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USER = "user"
        private const val KEY_USE_KEY = "use_key"
        private const val KEY_MAPPINGS = "mappings"
        private const val KEY_LOCAL = "local_port"
        private const val KEY_REMOTE_PORT = "remote_port"
        private const val KEY_TOFU = "tofu"
        private const val KEY_SKIP = "skip"
        private const val MAX_MAPPINGS = 16

        private fun uiPhase(state: String): UiPhase = when (state) {
            "connecting", "authenticating" -> UiPhase.Connecting
            "listening" -> UiPhase.Connected
            "error" -> UiPhase.Error
            else -> UiPhase.Idle
        }

        private fun isBusyState(state: String) =
            state == "connecting" || state == "authenticating" || state == "listening"

        private fun isConnectedState(state: String) = state == "listening"

        fun encodeMappings(items: List<PortMapping>): String =
            items.joinToString(";") {
                listOf(
                    it.localPort.toString(),
                    it.remotePort.toString(),
                    if (it.enabled) "1" else "0",
                    it.id,
                    Uri.encode(it.name).orEmpty(),
                ).joinToString(":")
            }

        fun decodeMappings(raw: String?, prefs: android.content.SharedPreferences): List<PortMapping> {
            if (!raw.isNullOrBlank()) {
                return raw.split(';').mapNotNull { part ->
                    val bits = part.split(':')
                    if (bits.size < 3) {
                        null
                    } else {
                        val local = bits[0].toIntOrNull() ?: return@mapNotNull null
                        val remote = bits[1].toIntOrNull() ?: return@mapNotNull null
                        val enabled = bits[2] != "0"
                        val id = bits.getOrNull(3)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                        val name = bits.getOrNull(4)?.let { Uri.decode(it) }.orEmpty()
                        PortMapping(
                            id = id,
                            name = name,
                            localPort = local,
                            remotePort = remote,
                            enabled = enabled,
                        )
                    }
                }
            }
            val local = prefs.getString(KEY_LOCAL, "8080")?.toIntOrNull() ?: 8080
            val remote = prefs.getString(KEY_REMOTE_PORT, "80")?.toIntOrNull() ?: 80
            return listOf(PortMapping(localPort = local, remotePort = remote, enabled = true))
        }
    }
}
