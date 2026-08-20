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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AppUpdateCoordinator(private val activity: AppCompatActivity) {
    private val prefs = activity.getSharedPreferences(PREFS, AppCompatActivity.MODE_PRIVATE)
    private var pending: AppUpdate? = null
    private var checking = false
    private var interactiveCheck = false
    private var checkCancelled = false
    private var downloadJob: Job? = null
    private var checkDialog: AlertDialog? = null
    private var downloadDialog: AlertDialog? = null

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

    fun onHostFocused() {
        val update = pending ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            return
        }
        if (downloadJob?.isActive == true) {
            return
        }
        pending = null
        TunnelHub.appendUpdateLog("已获得安装未知应用权限，继续更新")
        activity.window.decorView.post {
            if (!alive()) {
                pending = update
                return@post
            }
            prepareDownload(update)
        }
    }

    private fun check(silent: Boolean) {
        if (checking) {
            if (!silent) {
                interactiveCheck = true
                checkCancelled = false
                showCheckingDialog()
            }
            return
        }
        checking = true
        interactiveCheck = !silent
        checkCancelled = false
        if (!silent) {
            showCheckingDialog()
        }
        TunnelHub.appendUpdateLog(if (interactiveCheck) "手动检查更新" else "自动检查更新")
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AppUpdateClient.fetchLatest() }
            checking = false
            prefs.edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
            val showUi = interactiveCheck && !checkCancelled
            interactiveCheck = false
            dismissCheckingDialog()
            if (checkCancelled) {
                logCheckResult(result)
                return@launch
            }
            if (!showUi) {
                handleSilentResult(result)
                return@launch
            }
            presentCheckResult(result)
        }
    }

    private fun logCheckResult(result: UpdateCheck) {
        when (result) {
            is UpdateCheck.Available -> TunnelHub.appendUpdateLog(
                "发现新版本 ${result.update.versionName}（${result.update.versionCode}），当前 ${BuildConfig.VERSION_NAME}",
            )
            UpdateCheck.UpToDate -> TunnelHub.appendUpdateLog("已是最新版本 ${BuildConfig.VERSION_NAME}")
            UpdateCheck.NoRelease -> TunnelHub.appendUpdateLog("GitHub 上没有带 APK 的 Release")
            is UpdateCheck.Failed -> TunnelHub.appendUpdateLog("检查失败：${result.reason}")
        }
    }

    private fun handleSilentResult(result: UpdateCheck) {
        when (result) {
            is UpdateCheck.Available -> {
                TunnelHub.appendUpdateLog(
                    "发现新版本 ${result.update.versionName}（${result.update.versionCode}），当前 ${BuildConfig.VERSION_NAME}",
                )
                val snoozed = prefs.getInt(KEY_SNOOZED, 0)
                if (snoozed == result.update.versionCode) {
                    TunnelHub.appendUpdateLog("该版本已选择稍后")
                    return
                }
                showAvailable(result.update)
            }
            UpdateCheck.UpToDate -> {
                TunnelHub.appendUpdateLog("已是最新版本 ${BuildConfig.VERSION_NAME}")
            }
            UpdateCheck.NoRelease -> {
                TunnelHub.appendUpdateLog("GitHub 上没有带 APK 的 Release")
            }
            is UpdateCheck.Failed -> {
                TunnelHub.appendUpdateLog("检查失败：${result.reason}")
            }
        }
    }

    private fun presentCheckResult(result: UpdateCheck) {
        if (!alive()) {
            return
        }
        when (result) {
            is UpdateCheck.Available -> {
                logCheckResult(result)
                showAvailable(result.update)
            }
            UpdateCheck.UpToDate -> {
                logCheckResult(result)
                showPlainMessage(activity.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME))
            }
            UpdateCheck.NoRelease -> {
                logCheckResult(result)
                showPlainMessage(activity.getString(R.string.update_no_release))
            }
            is UpdateCheck.Failed -> {
                logCheckResult(result)
                val text = if (result.reason == AppUpdateClient.PRIVATE_OR_MISSING) {
                    activity.getString(R.string.update_private_repo)
                } else {
                    activity.getString(R.string.update_check_failed, result.reason)
                }
                showPlainMessage(text)
            }
        }
    }

    private fun showAvailable(update: AppUpdate) {
        if (!alive()) {
            return
        }
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
        if (downloadJob?.isActive == true) {
            return
        }
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
        if (downloadJob?.isActive == true) {
            return
        }
        val cancelled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val connection = AtomicReference<HttpURLConnection?>(null)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null, false)
        val status = view.findViewById<TextView>(R.id.updateProgressStatus)
        val bar = view.findViewById<ProgressBar>(R.id.updateProgressBar)
        status.setText(R.string.update_downloading)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_downloading_title)
            .setView(view)
            .setNegativeButton(R.string.action_cancel, null)
            .setCancelable(true)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                abortDownload(cancelled, connection)
                dismissDownloadDialog()
            }
        }
        dialog.setOnCancelListener {
            abortDownload(cancelled, connection)
        }
        dialog.setOnDismissListener {
            downloadDialog = null
            if (!finished.get()) {
                abortDownload(cancelled, connection)
            }
        }
        downloadDialog = dialog
        dialog.show()
        downloadJob = activity.lifecycleScope.launch {
            val apk = apkFile()
            try {
                withContext(Dispatchers.IO) {
                    AppUpdateClient.download(
                        update = update,
                        dest = apk,
                        resume = resume,
                        cancelled = cancelled::get,
                        onConnection = { connection.set(it) },
                    ) { pct ->
                        activity.runOnUiThread {
                            if (downloadDialog !== dialog || !dialog.isShowing) {
                                return@runOnUiThread
                            }
                            bar.isIndeterminate = false
                            bar.progress = pct
                            status.text = activity.getString(R.string.update_downloading_pct, pct)
                        }
                    }
                }
                if (cancelled.get()) {
                    TunnelHub.appendUpdateLog("下载已暂停，下次可继续")
                    return@launch
                }
                if (dialog.isShowing) {
                    status.setText(R.string.update_verifying)
                }
                TunnelHub.appendUpdateLog("正在校验安装包")
                val error = withContext(Dispatchers.IO) { AppUpdateInstaller.verify(activity, apk) }
                if (cancelled.get()) {
                    TunnelHub.appendUpdateLog("下载已暂停，下次可继续")
                    return@launch
                }
                if (error != null) {
                    TunnelHub.appendUpdateLog("校验失败：$error")
                    finished.set(true)
                    dismissDownloadDialog()
                    snack(error)
                    return@launch
                }
                TunnelHub.appendUpdateLog("校验通过")
                finished.set(true)
                dismissDownloadDialog()
                startInstall(apk)
            } catch (_: UpdateCancelled) {
                TunnelHub.appendUpdateLog("下载已暂停，进度已保留")
            } catch (e: Exception) {
                if (cancelled.get()) {
                    TunnelHub.appendUpdateLog("下载已暂停，进度已保留")
                    return@launch
                }
                TunnelHub.appendUpdateLog("下载失败：${e.message ?: e.javaClass.simpleName}")
                finished.set(true)
                dismissDownloadDialog()
                snack(activity.getString(R.string.update_download_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                finished.set(true)
                connection.set(null)
                dismissDownloadDialog()
            }
        }
    }

    private fun abortDownload(
        cancelled: AtomicBoolean,
        connection: AtomicReference<HttpURLConnection?>,
    ) {
        if (cancelled.getAndSet(true)) {
            return
        }
        TunnelHub.appendUpdateLog("用户取消下载")
        try {
            connection.get()?.disconnect()
        } catch (_: Exception) {
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

    private fun showCheckingDialog() {
        if (checkDialog?.isShowing == true) {
            return
        }
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_status, null, false)
        view.findViewById<TextView>(R.id.updateStatusText).setText(R.string.update_checking)
        checkDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_check_title)
            .setView(view)
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                checkCancelled = true
                interactiveCheck = false
                TunnelHub.appendUpdateLog("用户取消检查更新")
            }
            .setCancelable(true)
            .setOnCancelListener {
                checkCancelled = true
                interactiveCheck = false
                TunnelHub.appendUpdateLog("用户取消检查更新")
            }
            .show()
    }

    private fun showPlainMessage(message: String) {
        if (!alive()) {
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_check_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun dismissCheckingDialog() {
        checkDialog?.let { dialog ->
            dialog.setOnDismissListener(null)
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }
        checkDialog = null
    }

    private fun dismissDownloadDialog() {
        downloadDialog?.let { dialog ->
            dialog.setOnDismissListener(null)
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }
        downloadDialog = null
    }

    private fun apkFile(): File = File(activity.cacheDir, "updates/a-lite-ssh-update.apk")

    private fun alive(): Boolean = !activity.isFinishing && !activity.isDestroyed

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
