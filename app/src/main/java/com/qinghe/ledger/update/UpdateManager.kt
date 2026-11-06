package com.qinghe.ledger.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.qinghe.ledger.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class UpdateInfo(
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
    val assetName: String,
    val sizeBytes: Long,
    val apiDownloadUrl: String? = null,
    val fallbackDownloadUrls: List<String> = emptyList(),
    val sourceLabel: String = ""
)

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failure(val message: String) : UpdateCheckResult
}

sealed interface UpdateInstallResult {
    data object Started : UpdateInstallResult
    data class NeedsPermission(val message: String) : UpdateInstallResult
    data class Failure(val message: String) : UpdateInstallResult
}

object UpdateManager {
    /**
     * Fallback download proxies. They are only used when the official GitHub
     * routes fail or time out. Every downloaded APK is still validated against
     * the installed app's package name and signing certificate before install,
     * so a mirror can slow things down but cannot inject a different APK.
     */
    private val BUILT_IN_DOWNLOAD_MIRRORS = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/"
    )

    // ghfast/ghproxy.net do not proxy api.github.com; gh-proxy does.
    private val BUILT_IN_API_MIRRORS = listOf("https://gh-proxy.com/")

    private const val MIRROR_PLACEHOLDER = "{url}"
    private const val CHECK_ATTEMPTS = 1
    private const val MAX_APK_BYTES = 100L * 1024L * 1024L

    internal data class DownloadSource(
        val url: String,
        val apiAsset: Boolean,
        val attempts: Int = 2
    )

    /**
     * Checks Gitee first (stable in mainland China) and falls back to GitHub.
     * A blank [giteeRepo] disables Gitee and uses GitHub only.
     */
    suspend fun check(
        giteeRepo: String,
        githubRepo: String,
        mirror: String = ""
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val github = normalizeRepo(githubRepo)
        val gitee = normalizeRepo(giteeRepo)
        var giteeUpToDate = false
        var giteeProblem: String? = null

        if (gitee != null) {
            try {
                when (val result = checkGitee(gitee, github)) {
                    is UpdateCheckResult.Available -> return@withContext result
                    UpdateCheckResult.UpToDate -> giteeUpToDate = true
                    is UpdateCheckResult.Failure -> giteeProblem = result.message
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                giteeProblem = error.message ?: "网络错误"
            }
        }

        if (github != null) {
            when (val result = checkGithub(github, mirror, gitee)) {
                is UpdateCheckResult.Available -> return@withContext result
                UpdateCheckResult.UpToDate -> return@withContext result
                is UpdateCheckResult.Failure -> {
                    if (giteeUpToDate) return@withContext UpdateCheckResult.UpToDate
                    val giteePart = giteeProblem?.let { "Gitee：$it；" }.orEmpty()
                    return@withContext UpdateCheckResult.Failure(
                        "${giteePart}GitHub：${result.message}"
                    )
                }
            }
        }

        return@withContext when {
            giteeUpToDate -> UpdateCheckResult.UpToDate
            giteeProblem != null -> UpdateCheckResult.Failure("Gitee 更新检查失败：$giteeProblem")
            else -> UpdateCheckResult.Failure("请先配置 Gitee 或 GitHub 更新源")
        }
    }

    private suspend fun checkGithub(
        repo: String,
        mirror: String,
        giteeRepo: String?
    ): UpdateCheckResult {
        val apiUrl = "https://api.github.com/repos/$repo/releases/latest"
        val urls = buildCheckUrls(apiUrl, mirror)
        var lastProblem = "网络错误"
        for (url in urls) {
            val attempts = if (url == apiUrl) CHECK_ATTEMPTS else 1
            repeat(attempts) { attempt ->
                try {
                    val (code, body) = fetchText(url)
                    when {
                        code in 200..299 ->
                            return parseGithubRelease(repo, body, giteeRepo)

                        // A direct 404 is authoritative; a mirror 404 just
                        // means that mirror cannot serve the API.
                        code == 404 && url == apiUrl ->
                            return UpdateCheckResult.Failure("仓库或 Release 不存在")

                        code == 403 ->
                            lastProblem = "GitHub API 访问受限，请稍后再试"

                        else ->
                            lastProblem = "检查更新失败（HTTP $code）"
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    lastProblem = error.message ?: "网络错误"
                }
                if (attempt < attempts - 1) delay(1200L * (attempt + 1))
            }
        }
        return UpdateCheckResult.Failure("$lastProblem（已尝试直连和镜像）")
    }

    private fun checkGitee(
        repo: String,
        githubRepo: String?
    ): UpdateCheckResult {
        val (code, body) = fetchText(
            "https://gitee.com/api/v5/repos/$repo/releases/latest"
        )
        when (code) {
            404 -> return UpdateCheckResult.Failure("仓库或 Release 不存在")
            !in 200..299 -> return UpdateCheckResult.Failure("检查更新失败（HTTP $code）")
        }
        return parseGiteeRelease(repo, body, githubRepo)
    }

    suspend fun download(
        context: Context,
        info: UpdateInfo,
        mirror: String = ""
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val safeName = info.assetName
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .ifBlank { "qinghe-update.apk" }
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
        val outputFile = File(directory, safeName)
        val sources = buildDownloadSources(info, mirror)
        var lastError: Exception? = null
        for (source in sources) {
            outputFile.delete()
            try {
                downloadWithRetry(source, outputFile, info.sizeBytes)
                val size = outputFile.length()
                if (size == 0L) throw IOException("下载的文件为空")
                if (info.sizeBytes > 0 && size != info.sizeBytes) {
                    throw IOException("下载不完整（$size/${info.sizeBytes}），将换线路重试")
                }
                return@withContext outputFile
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                outputFile.delete()
            }
        }
        val suffix = lastError?.message?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
        throw IOException("网络不稳定，已尝试 ${sources.size} 个下载线路仍失败$suffix")
    }

    private fun parseGithubRelease(
        repo: String,
        body: String,
        giteeRepo: String?
    ): UpdateCheckResult {
        val json = JSONObject(body)
        val tag = json.optString("tag_name")
            .trim()
            .removePrefix("v")
            .removePrefix("V")
        if (tag.isBlank()) {
            return UpdateCheckResult.Failure("Release 缺少版本标签")
        }

        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        var apkName: String? = null
        var apkSize = 0L
        var apkAssetId = 0L
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val url = asset.optString("browser_download_url")
                if (url.isBlank()) continue
                if (apkUrl == null ||
                    name.contains("qinghe", ignoreCase = true) ||
                    name.contains("release", ignoreCase = true)
                ) {
                    apkUrl = url
                    apkName = name
                    apkSize = asset.optLong("size")
                    apkAssetId = asset.optLong("id")
                }
            }
        }
        if (apkUrl == null || apkName == null) {
            return UpdateCheckResult.Failure("该 Release 没有 APK 附件")
        }
        if (apkSize > MAX_APK_BYTES) {
            return UpdateCheckResult.Failure("APK 附件大小异常，已拒绝下载")
        }
        if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) {
            return UpdateCheckResult.UpToDate
        }
        return UpdateCheckResult.Available(
            UpdateInfo(
                versionName = tag,
                changelog = json.optString("body").take(4000),
                downloadUrl = apkUrl,
                assetName = apkName,
                sizeBytes = apkSize,
                apiDownloadUrl = apkAssetId.takeIf { it > 0 }?.let {
                    "https://api.github.com/repos/$repo/releases/assets/$it"
                },
                fallbackDownloadUrls = listOfNotNull(
                    giteeRepo?.let {
                        "https://gitee.com/$it/releases/download/v$tag/$apkName"
                    }
                ),
                sourceLabel = "GitHub"
            )
        )
    }

    private fun parseGiteeRelease(
        repo: String,
        body: String,
        githubRepo: String?
    ): UpdateCheckResult {
        val json = JSONObject(body)
        if (json.optBoolean("prerelease")) {
            return UpdateCheckResult.Failure("最新版本是预发布版，已忽略")
        }
        val tag = json.optString("tag_name")
            .trim()
            .removePrefix("v")
            .removePrefix("V")
        if (tag.isBlank()) {
            return UpdateCheckResult.Failure("Release 缺少版本标签")
        }
        if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) {
            return UpdateCheckResult.UpToDate
        }

        val assets = json.optJSONArray("assets").toGiteeAssets()
        // Gitee keeps upload sizes (and sometimes the uploaded files
        // themselves) on the attach_files endpoint rather than in `assets`.
        var attachFiles = emptyList<GiteeAsset>()
        val releaseId = json.optLong("id")
        if (releaseId > 0) {
            try {
                val (code, attachBody) = fetchText(
                    "https://gitee.com/api/v5/repos/$repo/releases/$releaseId/attach_files"
                )
                if (code in 200..299) {
                    attachFiles = JSONArray(attachBody).toGiteeAssets()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Enrichment is optional; `assets` can still provide a URL.
            }
        }

        val apk = pickGiteeApk(assets, attachFiles)
            ?: return UpdateCheckResult.Failure("该 Release 没有 APK 附件")
        if (apk.size > MAX_APK_BYTES) {
            return UpdateCheckResult.Failure("APK 附件大小异常，已拒绝下载")
        }
        val fallback = githubRepo?.let {
            "https://github.com/$it/releases/download/v$tag/${apk.name}"
        }
        return UpdateCheckResult.Available(
            UpdateInfo(
                versionName = tag,
                changelog = json.optString("body").take(4000),
                downloadUrl = apk.url,
                assetName = apk.name,
                sizeBytes = apk.size,
                fallbackDownloadUrls = listOfNotNull(fallback),
                sourceLabel = "Gitee"
            )
        )
    }

    private fun JSONArray?.toGiteeAssets(): List<GiteeAsset> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    GiteeAsset(
                        name = item.optString("name"),
                        url = item.optString("browser_download_url"),
                        size = item.optLong("size")
                    )
                )
            }
        }
    }

    /**
     * Prefers an uploaded attachment (it carries the size), otherwise the
     * release `assets` entry. Source zip/tar archives are ignored.
     */
    internal fun pickGiteeApk(
        assets: List<GiteeAsset>,
        attachFiles: List<GiteeAsset>
    ): GiteeAsset? {
        fun GiteeAsset.isApk() =
            name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()

        val fromAssets = assets.firstOrNull { it.isApk() }
        val fromAttach = attachFiles.firstOrNull {
            it.isApk() && (fromAssets == null || it.name == fromAssets.name)
        }
        return fromAttach ?: fromAssets
    }

    internal data class GiteeAsset(
        val name: String,
        val url: String,
        val size: Long
    )

    private fun fetchText(url: String): Pair<Int, String> {
        val connection = openConnection(url, apiRequest = true)
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to body
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildCheckUrls(apiUrl: String, mirror: String): List<String> {
        val urls = LinkedHashSet<String>()
        val custom = mirror.trim()
        if (custom.isNotEmpty()) urls.add(applyMirror(custom, apiUrl))
        urls.add(apiUrl)
        BUILT_IN_API_MIRRORS.forEach { urls.add(applyMirror(it, apiUrl)) }
        return urls.toList()
    }

    internal fun buildDownloadSources(
        info: UpdateInfo,
        mirror: String
    ): List<DownloadSource> {
        val sources = LinkedHashMap<String, DownloadSource>()
        fun add(url: String, apiAsset: Boolean = false, attempts: Int = 1) {
            if (url.startsWith("https://") || url.startsWith("http://")) {
                sources.putIfAbsent(url, DownloadSource(url, apiAsset, attempts))
            }
        }
        fun addGithub(url: String) {
            val custom = mirror.trim()
            if (custom.isNotEmpty()) add(applyMirror(custom, url), attempts = 1)
            add(url, attempts = 1)
            BUILT_IN_DOWNLOAD_MIRRORS.forEach { add(applyMirror(it, url), attempts = 2) }
        }

        val primary = info.downloadUrl
        if (isGiteeUrl(primary)) {
            // Gitee is stable in mainland China; GitHub is only the fallback.
            add(primary, attempts = 2)
            info.fallbackDownloadUrls.forEach { url ->
                if (isGithubUrl(url)) addGithub(url) else add(url, attempts = 2)
            }
        } else {
            val custom = mirror.trim()
            if (custom.isNotEmpty()) add(applyMirror(custom, primary), attempts = 1)
            info.apiDownloadUrl?.let { add(it, apiAsset = true, attempts = 1) }
            BUILT_IN_DOWNLOAD_MIRRORS.forEach { add(applyMirror(it, primary), attempts = 2) }
            add(primary, attempts = 1)
            info.fallbackDownloadUrls.forEach { url ->
                if (isGithubUrl(url)) addGithub(url) else add(url, attempts = 2)
            }
        }
        return sources.values.toList()
    }

    private fun isGiteeUrl(url: String): Boolean =
        hostOf(url)?.let { it == "gitee.com" || it.endsWith(".gitee.com") } == true

    private fun isGithubUrl(url: String): Boolean =
        hostOf(url)?.let {
            it == "github.com" || it == "api.github.com" ||
                it.endsWith(".githubusercontent.com")
        } == true

    private fun hostOf(url: String): String? =
        runCatching { URL(url).host.lowercase() }.getOrNull()

    /** Accepts `owner/repo` or a full github.com / gitee.com URL. */
    private fun normalizeRepo(repo: String): String? {
        var value = repo.trim().trim('/')
        value = value
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("https://gitee.com/")
            .removePrefix("http://gitee.com/")
            .trim('/')
        if (value.isBlank()) return null
        return value.takeIf {
            it.matches(Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"""))
        }
    }

    /** Prefixes [url] with a proxy, or substitutes it into a `{url}` template. */
    internal fun applyMirror(mirror: String, url: String): String {
        val trimmed = mirror.trim()
        if (trimmed.isEmpty()) return url
        if (trimmed.contains(MIRROR_PLACEHOLDER)) {
            return trimmed.replace(MIRROR_PLACEHOLDER, url)
        }
        return if (trimmed.endsWith("/")) "$trimmed$url" else "$trimmed/$url"
    }

    private suspend fun downloadWithRetry(
        source: DownloadSource,
        outputFile: File,
        expectedSize: Long
    ) {
        var lastError: Exception? = null
        repeat(source.attempts) { attempt ->
            try {
                downloadOnce(source, outputFile, expectedSize)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                if (attempt < source.attempts - 1) {
                    delay(800L * (attempt + 1))
                }
            }
        }
        throw lastError ?: IOException("下载失败")
    }

    private fun downloadOnce(
        source: DownloadSource,
        outputFile: File,
        expectedSize: Long
    ) {
        val existing = if (outputFile.exists()) outputFile.length() else 0L
        if (expectedSize > 0 && existing >= expectedSize) return
        // Without a known size, resuming could leave a corrupt prefix; restart.
        if (expectedSize <= 0 && existing > 0L) outputFile.delete()
        val resumeFrom = if (expectedSize > 0) existing else 0L
        val connection = openConnection(
            source.url,
            downloadAsset = source.apiAsset,
            rangeFrom = resumeFrom
        )
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("下载失败（HTTP $code）")
            }
            val contentType = connection.contentType.orEmpty()
            if (contentType.startsWith("text/", ignoreCase = true)) {
                throw IOException("镜像返回了网页而不是 APK")
            }
            val append = existing > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (existing > 0L && !append) {
                outputFile.delete()
            }
            val alreadyHave = if (append) existing else 0L
            val allowed = if (expectedSize > 0) {
                (expectedSize - alreadyHave).coerceAtLeast(0L)
            } else {
                MAX_APK_BYTES
            }
            connection.inputStream.use { input ->
                FileOutputStream(outputFile, append).use { output ->
                    copyBounded(input, output, allowed)
                }
            }
        } finally {
            connection.disconnect()
        }
        val size = outputFile.length()
        if (size == 0L) throw IOException("下载的文件为空")
        if (expectedSize > 0 && size < expectedSize) {
            // Keep the partial file: the retry asks for the remaining bytes.
            throw IOException("下载不完整（$size/$expectedSize）")
        }
        if (expectedSize > 0 && size > expectedSize) {
            outputFile.delete()
            throw IOException("下载内容大小异常")
        }
    }

    /** Copies the stream, refusing to write more than [maxBytes]. */
    private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            if (total > maxBytes) {
                throw IOException("下载内容超过预期大小")
            }
            output.write(buffer, 0, read)
        }
    }

    fun validateApk(context: Context, file: File): String? {
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return "无法读取下载的 APK"
        if (archive.packageName != context.packageName) {
            return "下载的 APK 包名不一致"
        }
        if ((archive.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            return "更新包是 debug 构建，请上传 release APK"
        }
        val installed = manager.getPackageInfo(context.packageName, flags)
        if (versionCode(archive) <= versionCode(installed)) {
            return "下载的 APK 版本不高于当前版本"
        }
        val archiveCerts = certificateHashes(archive)
        val installedCerts = certificateHashes(installed)
        if (archiveCerts.isEmpty() || installedCerts.isEmpty()) {
            return "无法校验 APK 签名"
        }
        if (archiveCerts != installedCerts) {
            return "APK 签名与当前应用不一致，无法覆盖安装"
        }
        return null
    }

    fun install(context: Context, file: File): UpdateInstallResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            openUnknownSourcesSettings(context)
            return UpdateInstallResult.NeedsPermission(
                "请先允许青禾安装未知应用，然后重新点击「下载并安装」"
            )
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            UpdateInstallResult.Started
        } catch (error: Exception) {
            UpdateInstallResult.Failure(error.message ?: "无法启动安装器")
        }
    }

    fun compareVersions(left: String, right: String): Int {
        fun parts(value: String): List<Int> =
            value.trim()
                .removePrefix("v")
                .removePrefix("V")
                .split('.', '-', '+')
                .map { it.toIntOrNull() ?: 0 }

        val a = parts(left)
        val b = parts(right)
        val count = maxOf(a.size, b.size)
        for (index in 0 until count) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun certificateHashes(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners ?: return emptySet()
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: return emptySet()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.toSet()
    }

    private fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun openConnection(
        url: String,
        apiRequest: Boolean = false,
        downloadAsset: Boolean = false,
        rangeFrom: Long = 0L
    ): HttpURLConnection {
        var current = url
        repeat(6) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.connectTimeout = if (apiRequest) 10_000 else 12_000
            connection.readTimeout = if (apiRequest) 15_000 else 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "QingheLedger/${BuildConfig.VERSION_NAME}")
            if (apiRequest) {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
            } else if (downloadAsset) {
                // Required for the release asset endpoint to redirect to the CDN.
                connection.setRequestProperty("Accept", "application/octet-stream")
            }
            if (rangeFrom > 0L) {
                connection.setRequestProperty("Range", "bytes=$rangeFrom-")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("下载地址缺少跳转目标")
                }
                current = if (location.startsWith("http")) {
                    location
                } else {
                    URL(URL(current), location).toString()
                }
                return@repeat
            }
            return connection
        }
        throw IllegalStateException("下载地址重定向次数过多")
    }
}
