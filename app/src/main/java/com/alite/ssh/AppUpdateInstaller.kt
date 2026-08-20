package com.alite.ssh

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import java.io.File
import java.io.FileInputStream

object AppUpdateInstaller {
    const val ACTION_INSTALL_RESULT = "com.alite.ssh.INSTALL_RESULT"

    fun verify(context: Context, apk: File): String? {
        if (!apk.isFile || apk.length() < 1024) {
            return "下载的安装包不完整"
        }
        val flags = packageInfoFlags()
        val incoming = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return "无法读取安装包"
        incoming.applicationInfo?.apply {
            sourceDir = apk.absolutePath
            publicSourceDir = apk.absolutePath
        }
        if (incoming.packageName != context.packageName) {
            return "安装包应用名不匹配"
        }
        @Suppress("DEPRECATION")
        if (incoming.versionCode <= BuildConfig.VERSION_CODE) {
            return "安装包版本不高于当前版本"
        }
        val current = context.packageManager.getPackageInfo(context.packageName, flags)
        val currentSigs = signatures(current)
        val incomingSigs = signatures(incoming)
        if (currentSigs.isEmpty() || incomingSigs.isEmpty() || !currentSigs.any { it in incomingSigs }) {
            return "安装包签名与当前应用不一致。请先卸载后安装 GitHub Release 中的安装包，之后即可应用内更新。"
        }
        return null
    }

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            FileInputStream(apk).use { input ->
                session.openWrite("update", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutablePiFlag()
            val pi = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_RESULT),
                flags,
            )
            session.commit(pi.intentSender)
        }
    }

    private fun packageInfoFlags(): Int = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    private fun signatures(info: android.content.pm.PackageInfo): Set<String> {
        val bytes = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptySet()
            val signers = if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
            signers?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }
        return bytes.map { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }.toSet()
    }

    private fun mutablePiFlag(): Int = if (Build.VERSION.SDK_INT >= 31) {
        PendingIntent.FLAG_MUTABLE
    } else {
        0
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val extra = extraIntent(intent) ?: return
                extra.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(extra)
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, context.getString(R.string.update_installed), Toast.LENGTH_LONG).show()
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.update_install_failed)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun extraIntent(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }
}
