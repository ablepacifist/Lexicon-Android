package com.alexdyakin.lexicon.data

import android.content.Context
import android.util.Log
import com.alexdyakin.lexicon.data.api.AuthApi
import com.alexdyakin.lexicon.data.bridge.BridgeRepository
import com.alexdyakin.lexicon.data.di.BaseOkHttp
import com.alexdyakin.lexicon.media.PlayerConnection
import com.alexdyakin.lexicon.voice.VoiceService
import com.alexdyakin.lexicon.voice.VoiceSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.net.CookieManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything "Sign out" has to undo, in one place.
 *
 * It used to clear the stored token and nothing else. The server session and the bearer
 * token stayed valid, the voice socket stayed signed in as the old user (so the next
 * person on the phone got their DMs and channels), the notification stream kept running
 * for the old account, their media kept playing, and their session cookie and cached
 * responses stayed on disk.
 */
@Singleton
class SessionEnder @Inject constructor(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
    private val bridge: BridgeRepository,
    private val voice: VoiceSession,
    private val notifications: NotificationRepository,
    private val player: PlayerConnection,
    private val cookies: CookieManager,
    @BaseOkHttp private val http: OkHttpClient,
    @ApplicationContext private val context: Context,
) {
    suspend fun signOut() {
        // The server first, while the token still authenticates the request; it revokes the
        // bearer token along with the session. Bounded, so a dead network cannot trap the
        // user in the app: everything below happens regardless.
        withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { safeApiCall { authApi.logout() } }

        voice.stop()
        VoiceService.stop(context)
        bridge.signOut()
        notifications.signOut()
        player.stop()

        cookies.cookieStore.removeAll()
        withContext(Dispatchers.IO) {
            runCatching { http.cache?.evictAll() }.onFailure { Log.w("Lexicon", "HTTP cache not cleared", it) }
        }
        tokenStore.clear()
    }

    private companion object {
        const val LOGOUT_TIMEOUT_MS = 4_000L
    }
}
