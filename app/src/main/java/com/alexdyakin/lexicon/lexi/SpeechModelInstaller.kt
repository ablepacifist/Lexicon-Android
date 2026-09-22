package com.alexdyakin.lexicon.lexi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Fetches Vosk's speech-recognition model the first time Lexi is switched on.
 *
 * It is not bundled in the APK on purpose. At 41 MB it would be re-downloaded with every
 * app update the in-app updater ships, for a file that never changes. Fetched once, it
 * lives in app-private storage and survives updates.
 *
 * The download is pinned by SHA-256, and nothing is installed until the hash matches: an
 * interrupted or tampered download leaves the previous model (or none) in place rather
 * than a half-written one that would fail later, inside the speech engine, where the
 * cause would be far from obvious.
 *
 * The phone needs a network for Lexi to reach the brain anyway, so requiring one for the
 * first switch-on costs no capability.
 */
class SpeechModelInstaller(
    private val http: OkHttpClient,
    /** Where the model ends up. Kept out of the cache directory, which Android may clear. */
    private val modelDir: File,
    /** Scratch space for the archive; cleared as soon as it has been unpacked. */
    private val workDir: File,
    private val url: String = VOSK_SMALL_EN_US_URL,
    private val sha256: String = VOSK_SMALL_EN_US_SHA256,
    val downloadBytes: Long = VOSK_SMALL_EN_US_BYTES,
) {

    /** Progress of one install, 0..1, or null while the size is still unknown. */
    fun interface Progress {
        fun onProgress(fraction: Float?)
    }

    private val stamp = File(modelDir, STAMP_FILE)

    /** True when a verified model is already in place, so switching Lexi on is instant. */
    fun isInstalled(): Boolean = stamp.takeIf { it.isFile }?.readText()?.trim() == sha256

    /**
     * Returns the directory holding the model, downloading it first if necessary.
     *
     * @throws IOException if the download fails, the hash does not match, or there is not
     *   enough room for the model.
     */
    suspend fun ensureInstalled(progress: Progress = Progress {}): File = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext modelDir

        val free = (workDir.parentFile ?: workDir).usableSpace
        if (free in 1 until REQUIRED_FREE_BYTES) {
            throw IOException("Lexi needs about ${REQUIRED_FREE_BYTES / 1_000_000} MB free to install its speech model.")
        }

        workDir.deleteRecursively()
        workDir.mkdirs()
        val archive = File(workDir, "model.zip")
        try {
            download(archive, progress)
            verify(archive)
            unpack(archive)
            modelDir
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun download(target: File, progress: Progress) {
        progress.onProgress(0f)
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!response.isSuccessful) throw IOException("The speech model download failed (${response.code}).")
            val body = response.body ?: throw IOException("The speech model download was empty.")
            val total = body.contentLength().takeIf { it > 0 } ?: downloadBytes

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var written = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        progress.onProgress(if (total > 0) (written.toFloat() / total).coerceIn(0f, 1f) else null)
                    }
                }
            }
        }
    }

    private fun verify(archive: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256) {
            throw IOException("The speech model download was corrupted; its checksum does not match.")
        }
    }

    private suspend fun unpack(archive: File) {
        val staging = File(workDir, "unpacked")
        archive.inputStream().use { raw -> extract(raw, staging) }

        // Swap in only once the whole archive is unpacked, then stamp it: a half-written
        // model must never look installed.
        modelDir.deleteRecursively()
        modelDir.parentFile?.mkdirs()
        if (!staging.renameTo(modelDir)) {
            staging.copyRecursively(modelDir, overwrite = true)
        }
        stamp.writeText(sha256)
    }

    private suspend fun extract(source: InputStream, target: File) {
        ZipInputStream(source.buffered()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                // Drop the archive's versioned top-level folder, so upgrading the model
                // does not move everything the engine loads.
                val relative = entry.name.substringAfter('/')
                if (entry.isDirectory || relative.isEmpty()) continue

                val file = File(target, relative)
                if (!file.canonicalPath.startsWith(target.canonicalFile.path + File.separator)) {
                    throw IOException("The speech model archive contains an unsafe path: ${entry.name}")
                }
                file.parentFile?.mkdirs()
                file.outputStream().use { zip.copyTo(it) }
            }
        }
    }

    companion object {
        private const val STAMP_FILE = "installed.sha256"

        /** Unpacked (71 MB) plus the archive (41 MB), with room to spare. */
        private const val REQUIRED_FREE_BYTES = 150_000_000L

        // Vosk's small English model, the one the Pi uses. Pinned by hash, so the app is
        // not trusting whatever happens to be at this URL later. To serve it yourself,
        // point this at your own host; the hash stays the same.
        const val VOSK_SMALL_EN_US_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val VOSK_SMALL_EN_US_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
        const val VOSK_SMALL_EN_US_BYTES = 41_205_931L
    }
}
