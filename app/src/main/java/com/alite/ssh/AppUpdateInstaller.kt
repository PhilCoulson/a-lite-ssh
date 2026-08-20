package com.alite.ssh

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

object AppUpdateInstaller {
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
        TunnelHub.appendUpdateLog("调起系统安装界面 ${apk.name}（${apk.length() / 1024} KB）")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
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
}
