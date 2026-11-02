package com.alexdyakin.lexicon.data.bridge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.di.BaseOkHttp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** What the bridge returns after storing a file in Lexicon. */
@Serializable
data class ChatUploadResult(
    val id: Long = 0,
    val url: String? = null,
    val thumbnailUrl: String? = null,
    val originalFilename: String? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * Uploads an image to the bridge, which forwards it to Lexicon and hands back the ids
 * needed to post it.
 *
 * This is a plain OkHttp call rather than Retrofit because the endpoint lives on the
 * bridge host rather than Lexicon, and it takes a hand-built multipart body whose field
 * names the server matches exactly. It is deliberately **not** run through the Lexicon
 * [com.alexdyakin.lexicon.data.AuthInterceptor]: the bridge has its own credential, the
 * session token it issued on `auth_ok`, which is sent here as `Authorization: Bearer`.
 * The bridge acts as the signed-in user that token belongs to; the `username`/`userId`
 * form fields are still sent for older bridges but are ignored by a current one.
 *
 * The server's limits are enforced here too, so an oversized or unsupported file fails
 * immediately with something readable instead of costing an 8 MB round trip to be told
 * the same thing.
 */
@Singleton
class ChatUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    // The plain client, not the WebSocket one: that has readTimeout(0) for a socket
    // that stays open, which is wrong for an 8MB request that must eventually give up.
    @BaseOkHttp private val client: OkHttpClient,
    private val json: Json,
    private val connection: BridgeConnection,
) {

    /** Overridable only so JVM tests can point at a local server. */
    internal var endpoint: String = ApiUrls.VOICE + "api/chat/upload"

    suspend fun upload(
        uri: Uri,
        userId: Int,
        username: String,
        channelId: Int,
    ): Result<ChatUploadResult> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            require(mime in ALLOWED_TYPES) { "That file type is not supported." }

            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read that image.")
            require(bytes.size <= MAX_BYTES) { "That image is larger than 8MB." }

            val request = buildUploadRequest(
                url = endpoint,
                sessionToken = connection.sessionToken,
                bytes = bytes,
                mime = mime,
                filename = displayName(uri),
                userId = userId,
                username = username,
                channelId = channelId,
            )

            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "upload failed: ${response.code} $payload")
                    // The server replies with {"error": "..."}, which is worth surfacing
                    // verbatim — it names the actual limit that was hit.
                    error(errorMessage(payload) ?: "Upload failed (${response.code}).")
                }
                json.decodeFromString(ChatUploadResult.serializer(), payload)
            }
        }
    }

    private fun errorMessage(payload: String): String? = runCatching {
        json.decodeFromString(UploadError.serializer(), payload).error
    }.getOrNull()

    /** The original filename, which the server echoes back and both clients display. */
    private fun displayName(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) {
                it.getString(index)?.let { name -> return name }
            }
        }
        return "image"
    }

    @Serializable
    private data class UploadError(val error: String = "")

    internal companion object {
        /** The upload call, kept free of Android types so it can be checked on the JVM. */
        fun buildUploadRequest(
            url: String,
            sessionToken: String?,
            bytes: ByteArray,
            mime: String,
            filename: String,
            userId: Int,
            username: String,
            channelId: Int,
        ): Request {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, bytes.toRequestBody(mime.toMediaType()))
                .addFormDataPart("username", username)
                .addFormDataPart("userId", userId.toString())
                .addFormDataPart("channelId", channelId.toString())
                .build()
            return Request.Builder()
                .url(url)
                .apply { if (!sessionToken.isNullOrEmpty()) header("Authorization", "Bearer $sessionToken") }
                .post(body)
                .build()
        }

        const val TAG = "ChatUploader"
        const val MAX_BYTES = 8 * 1024 * 1024
        val ALLOWED_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
    }
}
