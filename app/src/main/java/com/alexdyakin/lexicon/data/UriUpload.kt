package com.alexdyakin.lexicon.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.FileNotFoundException

/**
 * A picked file, streamed into the request as OkHttp writes it.
 *
 * Uploads used to `readBytes()` the whole file first, on the main thread, then hand
 * OkHttp a byte array. A film is gigabytes: the app froze while reading and then died
 * of OutOfMemoryError. This reads nothing until OkHttp asks, on OkHttp's own thread, and
 * holds only one buffer at a time.
 */
class UriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val type: MediaType?,
) : RequestBody() {

    private val length: Long by lazy { resolver.sizeOf(uri) ?: -1L }

    override fun contentType(): MediaType? = type

    /** Known when the provider reports it, so the server and any progress bar can see it. */
    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val stream = resolver.openInputStream(uri)
            ?: throw FileNotFoundException("Could not open $uri")
        stream.source().use { sink.writeAll(it) }
    }
}

fun ContentResolver.uploadBody(uri: Uri, fallbackType: String? = null): UriRequestBody =
    UriRequestBody(this, uri, (getType(uri) ?: fallbackType)?.toMediaTypeOrNull())

/**
 * The name the user sees for the file. `Uri.lastPathSegment` is the provider's internal
 * id for most pickers ("document/primary:Movies/clip.mp4" or just "msf:1000034"), which is
 * what uploads were named on the server.
 */
fun ContentResolver.displayName(uri: Uri): String? = runCatching {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() }

fun ContentResolver.sizeOf(uri: Uri): Long? = runCatching {
    query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
    }
}.getOrNull()?.takeIf { it >= 0 }

/** [displayName], or the last path segment, made safe to use as a file name. */
fun ContentResolver.fileNameFor(uri: Uri, fallback: String): String =
    safeFileName(displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/'), fallback)

private const val MAX_NAME = 120

private val UNSAFE_FILE_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

/**
 * A name that is only a name: no directories, no characters a file system rejects, not
 * empty, not "." or "..", and not absurdly long. Used for names we send (uploads) and
 * names the server sends us (downloads), where "../" would otherwise climb out of the
 * Downloads folder.
 */
fun safeFileName(raw: String?, fallback: String): String {
    val cleaned = raw.orEmpty()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(UNSAFE_FILE_CHARS, "_")
        .trim()
        .trim('.')
    if (cleaned.isEmpty()) return fallback
    if (cleaned.length <= MAX_NAME) return cleaned
    // Shorten the stem, not the extension, so "….mp4" still opens as a video.
    val ext = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..10 }
    return if (ext == null) cleaned.take(MAX_NAME)
    else cleaned.dropLast(ext.length + 1).take(MAX_NAME - ext.length - 1) + "." + ext
}
