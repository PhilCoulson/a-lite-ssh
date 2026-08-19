package com.alite.ssh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.alite.ssh.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TunnelHub.Observer {
    private lateinit var binding: ActivityMainBinding
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* tunnel can still run without a notification on older devices */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        restoreForm()
        updateAuthFields()

        binding.authGroup.setOnCheckedChangeListener { _, _ -> updateAuthFields() }
        binding.connectButton.setOnClickListener { toggleTunnel() }
        binding.openWebButton.setOnClickListener { openWeb() }

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

    override fun onTunnelEvent(snapshot: TunnelHub.Snapshot) {
        runOnUiThread {
            val running = snapshot.state == "connecting" ||
                snapshot.state == "authenticating" ||
                snapshot.state == "listening"
            binding.connectButton.text = getString(
                if (running) R.string.action_disconnect else R.string.action_connect,
            )
            binding.statusText.text = getString(R.string.status_fmt, snapshot.state)
            binding.openWebButton.isEnabled = snapshot.state == "listening"
            binding.logView.text = snapshot.logs.joinToString("\n")
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
            setFormEnabled(!running)
        }
    }

    private fun toggleTunnel() {
        val running = TunnelHub.state == "connecting" ||
            TunnelHub.state == "authenticating" ||
            TunnelHub.state == "listening"
        if (running) {
            val intent = Intent(this, SshTunnelService::class.java).setAction(SshTunnelService.ACTION_STOP)
            startService(intent)
            return
        }
        val config = readForm() ?: return
        persistForm()
        TunnelHub.config = config
        TunnelHub.resetLogs()
        TunnelHub.appendLog("${timeFmt.format(Date())} 开始连接")
        TunnelHub.setState("connecting")
        val intent = Intent(this, SshTunnelService::class.java).setAction(SshTunnelService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun openWeb() {
        val port = binding.localPortInput.text.toString().toIntOrNull() ?: return
        startActivity(
            Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_PORT, port),
        )
    }

    private fun readForm(): TunnelConfig? {
        val host = binding.hostInput.text.toString().trim()
        val username = binding.userInput.text.toString().trim()
        val remoteHost = binding.remoteHostInput.text.toString().trim().ifEmpty { "127.0.0.1" }
        val sshPort = binding.portInput.text.toString().toIntOrNull() ?: 22
        val localPort = binding.localPortInput.text.toString().toIntOrNull() ?: 8080
        val remotePort = binding.remotePortInput.text.toString().toIntOrNull() ?: 80
        if (host.isEmpty() || username.isEmpty()) {
            Toast.makeText(this, R.string.err_required, Toast.LENGTH_SHORT).show()
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
            localPort = localPort,
            remoteHost = remoteHost,
            remotePort = remotePort,
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
            putString(KEY_LOCAL, binding.localPortInput.text.toString())
            putString(KEY_REMOTE_HOST, binding.remoteHostInput.text.toString())
            putString(KEY_REMOTE_PORT, binding.remotePortInput.text.toString())
            putBoolean(KEY_TOFU, binding.tofuCheck.isChecked)
            putBoolean(KEY_SKIP, binding.skipMismatchCheck.isChecked)
        }
    }

    private fun restoreForm() {
        val p = prefs()
        binding.hostInput.setText(p.getString(KEY_HOST, ""))
        binding.portInput.setText(p.getString(KEY_PORT, "22"))
        binding.userInput.setText(p.getString(KEY_USER, ""))
        binding.localPortInput.setText(p.getString(KEY_LOCAL, "8080"))
        binding.remoteHostInput.setText(p.getString(KEY_REMOTE_HOST, "127.0.0.1"))
        binding.remotePortInput.setText(p.getString(KEY_REMOTE_PORT, "80"))
        binding.tofuCheck.isChecked = p.getBoolean(KEY_TOFU, true)
        binding.skipMismatchCheck.isChecked = p.getBoolean(KEY_SKIP, false)
        if (p.getBoolean(KEY_USE_KEY, false)) {
            binding.authKey.isChecked = true
        } else {
            binding.authPassword.isChecked = true
        }
    }

    private fun updateAuthFields() {
        val key = binding.authKey.isChecked
        binding.passwordLayout.visibility = if (key) View.GONE else View.VISIBLE
        binding.keyLayout.visibility = if (key) View.VISIBLE else View.GONE
        binding.passphraseLayout.visibility = if (key) View.VISIBLE else View.GONE
    }

    private fun setFormEnabled(enabled: Boolean) {
        binding.hostInput.isEnabled = enabled
        binding.portInput.isEnabled = enabled
        binding.userInput.isEnabled = enabled
        binding.passwordInput.isEnabled = enabled
        binding.keyInput.isEnabled = enabled
        binding.passphraseInput.isEnabled = enabled
        binding.localPortInput.isEnabled = enabled
        binding.remoteHostInput.isEnabled = enabled
        binding.remotePortInput.isEnabled = enabled
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
        private const val KEY_LOCAL = "local_port"
        private const val KEY_REMOTE_HOST = "remote_host"
        private const val KEY_REMOTE_PORT = "remote_port"
        private const val KEY_TOFU = "tofu"
        private const val KEY_SKIP = "skip"
    }
}
