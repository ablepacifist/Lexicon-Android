package com.alexdyakin.lexicon.data

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches `Authorization: Bearer <token>` to the backends that accept it, and
 * picks up a rotated token when the server hands one back on X-Mobile-Token.
 *
 * Scoped by host so the credential is never sent to Alchemy or anywhere else.
 */
class AuthInterceptor(
    private val tokenStore: TokenStore,
    private val sessionExpiry: SessionExpiry? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokenStore.token

        val authed = if (token.isNullOrEmpty() || request.url.host !in ApiUrls.TOKEN_AWARE_HOSTS) {
            request
        } else {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }

        val response = chain.proceed(authed)

        response.header(REFRESHED_TOKEN_HEADER)?.let { rotated ->
            if (rotated.isNotEmpty()) tokenStore.token = rotated
        }

        // Our servers answer 401 only for "not signed in", so a 401 to a request that carried
        // the token means the token is no longer good. Only if it is still the current one:
        // a late answer to a request sent before sign-out or a rotation proves nothing.
        if (response.code == 401 && authed !== request && token == tokenStore.token &&
            !request.url.encodedPath.contains("/api/auth/login")
        ) {
            sessionExpiry?.report()
        }

        return response
    }

    private companion object {
        const val REFRESHED_TOKEN_HEADER = "X-Mobile-Token"
    }
}
