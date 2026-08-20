package com.alite.ssh

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String,
)

class UpdateCancelled : RuntimeException()

sealed class UpdateCheck {
    data class Available(val update: AppUpdate) : UpdateCheck()
    data object UpToDate : UpdateCheck()
    data object NoRelease : UpdateCheck()
    data class Failed(val reason: String) : UpdateCheck()
}

object AppUpdateClient {
    fun fetchLatest(): UpdateCheck {
        return try {
        val raw = httpGet(BuildConfig.UPDATE_API_URL, acceptJson = true)
        val root = JSONObject(raw)
        val assets = root.optJSONArray("assets")
            ?: return UpdateCheck.NoRelease
        var apkUrl = ""
        var apkSize = 0L
        var versionJsonUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                apkUrl = url
                apkSize = asset.optLong("size")
            } else if (name == "version.json") {
                versionJsonUrl = url
            }
        }
        if (apkUrl.isBlank() || !isAllowedUrl(apkUrl)) {
            return UpdateCheck.NoRelease
        }
        val fromTitle = parseVersion(root.optString("name"), root.optString("tag_name"))
        val fromJson = versionJsonUrl
            ?.takeIf { isAllowedUrl(it) }
            ?.let { httpGet(it, acceptJson = true) }
            ?.let { JSONObject(it) }
        val versionCode = fromJson?.optInt("versionCode")?.takeIf { it > 0 }
            ?: fromTitle?.first
            ?: return UpdateCheck.Failed("发布包缺少版本号")
        val versionName = fromJson?.optString("versionName")?.takeIf { it.isNotBlank() }
            ?: fromTitle?.second
            ?: root.optString("tag_name").removePrefix("v")
        val update = AppUpdate(
            versionCode = versionCode,
            versionName = versionName,
            notes = root.optString("body").trim(),
            apkUrl = apkUrl,
            apkSize = apkSize,
            pageUrl = root.optString("html_url"),
        )
        if (update.versionCode > BuildConfig.VERSION_CODE) {
            UpdateCheck.Available(update)
        } else {
            UpdateCheck.UpToDate
        }
    } catch (e: Exception) {
        UpdateCheck.Failed(e.message ?: e.javaClass.simpleName)
    }
    }

    fun download(update: AppUpdate, dest: File, cancelled: () -> Boolean, onProgress: (Int) -> Unit) {
        if (!isAllowedUrl(update.apkUrl)) {
            throw IllegalStateException("更新地址不受信任")
        }
        dest.parentFile?.mkdirs()
        if (dest.exists()) {
            dest.delete()
        }
        val tmp = File(dest.parentFile, dest.name + ".part")
        if (tmp.exists()) {
            tmp.delete()
        }
        val conn = open(update.apkUrl, acceptJson = false, readTimeoutMs = 300_000)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("下载失败 HTTP $code")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.apkSize
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(16 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        if (cancelled()) {
                            throw UpdateCancelled()
                        }
                        val n = input.read(buf)
                        if (n < 0) {
                            break
                        }
                        output.write(buf, 0, n)
                        read += n
                        val pct = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                    output.flush()
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            onProgress(100)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseVersion(name: String, tag: String): Pair<Int, String>? {
        val named = Regex("""^\s*(.+?)\s*\((\d+)\)\s*$""").matchEntire(name)
        if (named != null) {
            return named.groupValues[2].toInt() to named.groupValues[1].trim().removePrefix("v")
        }
        val tagged = Regex("""^v?(\d+(?:\.\d+)*)-(\d+)$""").matchEntire(tag.trim())
        if (tagged != null) {
            return tagged.groupValues[2].toInt() to tagged.groupValues[1]
        }
        return null
    }

    private fun httpGet(url: String, acceptJson: Boolean): String {
        val conn = open(url, acceptJson = acceptJson, readTimeoutMs = 20_000)
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw IllegalStateException(PRIVATE_OR_MISSING)
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, acceptJson: Boolean, readTimeoutMs: Int = 60_000): HttpURLConnection {
        var current = url
        repeat(8) {
            if (!isAllowedUrl(current)) {
                throw IllegalStateException("更新地址不受信任")
            }
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", "A-Lite-SSH/${BuildConfig.VERSION_NAME}")
            if (acceptJson) {
                conn.setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = conn.responseCode
            if (code in REDIRECTS) {
                val next = conn.getHeaderField("Location") ?: throw IllegalStateException("重定向缺少地址")
                conn.disconnect()
                current = resolveRedirect(current, next)
            } else {
                return conn
            }
        }
        throw IllegalStateException("重定向过多")
    }

    private fun resolveRedirect(current: String, location: String): String =
        if (location.startsWith("http://") || location.startsWith("https://")) {
            location
        } else {
            URL(URL(current), location).toString()
        }

    private fun isAllowedUrl(url: String): Boolean = try {
        val parsed = URL(url)
        parsed.protocol == "https" && parsed.host.lowercase() in ALLOWED_HOSTS
    } catch (_: Exception) {
        false
    }

    private val REDIRECTS = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        307,
        308,
    )

    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )

    const val PRIVATE_OR_MISSING = "REPO_PRIVATE"
}
