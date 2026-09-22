package com.alexdyakin.lexicon.lexi

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.alexdyakin.lexicon.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything about Lexi a user can change. The agent token is deliberately absent: it is
 * read through [LexiSettingsStore.agentToken] only, so it cannot end up in a log line or
 * a crash report by way of this class's `toString`.
 */
data class LexiSettings(
    /** The user left Lexi switched on; the app resumes listening when it is next opened. */
    val enabled: Boolean = false,
    val hasAgentToken: Boolean = false,
    /** Whether the token can be stored at all. False on a device whose keystore is broken. */
    val secureStorageAvailable: Boolean = true,
    val tunnelUrl: String = DEFAULT_TUNNEL_URL,
    /** Tried after the tunnel. Defaults to alison's LAN address; only reachable when the
     *  phone is on the same network, which is rare, but a stored override always wins. */
    val lanUrl: String = DEFAULT_LAN_URL,
    val orchestrator: String = DEFAULT_ORCHESTRATOR,
    val wakeThreshold: Float = DEFAULT_WAKE_THRESHOLD,
) {
    val bases: List<String> get() = listOf(tunnelUrl, lanUrl).filter { it.isNotBlank() }

    companion object {
        // The same defaults as the Pi's config.example.toml. Derived from the one
        // destination registry via BuildConfig - see ApiUrls.kt and
        // LexiconAndroid/.env.example - rather than hard-coded, so no longer `const`.
        val DEFAULT_TUNNEL_URL: String = BuildConfig.PUBLIC_BRAIN_URL
        val DEFAULT_LAN_URL: String = "http://${BuildConfig.ALISON_LAN_IP}:${BuildConfig.BRAIN_LAN_PORT}"
        const val DEFAULT_ORCHESTRATOR = "qwen3.5-4b-claude-opus-reasoning-distilled-v2"
        const val DEFAULT_WAKE_THRESHOLD = 0.5f
    }
}

/**
 * Persists [LexiSettings], with the agent token encrypted at rest.
 *
 * Unlike [com.alexdyakin.lexicon.data.TokenStore], a broken keystore does not fall back to
 * plain storage here. The agent token opens the brain to anyone holding it, over the public
 * tunnel, so if it cannot be stored encrypted it is not stored. Lexi then reports that it
 * has no token rather than quietly keeping a secret in plaintext.
 */
@Singleton
class LexiSettingsStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val secrets: SharedPreferences? = try {
        EncryptedSharedPreferences.create(
            context,
            SECRETS_FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        null
    }

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<LexiSettings> = _settings.asStateFlow()

    /** The gateway's shared secret, or null if none has been saved. */
    fun agentToken(): String? = secrets?.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** Saves [token], or removes it when blank. Returns false if it could not be stored securely. */
    fun setAgentToken(token: String): Boolean {
        val store = secrets ?: return false
        store.edit().apply {
            if (token.isBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, token.trim())
        }.apply()
        _settings.value = read()
        return true
    }

    fun setEnabled(enabled: Boolean) = edit { putBoolean(KEY_ENABLED, enabled) }

    fun setWakeThreshold(threshold: Float) =
        edit { putFloat(KEY_THRESHOLD, threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)) }

    fun setBrain(tunnelUrl: String, lanUrl: String, orchestrator: String) = edit {
        putString(KEY_TUNNEL, tunnelUrl.trim().ifEmpty { LexiSettings.DEFAULT_TUNNEL_URL })
        putString(KEY_LAN, lanUrl.trim())
        putString(KEY_ORCHESTRATOR, orchestrator.trim().ifEmpty { LexiSettings.DEFAULT_ORCHESTRATOR })
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _settings.value = read()
    }

    private fun read() = LexiSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        hasAgentToken = agentToken() != null,
        secureStorageAvailable = secrets != null,
        tunnelUrl = prefs.getString(KEY_TUNNEL, null) ?: LexiSettings.DEFAULT_TUNNEL_URL,
        lanUrl = prefs.getString(KEY_LAN, null) ?: LexiSettings.DEFAULT_LAN_URL,
        orchestrator = prefs.getString(KEY_ORCHESTRATOR, null) ?: LexiSettings.DEFAULT_ORCHESTRATOR,
        wakeThreshold = prefs.getFloat(KEY_THRESHOLD, LexiSettings.DEFAULT_WAKE_THRESHOLD),
    )

    companion object {
        const val MIN_THRESHOLD = 0.2f
        const val MAX_THRESHOLD = 0.9f

        private const val PREFS_FILE = "lexi_settings"
        private const val SECRETS_FILE = "lexi_secrets"
        private const val KEY_TOKEN = "agent_token"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TUNNEL = "tunnel_url"
        private const val KEY_LAN = "lan_url"
        private const val KEY_ORCHESTRATOR = "orchestrator"
        private const val KEY_THRESHOLD = "wake_threshold"
    }
}
