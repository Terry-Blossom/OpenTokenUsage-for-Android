package com.opentokenusage.android.data.update

import android.content.Context
import android.os.Build
import com.opentokenusage.android.BuildConfig
import com.opentokenusage.android.data.network.ProviderHttpSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

data class AvailableUpdate(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
)

/**
 * Checks GitHub Releases for a newer build and downloads the attached APK.
 * Installation itself is delegated to [ApkInstaller] so the caller controls the
 * unknown-sources permission flow.
 */
class AppUpdater(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    suspend fun checkForUpdate(): Result<AvailableUpdate?> = withContext(Dispatchers.IO) {
        try {
            Result.success(fetchLatest())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadApk(
        update: AvailableUpdate,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            Result.success(fetchApk(update, onProgress))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchLatest(): AvailableUpdate? {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "OpenTokenUsage-Android/${BuildConfig.VERSION_NAME}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(httpErrorMessage(response.code))
            val body = response.body?.string().orEmpty()
            val release = try {
                ProviderHttpSupport.json.decodeFromString<GitHubRelease>(body)
            } catch (e: Exception) {
                throw IOException("更新情報を解析できませんでした")
            }
            val remoteVersion = release.tagName.trim().removePrefix("v").removePrefix("V")
            if (remoteVersion.isBlank() || !isNewerVersion(remoteVersion, BuildConfig.VERSION_NAME)) {
                return null
            }
            val apk = release.assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true) && it.browserDownloadUrl.isNotBlank()
            } ?: throw IOException("リリースに APK が添付されていません")

            return AvailableUpdate(
                versionName = remoteVersion,
                notes = release.body.trim(),
                apkUrl = apk.browserDownloadUrl,
                apkSizeBytes = apk.size,
            )
        }
    }

    private suspend fun fetchApk(update: AvailableUpdate, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, APK_DIR).apply { mkdirs() }
        val apkFile = File(dir, "OpenTokenUsage-${update.versionName}.apk")
        dir.listFiles()
            ?.filter { it.name != apkFile.name && it.name.startsWith("OpenTokenUsage-") }
            ?.forEach { it.delete() }

        if (update.apkSizeBytes > 0 && apkFile.length() == update.apkSizeBytes) {
            validateApk(apkFile)
            withContext(Dispatchers.Main) { onProgress(1f) }
            return apkFile
        }

        val request = Request.Builder()
            .url(update.apkUrl)
            .header("Accept", "application/octet-stream")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(httpErrorMessage(response.code))
            val body = response.body ?: throw IOException("APK の応答が空です")
            val total = body.contentLength().takeIf { it > 0 } ?: update.apkSizeBytes
            val temp = File(dir, "${apkFile.name}.part")
            body.byteStream().use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) { onProgress(percent / 100f) }
                            }
                        }
                    }
                }
            }

            if (update.apkSizeBytes > 0 && temp.length() != update.apkSizeBytes) {
                temp.delete()
                throw IOException("ダウンロードした APK のサイズが一致しません")
            }
            apkFile.delete()
            if (!temp.renameTo(apkFile)) {
                temp.delete()
                throw IOException("APK の保存に失敗しました")
            }
        }

        try {
            validateApk(apkFile)
        } catch (e: Exception) {
            apkFile.delete()
            throw e
        }
        withContext(Dispatchers.Main) { onProgress(1f) }
        return apkFile
    }

    @Suppress("DEPRECATION")
    private fun validateApk(apkFile: File) {
        if (!apkFile.isFile) throw IOException("APK が見つかりません")
        val archive = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            ?: throw IOException("APK を解析できませんでした")
        if (archive.packageName != context.packageName) {
            throw IOException("別のアプリの APK がダウンロードされました")
        }
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }
        if (archiveVersionCode <= BuildConfig.VERSION_CODE) {
            throw IOException("この APK は現在のバージョンより新しくありません")
        }
    }

    private fun httpErrorMessage(code: Int): String = when (code) {
        403 -> "GitHub の更新確認回数が上限に達しました。時間をおいて再試行してください"
        404 -> "GitHub にリリース情報が見つかりません"
        else -> "GitHub への接続に失敗しました（HTTP $code）"
    }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/otetcham/OpenTokenUsage-for-Android/releases/latest"
        const val APK_DIR = "updates"
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val body: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0L,
)

/** Compares dotted version numbers such as `v0.1.10` against `0.1.9`. */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    fun components(raw: String): List<Int> = raw.trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }

    val candidateParts = components(candidate)
    val currentParts = components(current)
    for (index in 0 until maxOf(candidateParts.size, currentParts.size)) {
        val candidatePart = candidateParts.getOrElse(index) { 0 }
        val currentPart = currentParts.getOrElse(index) { 0 }
        if (candidatePart != currentPart) return candidatePart > currentPart
    }
    return false
}
