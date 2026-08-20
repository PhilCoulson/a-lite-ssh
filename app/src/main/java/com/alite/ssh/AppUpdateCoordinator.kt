package com.alite.ssh

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AppUpdateCoordinator(private val activity: AppCompatActivity) {
    private val prefs = activity.getSharedPreferences(PREFS, AppCompatActivity.MODE_PRIVATE)
    private var pending: AppUpdate? = null
    private var checking = false

    fun checkIfDue() {
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) {
            return
        }
        check(silent = true)
    }

    fun checkNow() {
        check(silent = false)
    }

    fun onResume() {
        val update = pending ?: return
        if (activity.packageManager.canRequestPackageInstalls()) {
            pending = null
            TunnelHub.appendUpdateLog("已获得安装未知应用权限，继续更新")
            prepareDownload(update)
        }
    }

    private fun check(silent: Boolean) {
        if (checking) {
            if (!silent) {
                snack(activity.getString(R.string.update_checking))
            }
            return
        }
        checking = true
        TunnelHub.appendUpdateLog(if (silent) "自动检查更新" else "手动检查更新")
        if (!silent) {
            snack(activity.getString(R.string.update_checking))
        }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AppUpdateClient.fetchLatest() }
            checking = false
            prefs.edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
            when (result) {
                is UpdateCheck.Available -> {
                    TunnelHub.appendUpdateLog(
                        "发现新版本 ${result.update.versionName}（${result.update.versionCode}），当前 ${BuildConfig.VERSION_NAME}",
                    )
                    val snoozed = prefs.getInt(KEY_SNOOZED, 0)
                    if (silent && snoozed == result.update.versionCode) {
                        TunnelHub.appendUpdateLog("该版本已选择稍后")
                        return@launch
                    }
                    showAvailable(result.update)
                }
                UpdateCheck.UpToDate -> {
                    TunnelHub.appendUpdateLog("已是最新版本 ${BuildConfig.VERSION_NAME}")
                    if (!silent) {
                        snack(activity.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME))
                    }
                }
                UpdateCheck.NoRelease -> {
                    TunnelHub.appendUpdateLog("GitHub 上没有带 APK 的 Release")
                    if (!silent) snack(activity.getString(R.string.update_no_release))
                }
                is UpdateCheck.Failed -> {
                    TunnelHub.appendUpdateLog("检查失败：${result.reason}")
                    if (!silent) {
                        val text = if (result.reason == AppUpdateClient.PRIVATE_OR_MISSING) {
                            activity.getString(R.string.update_private_repo)
                        } else {
                            activity.getString(R.string.update_check_failed, result.reason)
                        }
                        snack(text)
                    }
                }
            }
        }
    }

    private fun showAvailable(update: AppUpdate) {
        val notes = update.notes.ifBlank { activity.getString(R.string.update_no_notes) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_available_title, update.versionName))
            .setMessage(
                activity.getString(
                    R.string.update_available_body,
                    update.versionName,
                    BuildConfig.VERSION_NAME,
                    notes,
                ),
            )
            .setNegativeButton(R.string.update_later) { _, _ ->
                prefs.edit { putInt(KEY_SNOOZED, update.versionCode) }
                TunnelHub.appendUpdateLog("用户选择稍后安装 ${update.versionName}")
            }
            .setPositiveButton(R.string.update_download) { _, _ ->
                prefs.edit { putInt(KEY_SNOOZED, 0) }
                TunnelHub.appendUpdateLog("用户确认下载 ${update.versionName}")
                ensureInstallPermission(update)
            }
            .show()
    }

    private fun ensureInstallPermission(update: AppUpdate) {
        if (activity.packageManager.canRequestPackageInstalls()) {
            prepareDownload(update)
            return
        }
        pending = update
        TunnelHub.appendUpdateLog("需要「安装未知应用」权限")
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_body)
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                pending = null
                TunnelHub.appendUpdateLog("用户取消授权安装未知应用")
            }
            .setPositiveButton(R.string.update_permission_go) { _, _ ->
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}"),
                    ),
                )
            }
            .show()
    }

    private fun prepareDownload(update: AppUpdate) {
        val dest = apkFile()
        if (dest.isFile && AppUpdateInstaller.verify(activity, dest) == null) {
            TunnelHub.appendUpdateLog("已有完整安装包，跳过下载")
            startInstall(dest)
            return
        }
        if (AppUpdateClient.canResume(update, dest)) {
            TunnelHub.appendUpdateLog("发现未完成下载，询问是否续传")
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_resume_title)
                .setMessage(R.string.update_resume_body)
                .setNeutralButton(R.string.action_cancel) { _, _ ->
                    TunnelHub.appendUpdateLog("用户取消续传")
                }
                .setNegativeButton(R.string.update_redownload) { _, _ ->
                    TunnelHub.appendUpdateLog("用户选择重新下载")
                    AppUpdateClient.clearPartial(dest)
                    downloadAndInstall(update, resume = false)
                }
                .setPositiveButton(R.string.update_resume) { _, _ ->
                    TunnelHub.appendUpdateLog("用户选择继续下载")
                    downloadAndInstall(update, resume = true)
                }
                .show()
            return
        }
        downloadAndInstall(update, resume = false)
    }

    private fun downloadAndInstall(update: AppUpdate, resume: Boolean) {
        val cancelled = AtomicBoolean(false)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null, false)
        val status = view.findViewById<TextView>(R.id.updateProgressStatus)
        val bar = view.findViewById<ProgressBar>(R.id.updateProgressBar)
        status.setText(R.string.update_downloading)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_downloading_title)
            .setView(view)
            .setNegativeButton(R.string.action_cancel) { _, _ -> cancelled.set(true) }
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                cancelled.set(true)
                status.setText(R.string.update_cancelling)
            }
        }
        dialog.show()
        activity.lifecycleScope.launch {
            val apk = apkFile()
            try {
                withContext(Dispatchers.IO) {
                    AppUpdateClient.download(update, apk, resume, cancelled::get) { pct ->
                        activity.runOnUiThread {
                            bar.isIndeterminate = false
                            bar.progress = pct
                            status.text = activity.getString(R.string.update_downloading_pct, pct)
                        }
                    }
                }
                if (cancelled.get()) {
                    TunnelHub.appendUpdateLog("下载已暂停，下次可继续")
                    snack(activity.getString(R.string.update_cancelling))
                    return@launch
                }
                status.setText(R.string.update_verifying)
                TunnelHub.appendUpdateLog("正在校验安装包")
                val error = withContext(Dispatchers.IO) { AppUpdateInstaller.verify(activity, apk) }
                if (error != null) {
                    TunnelHub.appendUpdateLog("校验失败：$error")
                    snack(error)
                    return@launch
                }
                TunnelHub.appendUpdateLog("校验通过")
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
                startInstall(apk)
                return@launch
            } catch (_: UpdateCancelled) {
                TunnelHub.appendUpdateLog("下载已暂停，进度已保留")
            } catch (e: Exception) {
                TunnelHub.appendUpdateLog("下载失败：${e.message ?: e.javaClass.simpleName}")
                if (!cancelled.get()) {
                    snack(activity.getString(R.string.update_download_failed, e.message ?: e.javaClass.simpleName))
                }
            } finally {
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
            }
        }
    }

    private fun startInstall(apk: File) {
        snack(activity.getString(R.string.update_install_confirm))
        try {
            AppUpdateInstaller.install(activity, apk)
        } catch (e: Exception) {
            TunnelHub.appendUpdateLog("无法打开安装界面：${e.message ?: e.javaClass.simpleName}")
            snack(activity.getString(R.string.update_download_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun apkFile(): File = File(activity.cacheDir, "updates/a-lite-ssh-update.apk")

    private fun snack(message: String) {
        val anchor = activity.findViewById<android.view.View>(R.id.coordinator)
            ?: activity.findViewById(android.R.id.content)
        showCenteredSnackbar(anchor, message)
    }

    companion object {
        private const val PREFS = "update"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_SNOOZED = "snoozed_version"
        private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    }
}
