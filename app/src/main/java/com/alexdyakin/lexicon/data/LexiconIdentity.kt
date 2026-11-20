package com.alexdyakin.lexicon.data

import com.alexdyakin.lexicon.data.api.AuthApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who the signed-in player is, as far as other backends need to know.
 *
 * Lexicon decides permissions (PLAYERS.ROLE → PermissionManager); the app only shows or hides
 * controls from them. Alchemy enforces them again on every request by trading the single-use
 * SSO token for the caller's permissions. An interface so view models can be tested on the JVM.
 */
interface LexiconIdentity {
    /** Permission names Lexicon reports for the current player; empty when it cannot be asked. */
    suspend fun permissions(): List<String>

    /** A fresh single-use SSO token. Mint immediately before the request that needs it. */
    suspend fun mintSsoToken(): String
}

/** Permission names shared with Lexicon's `Permission` enum. */
object Permissions {
    const val HOLDFAST_ADVANCE_TIME = "HOLDFAST_ADVANCE_TIME"
    const val HOLDFAST_DELETE = "HOLDFAST_DELETE"
}

@Singleton
class AuthApiLexiconIdentity @Inject constructor(
    private val authApi: AuthApi,
) : LexiconIdentity {
    override suspend fun permissions(): List<String> = authApi.me().permissions

    override suspend fun mintSsoToken(): String = authApi.generateSsoToken().token
}
