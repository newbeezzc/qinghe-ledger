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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UpdateInfo(
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
    val assetName: String,
    val sizeBytes: Long,
    val apiDownloadUrl: String? = null
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
    suspend fun check(repo: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val normalized = repo.trim().trim('/')
        if (!normalized.matches(Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"""))) {
            return@withContext UpdateCheckResult.Failure("更新源格式应为 GitHub 用户名/仓库名")
        }
        try {
            val connection = openConnection(
                "https://api.github.com/repos/$normalized/releases/latest",
                apiRequest = true
            )
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()

            when (code) {
                404 -> return@withContext UpdateCheckResult.Failure("仓库或 Release 不存在")
                403 -> return@withContext UpdateCheckResult.Failure("GitHub API 访问受限，请稍后再试")
            }
            if (code !in 200..299) {
                return@withContext UpdateCheckResult.Failure("检查更新失败（HTTP $code）")
            }

            val json = JSONObject(body)
            val tag = json.optString("tag_name")
                .trim()
                .removePrefix("v")
                .removePrefix("V")
            if (tag.isBlank()) {
                return@withContext UpdateCheckResult.Failure("Release 缺少版本标签")
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
                return@withContext UpdateCheckResult.Failure("该 Release 没有 APK 附件")
            }
            if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) {
                return@withContext UpdateCheckResult.UpToDate
            }
            UpdateCheckResult.Available(
                UpdateInfo(
                    versionName = tag,
                    changelog = json.optString("body").take(4000),
                    downloadUrl = apkUrl,
                    assetName = apkName,
                    sizeBytes = apkSize,
                    apiDownloadUrl = apkAssetId.takeIf { it > 0 }?.let {
                        "https://api.github.com/repos/$normalized/releases/assets/$it"
                    }
                )
            )
        } catch (error: Exception) {
            UpdateCheckResult.Failure(error.message ?: "网络错误")
        }
    }

    suspend fun download(context: Context, info: UpdateInfo): File =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            directory.listFiles()?.forEach { it.delete() }
            val safeName = info.assetName
                .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                .ifBlank { "qinghe-update.apk" }
                .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
            val outputFile = File(directory, safeName)
            // api.github.com + release-assets.githubusercontent.com is usually reachable
            // where github.com release downloads are blocked or time out (e.g. mainland China).
            val candidates = buildList {
                info.apiDownloadUrl?.let { add(it to true) }
                add(info.downloadUrl to false)
            }
            var lastError: Exception? = null
            for ((url, apiAsset) in candidates) {
                try {
                    val connection = openConnection(url, downloadAsset = apiAsset)
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        connection.disconnect()
                        throw IllegalStateException("下载失败（HTTP $code）")
                    }
                    connection.inputStream.use { input ->
                        outputFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    connection.disconnect()
                    if (!outputFile.exists() || outputFile.length() == 0L) {
                        throw IllegalStateException("下载的文件为空")
                    }
                    return@withContext outputFile
                } catch (error: Exception) {
                    outputFile.delete()
                    lastError = error
                }
            }
            throw lastError ?: IllegalStateException("下载更新失败")
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
        downloadAsset: Boolean = false
    ): HttpURLConnection {
        var current = url
        repeat(6) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "QingheLedger/${BuildConfig.VERSION_NAME}")
            if (apiRequest) {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
            } else if (downloadAsset) {
                // Required for the release asset endpoint to redirect to the CDN.
                connection.setRequestProperty("Accept", "application/octet-stream")
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
