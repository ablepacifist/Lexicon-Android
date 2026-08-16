package com.alexdyakin.lexicon.push

import android.util.Log
import com.alexdyakin.lexicon.data.DeviceTokenRequest
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.NotificationApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Sends this device's FCM token to the backend so it can be targeted by pushes.
 *
 * Every entry point guards on Firebase actually being configured: the app is built
 * without google-services.json until Firebase is set up, and touching
 * [FirebaseMessaging] in that state throws.
 */
@Singleton
class PushTokenRegistrar @Inject constructor(
    private val api: NotificationApi,
    private val tokenStore: TokenStore,
) {
    private fun firebaseReady(): Boolean =
        runCatching { FirebaseApp.getInstance() != null }.getOrDefault(false)

    /** Pushes the current token to the backend. Call after login. */
    suspend fun syncCurrentToken() {
        if (!firebaseReady()) {
            Log.i(TAG, "Firebase not configured; skipping push token sync.")
            return
        }
        if (!tokenStore.isLoggedIn) return
        val token = currentToken() ?: return
        register(token)
    }

    /**
     * Reads the FCM token without pulling in kotlinx-coroutines-play-services just
     * for one `await()`.
     */
    private suspend fun currentToken(): String? = runCatching {
        suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    continuation.resume(if (task.isSuccessful) task.result else null)
                }
        }
    }.getOrElse {
        Log.w(TAG, "Could not read FCM token", it)
        null
    }

    suspend fun register(token: String) {
        if (!tokenStore.isLoggedIn) return
        val result = safeApiCall {
            api.registerDeviceToken(DeviceTokenRequest(userId = tokenStore.userId, token = token))
        }
        Log.i(TAG, "Push token registration result: $result")
    }

    /** Detaches the device so a signed-out user stops receiving that account's alerts. */
    suspend fun unregister() {
        if (!firebaseReady()) return
        val token = currentToken() ?: return
        safeApiCall { api.unregisterDeviceToken(DeviceTokenRequest(userId = tokenStore.userId, token = token)) }
    }

    private companion object {
        const val TAG = "PushTokenRegistrar"
    }
}
