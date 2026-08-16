package com.alexdyakin.lexicon.data

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches `Authorization: Bearer <token>` to the backends that accept it, and
 * picks up a rotated token when the server hands one back on X-Mobile-Token.
 *
 * Scoped by host so the credential is never sent to Alchemy or anywhere else.
 */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {

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

        return response
    }

    private companion object {
        const val REFRESHED_TOKEN_HEADER = "X-Mobile-Token"
    }
}
