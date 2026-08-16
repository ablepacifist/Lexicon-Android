package com.alexdyakin.lexicon.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the long-lived bearer token issued by LexiconServer at login.
 *
 * Backed by EncryptedSharedPreferences (AndroidX Security), so the credential is
 * encrypted at rest with a Keystore-held key rather than sitting in plain
 * WebView localStorage the way the old shell left it.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // A corrupt keystore entry would otherwise brick login permanently.
        // Fall back to plain prefs rather than refusing to start.
        context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, value)
            }.apply()
        }

    var userId: Int
        get() = prefs.getInt(KEY_USER_ID, -1)
        set(value) = prefs.edit().putInt(KEY_USER_ID, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    val isLoggedIn: Boolean get() = !token.isNullOrEmpty() && userId > 0

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val ENCRYPTED_FILE = "lexicon_auth"
        const val FALLBACK_FILE = "lexicon_auth_plain"
        const val KEY_TOKEN = "mobile_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
    }
}
