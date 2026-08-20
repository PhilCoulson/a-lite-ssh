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
import com.google.android.material.snackbar.Snackbar
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
            downloadAndInstall(update)
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
        if (!silent) {
            snack(activity.getString(R.string.update_checking))
        }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AppUpdateClient.fetchLatest() }
            checking = false
            prefs.edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
            when (result) {
                is UpdateCheck.Available -> {
                    val snoozed = prefs.getInt(KEY_SNOOZED, 0)
                    if (silent && snoozed == result.update.versionCode) {
                        return@launch
                    }
                    showAvailable(result.update)
                }
                UpdateCheck.UpToDate -> if (!silent) snack(activity.getString(R.string.update_up_to_date))
                UpdateCheck.NoRelease -> if (!silent) snack(activity.getString(R.string.update_no_release))
                is UpdateCheck.Failed -> if (!silent) {
                    snack(activity.getString(R.string.update_check_failed, result.reason))
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
            }
            .setPositiveButton(R.string.update_download) { _, _ ->
                prefs.edit { putInt(KEY_SNOOZED, 0) }
                ensureInstallPermission(update)
            }
            .show()
    }

    private fun ensureInstallPermission(update: AppUpdate) {
        if (activity.packageManager.canRequestPackageInstalls()) {
            downloadAndInstall(update)
            return
        }
        pending = update
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_body)
            .setNegativeButton(R.string.action_cancel) { _, _ -> pending = null }
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

    private fun downloadAndInstall(update: AppUpdate) {
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
            val apk = File(activity.cacheDir, "updates/a-lite-ssh-update.apk")
            try {
                withContext(Dispatchers.IO) {
                    AppUpdateClient.download(update, apk, cancelled::get) { pct ->
                        activity.runOnUiThread {
                            bar.isIndeterminate = false
                            bar.progress = pct
                            status.text = activity.getString(R.string.update_downloading_pct, pct)
                        }
                    }
                }
                if (cancelled.get()) {
                    apk.delete()
                    return@launch
                }
                status.setText(R.string.update_verifying)
                val error = withContext(Dispatchers.IO) { AppUpdateInstaller.verify(activity, apk) }
                if (error != null) {
                    snack(error)
                    return@launch
                }
                status.setText(R.string.update_installing)
                withContext(Dispatchers.IO) { AppUpdateInstaller.install(activity, apk) }
            } catch (_: UpdateCancelled) {
                apk.delete()
            } catch (e: Exception) {
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

    private fun snack(message: String) {
        val anchor = activity.findViewById<android.view.View>(R.id.coordinator)
            ?: activity.findViewById(android.R.id.content)
        Snackbar.make(anchor, message, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        private const val PREFS = "update"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_SNOOZED = "snoozed_version"
        private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    }
}
