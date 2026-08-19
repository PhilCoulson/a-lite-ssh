package com.alite.ssh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import com.alite.ssh.databinding.ActivityMainBinding
import com.alite.ssh.databinding.ItemPortMappingBinding
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        restoreForm()
        updateAuthFields()
        renderMappings()

        binding.authGroup.setOnCheckedChangeListener { _, _ -> updateAuthFields() }
        binding.connectButton.setOnClickListener { toggleTunnel() }
        binding.addMappingButton.setOnClickListener { addMapping() }
        binding.hostInput.doAfterTextChanged { renderMappings() }
        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        TunnelHub.addObserver(this)
    }

    override fun onStop() {
        TunnelHub.removeObserver(this)
        super.onStop()
    }

    private var lastUiState: String? = null

    override fun onTunnelEvent(snapshot: TunnelHub.Snapshot) {
        runOnUiThread {
            val running = isRunningState(snapshot.state)
            binding.connectButton.text = getString(
                if (running) R.string.action_disconnect else R.string.action_connect,
            )
            binding.statusText.text = getString(R.string.status_fmt, snapshot.state)
            binding.logView.text = snapshot.logs.joinToString("\n")
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
            if (snapshot.state != lastUiState) {
                lastUiState = snapshot.state
                setSshFormEnabled(!running)
                renderMappings()
            }
        }
    }

    private fun toggleTunnel() {
        if (isRunningState(TunnelHub.state)) {
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

    private fun addMapping() {
        if (mappings.size >= MAX_MAPPINGS) {
            Toast.makeText(this, R.string.err_too_many_mappings, Toast.LENGTH_SHORT).show()
            return
        }
        val local = binding.newLocalPortInput.text.toString().toIntOrNull()
        val remote = binding.newRemotePortInput.text.toString().toIntOrNull()
        if (local == null || remote == null || local !in 1..65535 || remote !in 1..65535) {
            Toast.makeText(this, R.string.err_bad_port, Toast.LENGTH_SHORT).show()
            return
        }
        if (mappings.any { it.localPort == local }) {
            Toast.makeText(this, R.string.err_dup_local_port, Toast.LENGTH_SHORT).show()
            return
        }
        mappings.add(PortMapping(localPort = local, remotePort = remote, enabled = true))
        persistForm()
        renderMappings()
        pushForwardsIfRunning()
    }

    private fun readForm(): TunnelConfig? {
        val host = binding.hostInput.text.toString().trim()
        val username = binding.userInput.text.toString().trim()
        val sshPort = binding.portInput.text.toString().toIntOrNull() ?: 22
        if (host.isEmpty() || username.isEmpty()) {
            Toast.makeText(this, R.string.err_required, Toast.LENGTH_SHORT).show()
            return null
        }
        if (mappings.none { it.enabled }) {
            Toast.makeText(this, R.string.err_need_mapping, Toast.LENGTH_SHORT).show()
            return null
        }
        val useKey = binding.authKey.isChecked
        val password = binding.passwordInput.text.toString().ifEmpty { null }
        val privateKey = binding.keyInput.text.toString().ifEmpty { null }
        if (useKey && privateKey.isNullOrBlank()) {
            Toast.makeText(this, R.string.err_need_key, Toast.LENGTH_SHORT).show()
            return null
        }
        if (!useKey && password.isNullOrEmpty()) {
            Toast.makeText(this, R.string.err_need_password, Toast.LENGTH_SHORT).show()
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
            trustOnFirstUse = binding.tofuCheck.isChecked,
            ignoreHostKeyMismatch = binding.skipMismatchCheck.isChecked,
        )
    }

    private fun persistForm() {
        prefs().edit {
            putString(KEY_HOST, binding.hostInput.text.toString())
            putString(KEY_PORT, binding.portInput.text.toString())
            putString(KEY_USER, binding.userInput.text.toString())
            putBoolean(KEY_USE_KEY, binding.authKey.isChecked)
            putString(KEY_MAPPINGS, encodeMappings(mappings))
            putBoolean(KEY_TOFU, binding.tofuCheck.isChecked)
            putBoolean(KEY_SKIP, binding.skipMismatchCheck.isChecked)
        }
    }

    private fun restoreForm() {
        val p = prefs()
        binding.hostInput.setText(p.getString(KEY_HOST, ""))
        binding.portInput.setText(p.getString(KEY_PORT, "22"))
        binding.userInput.setText(p.getString(KEY_USER, ""))
        binding.tofuCheck.isChecked = p.getBoolean(KEY_TOFU, true)
        binding.skipMismatchCheck.isChecked = p.getBoolean(KEY_SKIP, false)
        if (p.getBoolean(KEY_USE_KEY, false)) {
            binding.authKey.isChecked = true
        } else {
            binding.authPassword.isChecked = true
        }
        mappings.clear()
        mappings.addAll(decodeMappings(p.getString(KEY_MAPPINGS, null), p))
    }

    private fun renderMappings() {
        val host = binding.hostInput.text?.toString()?.trim().orEmpty()
        val running = isRunningState(TunnelHub.state)
        binding.mappingList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        mappings.toList().forEach { mapping ->
            val row = ItemPortMappingBinding.inflate(inflater, binding.mappingList, false)
            row.mappingText.text = mapping.display(host)
            row.enableSwitch.setOnCheckedChangeListener(null)
            row.enableSwitch.isChecked = mapping.enabled
            row.enableSwitch.setOnCheckedChangeListener { _, checked ->
                val idx = mappings.indexOfFirst { it.id == mapping.id }
                if (idx >= 0) {
                    mappings[idx] = mappings[idx].copy(enabled = checked)
                    persistForm()
                    renderMappings()
                    pushForwardsIfRunning()
                }
            }
            row.openButton.isEnabled = running && mapping.enabled
            row.openButton.setOnClickListener {
                startActivity(
                    Intent(this, BrowserActivity::class.java)
                        .putExtra(BrowserActivity.EXTRA_PORT, mapping.localPort),
                )
            }
            row.deleteButton.setOnClickListener {
                mappings.removeAll { it.id == mapping.id }
                persistForm()
                renderMappings()
                pushForwardsIfRunning()
            }
            binding.mappingList.addView(row.root)
        }
    }

    private fun pushForwardsIfRunning() {
        if (!isRunningState(TunnelHub.state)) {
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
        binding.passwordLayout.visibility = if (key) View.GONE else View.VISIBLE
        binding.keyLayout.visibility = if (key) View.VISIBLE else View.GONE
        binding.passphraseLayout.visibility = if (key) View.VISIBLE else View.GONE
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
        binding.tofuCheck.isEnabled = enabled
        binding.skipMismatchCheck.isEnabled = enabled
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

        private fun isRunningState(state: String) =
            state == "connecting" || state == "authenticating" || state == "listening"

        fun encodeMappings(items: List<PortMapping>): String =
            items.joinToString(";") { "${it.localPort}:${it.remotePort}:${if (it.enabled) 1 else 0}:${it.id}" }

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
                        PortMapping(id = id, localPort = local, remotePort = remote, enabled = enabled)
                    }
                }
            }
            val local = prefs.getString(KEY_LOCAL, "8080")?.toIntOrNull() ?: 8080
            val remote = prefs.getString(KEY_REMOTE_PORT, "80")?.toIntOrNull() ?: 80
            return listOf(PortMapping(localPort = local, remotePort = remote, enabled = true))
        }
    }
}
