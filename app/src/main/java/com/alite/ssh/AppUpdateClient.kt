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

    fun partialFile(dest: File): File = File(dest.parentFile, dest.name + ".part")

    fun canResume(update: AppUpdate, dest: File): Boolean {
        val part = partialFile(dest)
        val meta = readMeta(dest) ?: return false
        if (!part.isFile || part.length() <= 0L) {
            return false
        }
        if (meta.versionCode != update.versionCode || meta.url != update.apkUrl) {
            return false
        }
        val expected = update.apkSize.takeIf { it > 0 } ?: meta.size
        return expected <= 0L || part.length() < expected
    }

    fun clearPartial(dest: File) {
        partialFile(dest).delete()
        metaFile(dest).delete()
        if (dest.exists()) {
            dest.delete()
        }
    }

    fun download(
        update: AppUpdate,
        dest: File,
        resume: Boolean,
        cancelled: () -> Boolean,
        onConnection: (HttpURLConnection) -> Unit = {},
        onProgress: (Int) -> Unit,
    ) {
        if (!isAllowedUrl(update.apkUrl)) {
            throw IllegalStateException("更新地址不受信任")
        }
        dest.parentFile?.mkdirs()
        val tmp = partialFile(dest)
        if (!resume) {
            clearPartial(dest)
        }
        var offset = if (resume && tmp.isFile) tmp.length() else 0L
        if (offset > 0L && update.apkSize > 0L && offset >= update.apkSize) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
            metaFile(dest).delete()
            onProgress(100)
            return
        }
        val headers = linkedMapOf<String, String>()
        if (offset > 0L) {
            headers["Range"] = "bytes=$offset-"
            TunnelHub.appendUpdateLog("断点续传，已有 ${offset / 1024} KB")
        } else {
            TunnelHub.appendUpdateLog("开始下载 ${update.versionName}")
        }
        val conn = open(
            update.apkUrl,
            acceptJson = false,
            readTimeoutMs = 300_000,
            extraHeaders = headers,
            cancelled = cancelled,
            onConnection = onConnection,
        )
        try {
            if (cancelled()) {
                throw UpdateCancelled()
            }
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                if (cancelled()) throw UpdateCancelled()
                throw e
            }
            if (cancelled()) {
                throw UpdateCancelled()
            }
            if (offset > 0L && code == HttpURLConnection.HTTP_OK) {
                TunnelHub.appendUpdateLog("服务器不支持续传，改为重新下载")
                conn.disconnect()
                clearPartial(dest)
                offset = 0L
                download(
                    update,
                    dest,
                    resume = false,
                    cancelled = cancelled,
                    onConnection = onConnection,
                    onProgress = onProgress,
                )
                return
            }
            if (offset > 0L && code != 206) {
                throw IllegalStateException("续传失败 HTTP $code")
            }
            if (offset == 0L && code !in 200..299) {
                throw IllegalStateException("下载失败 HTTP $code")
            }
            val total = parseTotalBytes(conn, update.apkSize, offset)
            writeMeta(dest, update, total)
            val append = offset > 0L && code == 206
            conn.inputStream.use { input ->
                FileOutputStream(tmp, append).use { output ->
                    val buf = ByteArray(16 * 1024)
                    var read = offset
                    var lastLogged = -1
                    while (true) {
                        if (cancelled()) {
                            output.flush()
                            throw UpdateCancelled()
                        }
                        val n = try {
                            input.read(buf)
                        } catch (e: Exception) {
                            if (cancelled()) throw UpdateCancelled()
                            throw e
                        }
                        if (n < 0) {
                            break
                        }
                        output.write(buf, 0, n)
                        read += n
                        val pct = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else 0
                        onProgress(pct)
                        val bucket = pct / 25 * 25
                        if (bucket != lastLogged && bucket in setOf(25, 50, 75, 100)) {
                            lastLogged = bucket
                            TunnelHub.appendUpdateLog("下载进度 $bucket%")
                        }
                    }
                    output.flush()
                }
            }
            if (total > 0L && tmp.length() < total) {
                throw IllegalStateException("下载不完整（${tmp.length()}/$total）")
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            metaFile(dest).delete()
            onProgress(100)
            TunnelHub.appendUpdateLog("下载完成 ${dest.length() / 1024} KB")
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

    private fun open(
        url: String,
        acceptJson: Boolean,
        readTimeoutMs: Int = 60_000,
        extraHeaders: Map<String, String> = emptyMap(),
        cancelled: () -> Boolean = { false },
        onConnection: (HttpURLConnection) -> Unit = {},
    ): HttpURLConnection {
        var current = url
        repeat(8) {
            if (cancelled()) {
                throw UpdateCancelled()
            }
            if (!isAllowedUrl(current)) {
                throw IllegalStateException("更新地址不受信任")
            }
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", "A-Lite-SSH/${BuildConfig.VERSION_NAME}")
            extraHeaders.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            if (acceptJson) {
                conn.setRequestProperty("Accept", "application/vnd.github+json")
            }
            onConnection(conn)
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                if (cancelled()) throw UpdateCancelled()
                throw e
            }
            if (cancelled()) {
                conn.disconnect()
                throw UpdateCancelled()
            }
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

    private fun metaFile(dest: File): File = File(dest.parentFile, dest.name + ".meta")

    private data class PartialMeta(val versionCode: Int, val url: String, val size: Long)

    private fun writeMeta(dest: File, update: AppUpdate, size: Long) {
        metaFile(dest).writeText(
            "versionCode=${update.versionCode}\nurl=${update.apkUrl}\nsize=$size\n",
        )
    }

    private fun readMeta(dest: File): PartialMeta? {
        val file = metaFile(dest)
        if (!file.isFile) {
            return null
        }
        val values = file.readLines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()
        val code = values["versionCode"]?.toIntOrNull() ?: return null
        val url = values["url"] ?: return null
        val size = values["size"]?.toLongOrNull() ?: 0L
        return PartialMeta(code, url, size)
    }

    private fun parseTotalBytes(conn: HttpURLConnection, declared: Long, offset: Long): Long {
        val range = conn.getHeaderField("Content-Range")
        if (!range.isNullOrBlank()) {
            val total = range.substringAfterLast('/', "").toLongOrNull()
            if (total != null && total > 0L) {
                return total
            }
        }
        val remaining = conn.contentLengthLong
        return when {
            declared > 0L -> declared
            remaining > 0L && offset > 0L -> offset + remaining
            remaining > 0L -> remaining
            else -> 0L
        }
    }
}
