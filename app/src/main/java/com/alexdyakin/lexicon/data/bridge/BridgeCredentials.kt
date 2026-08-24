package com.alexdyakin.lexicon.data.bridge

import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.AuthApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the bridge connection actually needs in order to authenticate.
 *
 * Kept as an interface so the connection can be tested on the JVM: [TokenStore] is
 * backed by EncryptedSharedPreferences and cannot be constructed off-device. It also
 * puts the single-use token rule in one place instead of leaving it implicit at the
 * call site.
 */
interface BridgeCredentials {
    val isSignedIn: Boolean

    /**
     * Mints a fresh SSO token.
     *
     * Must be called immediately before a connect attempt: the token is single-use
     * and expires after sixty seconds, so one minted before a backoff wait would be
     * spent on nothing. Throws if Lexicon refuses.
     */
    suspend fun mintSsoToken(): String
}

@Singleton
class LexiconBridgeCredentials @Inject constructor(
    private val tokenStore: TokenStore,
    private val authApi: AuthApi,
) : BridgeCredentials {
    override val isSignedIn: Boolean get() = tokenStore.isLoggedIn

    override suspend fun mintSsoToken(): String = authApi.generateSsoToken().token
}
