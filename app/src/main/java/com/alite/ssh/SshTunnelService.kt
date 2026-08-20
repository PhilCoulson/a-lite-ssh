package com.alite.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class SshTunnelService : Service(), SshNative.Listener {
    private val native = SshNative()
    private lateinit var knownHosts: KnownHostsStore

    override fun onCreate() {
        super.onCreate()
        knownHosts = KnownHostsStore(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                native.nativeStop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_FORWARDS -> {
                applyForwards()
            }
            ACTION_START -> {
                val config = TunnelHub.config
                if (config == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInForeground(config)
                if (!native.nativeIsRunning()) {
                    val enabled = config.enabledMappings()
                    val rc = native.nativeStart(
                        host = config.host,
                        port = config.port,
                        username = config.username,
                        password = config.password,
                        privateKey = config.privateKey,
                        passphrase = config.passphrase,
                        localPorts = enabled.map { it.localPort }.toIntArray(),
                        remotePorts = enabled.map { it.remotePort }.toIntArray(),
                        remoteHosts = enabled.map { it.effectiveRemoteHost() }.toTypedArray(),
                        listener = this,
                    )
                    if (rc != 0) {
                        TunnelHub.appendLog("启动失败，错误码 $rc")
                        TunnelHub.setState("error")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        native.nativeStop()
        super.onDestroy()
    }

    override fun onState(state: String) {
        TunnelHub.setState(state)
        if (state == "error" || state == "stopped") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onLog(message: String) {
        TunnelHub.appendLog(message)
    }

    override fun onHostKey(fingerprint: String, keyType: String): Boolean {
        val config = TunnelHub.config ?: return false
        val decision = knownHosts.decide(
            host = config.host,
            port = config.port,
            fingerprint = fingerprint,
            trustOnFirstUse = config.trustOnFirstUse,
            ignoreMismatch = config.ignoreHostKeyMismatch,
        )
        TunnelHub.appendLog(
            when (decision) {
                HostKeyDecision.Matched -> "主机密钥已匹配"
                HostKeyDecision.AcceptedFirstSeen -> "首次信任并保存主机密钥"
                HostKeyDecision.AcceptedMismatch -> "主机密钥不匹配，已按设置忽略"
                HostKeyDecision.RejectedUnknown -> "未知主机密钥，已拒绝（请开启首次信任）"
                HostKeyDecision.RejectedMismatch -> "主机密钥不匹配，已拒绝"
            },
        )
        return decision == HostKeyDecision.Matched ||
            decision == HostKeyDecision.AcceptedFirstSeen ||
            decision == HostKeyDecision.AcceptedMismatch
    }

    private fun startInForeground(config: TunnelConfig) {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SshTunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val first = config.enabledMappings().firstOrNull()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                if (first == null) {
                    config.host
                } else {
                    getString(R.string.notification_text, config.host, first.localPort)
                },
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun applyForwards() {
        if (!native.nativeIsRunning()) {
            return
        }
        val enabled = TunnelHub.config?.enabledMappings().orEmpty()
        native.nativeReplaceForwards(
            enabled.map { it.localPort }.toIntArray(),
            enabled.map { it.remotePort }.toIntArray(),
            enabled.map { it.effectiveRemoteHost() }.toTypedArray(),
        )
        TunnelHub.config?.let { startInForeground(it) }
    }

    companion object {
        const val ACTION_START = "com.alite.ssh.START"
        const val ACTION_STOP = "com.alite.ssh.STOP"
        const val ACTION_UPDATE_FORWARDS = "com.alite.ssh.UPDATE_FORWARDS"
        private const val CHANNEL_ID = "ssh_tunnel"
        private const val NOTIFICATION_ID = 42
    }
}
