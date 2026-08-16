package com.alexdyakin.lexicon.ui.profile

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.AppVersionInfo
import com.alexdyakin.lexicon.data.api.AppUpdateApi
import com.alexdyakin.lexicon.data.di.BaseOkHttp
import com.alexdyakin.lexicon.data.safeApiCall
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AppUpdateApi,
    @BaseOkHttp private val httpClient: OkHttpClient,
) {
    fun currentVersionCode(): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(packageInfo)
    }

    fun currentVersionName(): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.versionName ?: ""
    }

    suspend fun fetchLatestVersion(): ApiResult<AppVersionInfo> = safeApiCall { api.latestVersion() }

    suspend fun downloadApk(
        downloadUrl: String,
        expectedSha256: String?,
        onProgress: (Int?) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(downloadUrl).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                error("Download failed (HTTP ${response.code}).")
            }

            val body = response.body ?: run {
                response.close()
                error("Download failed: empty response body.")
            }

            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()
            val apkFile = File(updatesDir, "lexicon-latest.apk")

            val totalBytes = body.contentLength().takeIf { it > 0L }
            var bytesRead = 0L
            body.byteStream().use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (totalBytes != null) {
                            onProgress(((bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100))
                        } else {
                            onProgress(null)
                        }
                    }
                }
            }
            response.close()

            val expected = expectedSha256?.trim()?.lowercase().orEmpty()
            if (expected.isNotBlank()) {
                val actual = sha256(apkFile)
                if (actual != expected) {
                    apkFile.delete()
                    error("Downloaded APK failed integrity check.")
                }
            }
            onProgress(100)
            apkFile
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun buildInstallIntent(apkFile: File): Intent {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchInstaller(apkFile: File): Result<Unit> = runCatching {
        context.startActivity(buildInstallIntent(apkFile))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
