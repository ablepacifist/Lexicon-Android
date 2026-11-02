package com.alexdyakin.lexicon.ui.profile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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
            // No published checksum means nothing to verify the file against, so refuse before downloading anything
            val expected = UpdateVerification.requireChecksum(expectedSha256)
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

            // The file must match the published checksum and be signed by the same key as this app; otherwise it is
            // deleted and never offered to the installer.
            try {
                if (UpdateVerification.sha256Hex(apkFile) != expected) error("Downloaded APK failed integrity check.")
                checkSignedByUs(apkFile)
            } catch (e: Throwable) {
                apkFile.delete()
                throw e
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

    /**
     * Refuses a file that is not this app, or that is signed with a different key. Android's installer enforces the
     * signature too; doing it here means a wrong file is never shown to the user as an update.
     *
     * Only a definite mismatch blocks: if the platform cannot read the installed app's own signers there is nothing
     * to compare against, and the installer remains the final check.
     */
    private fun checkSignedByUs(apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("The download is not a valid app package.")
        if (archive.packageName != context.packageName) error("The download is not this app.")

        val installed = runCatching { signerDigests(pm.getPackageInfo(context.packageName, flags)) }.getOrDefault(emptySet())
        if (!UpdateVerification.sharesSigner(installed, signerDigests(archive))) {
            error("The download is signed with a different key than this app.")
        }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val certificates: List<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            // the full lineage, so a key that was rotated to is still recognised
            (if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory)
                .orEmpty().map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION") info.signatures.orEmpty().map { it.toByteArray() }
        }
        return UpdateVerification.digestsOf(certificates)
    }
}
