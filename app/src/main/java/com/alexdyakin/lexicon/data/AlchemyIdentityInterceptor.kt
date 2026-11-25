package com.alexdyakin.lexicon.data

import android.util.Log
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Proves to Alchemy who is asking.
 *
 * Alchemy used to act on whatever `playerId` a request named, so anyone could read or
 * spend another player's inventory. It now accepts a single-use Lexicon SSO token in
 * `X-Lexicon-Sso`, trades it with Lexicon for the signed-in player, and refuses a request
 * whose `playerId` is someone else (the website does the same, see `alchemyApi.js`).
 *
 * Added here, on the Alchemy client, rather than in each call: one place covers every
 * player-scoped route and every screen that uses them. A request that already carries the
 * header (Holdfast mints its own) is left alone. If a token cannot be minted the request
 * goes without one; while the server's `ALCHEMY_REQUIRE_SSO` is off it is still served.
 *
 * The token is minted with a plain synchronous call on [lexiconClient], not through a
 * suspend Retrofit call: an interceptor already runs on an OkHttp thread, and blocking that
 * thread on a coroutine that needs another OkHttp thread is how dispatchers deadlock.
 */
class AlchemyIdentityInterceptor(
    private val lexiconClient: OkHttpClient,
    private val json: Json,
    private val lexiconBaseUrl: String = ApiUrls.LEXICON,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(SSO_HEADER) != null || !isPlayerScoped(request.url.encodedPath)) {
            return chain.proceed(request)
        }
        val token = mint()
        val sent = if (token == null) request else request.newBuilder().header(SSO_HEADER, token).build()
        return chain.proceed(sent)
    }

    private fun mint(): String? = runCatching {
        val call = Request.Builder()
            .url("${lexiconBaseUrl}api/auth/sso/generate-token")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        lexiconClient.newCall(call).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            json.decodeFromString(SsoTokenResponse.serializer(), body).token.takeIf { it.isNotBlank() }
        }
    }.onFailure { Log.w("Lexicon", "Could not mint an SSO token for Alchemy", it) }.getOrNull()

    companion object {
        const val SSO_HEADER = "X-Lexicon-Sso"

        /** The routes Alchemy checks the caller on (CallerCheck in alchemyServer). */
        private val PLAYER_SCOPED = Regex(
            """/api/(player/(inventory|forage|ingredient/consume|potion/consume|levelup|knowledge)|potion/brew|game/forage)(/|$)""",
        )

        fun isPlayerScoped(path: String): Boolean = PLAYER_SCOPED.containsMatchIn(path)
    }
}
