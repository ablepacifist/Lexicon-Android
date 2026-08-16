package com.alexdyakin.lexicon.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.LevelUpRequest
import com.alexdyakin.lexicon.data.NotificationPrefs
import com.alexdyakin.lexicon.data.PlayerProfile
import com.alexdyakin.lexicon.data.PokemonPlayerStats
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.RemoveAvatarRequest
import com.alexdyakin.lexicon.data.api.AlchemyApi
import com.alexdyakin.lexicon.data.api.PokemonApi
import com.alexdyakin.lexicon.data.api.ProfileApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import coil.compose.AsyncImage
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

data class ProfileUiState(
    val loading: Boolean = true, val saving: Boolean = false, val lexicon: PlayerProfile? = null,
    val alchemy: PlayerProfile? = null, val pokemon: PokemonPlayerStats? = null,
    val preferences: NotificationPrefs? = null, val avatarUrl: String? = null, val message: String? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileApi: ProfileApi, private val alchemyApi: AlchemyApi, private val pokemonApi: PokemonApi,
    private val tokenStore: TokenStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    init { refresh() }
    fun showMessage(message: String) { _state.value = _state.value.copy(message = message) }
    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, message = null)
        val id = tokenStore.userId
        val lexicon = safeApiCall { profileApi.player(id) }.successOrNull
        val alchemy = safeApiCall { alchemyApi.player(id) }.successOrNull
        val pokemon = safeApiCall { pokemonApi.playerStats() }.successOrNull
        val prefs = safeApiCall { profileApi.notificationPrefs(id) }.successOrNull
        val avatarPath = lexicon?.username?.takeIf { it.isNotBlank() }?.let { safeApiCall { profileApi.avatar(it) }.successOrNull?.avatarUrl }
        _state.value = ProfileUiState(false, lexicon = lexicon, alchemy = alchemy, pokemon = pokemon, preferences = prefs, avatarUrl = avatarPath?.let(::proxiedAvatarUrl))
    }
    fun updatePrefs(next: NotificationPrefs) = viewModelScope.launch {
        _state.value = _state.value.copy(preferences = next, saving = true)
        when (val result = safeApiCall { profileApi.updateNotificationPrefs(tokenStore.userId, next) }) {
            is ApiResult.Success -> _state.value = _state.value.copy(saving = false, message = "Notification settings saved.")
            else -> _state.value = _state.value.copy(saving = false, message = "Could not save notification settings.")
        }
    }
    fun levelUp(secret: String) = viewModelScope.launch {
        if (secret.isBlank()) { _state.value = _state.value.copy(message = "Enter the secret password."); return@launch }
        _state.value = _state.value.copy(saving = true, message = null)
        when (val result = safeApiCall { alchemyApi.levelUp(LevelUpRequest(tokenStore.userId, secret)) }) {
            is ApiResult.Success -> _state.value = _state.value.copy(saving = false, alchemy = result.data, message = "Alchemy level increased.")
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, message = "Level-up was rejected.")
        }
    }
    fun uploadAvatar(bytes: ByteArray, filename: String, contentType: String?) = viewModelScope.launch {
        val user = _state.value.lexicon ?: return@launch
        _state.value = _state.value.copy(saving = true, message = null)
        val file = bytes.toRequestBody((contentType ?: "image/*").toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("avatar", filename, file)
        when (val result = safeApiCall { profileApi.uploadAvatar(user.username.toRequestBody(), user.id.toString().toRequestBody(), part) }) {
            is ApiResult.Success -> _state.value = _state.value.copy(saving = false, avatarUrl = proxiedAvatarUrl(result.data.avatarUrl), message = "Avatar updated.")
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, message = "Avatar upload was rejected.")
        }
    }
    fun removeAvatar() = viewModelScope.launch {
        val user = _state.value.lexicon ?: return@launch
        _state.value = _state.value.copy(saving = true, message = null)
        when (val result = safeApiCall { profileApi.removeAvatar(RemoveAvatarRequest(user.username, user.id)) }) {
            is ApiResult.Success -> _state.value = _state.value.copy(saving = false, avatarUrl = proxiedAvatarUrl(result.data.avatarUrl), message = "Avatar removed.")
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, message = "Avatar removal was rejected.")
        }
    }
}

private fun proxiedAvatarUrl(bridgePath: String): String {
    val filename = bridgePath.substringAfterLast('/').ifBlank { "default.jpg" }
    return "${ApiUrls.LEXICON}api/avatar/image/$filename?t=${System.currentTimeMillis()}"
}

@Composable
fun ProfileScreen(onBack: () -> Unit, viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var secret by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size > 2L * 1024 * 1024) {
            viewModel.showMessage("Choose an image no larger than 2 MB.")
            return@rememberLauncherForActivityResult
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null || bytes.size > 2 * 1024 * 1024) {
            viewModel.showMessage("Could not read that image, or it exceeds 2 MB.")
        } else {
            val filename = uri.lastPathSegment?.substringAfterLast('/') ?: "avatar.jpg"
            viewModel.uploadAvatar(bytes, filename, context.contentResolver.getType(uri))
        }
    }
    ScreenScaffold("Profile", onBack, R.drawable.bg_lexicon_room) { padding ->
        if (state.loading) { Column(Modifier.fillMaxSize().padding(padding), Arrangement.Center, Alignment.CenterHorizontally) { CircularProgressIndicator() }; return@ScreenScaffold }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(state.avatarUrl, "Profile avatar", Modifier.width(78.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.lexicon?.let { it.displayName.ifBlank { it.username } } ?: "Traveller", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text("@${state.lexicon?.username.orEmpty()}")
                    Text(state.lexicon?.email.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Text("Account ID: ${state.lexicon?.id ?: 0}", style = MaterialTheme.typography.labelSmall)
                }
            }; Row(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ picker.launch("image/*") }, enabled = !state.saving) { Text("Choose avatar") }
                Button(viewModel::removeAvatar, enabled = !state.saving) { Text("Remove") }
            } } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("⚗ Alchemy", "Level ${state.alchemy?.level ?: "—"}", Modifier.weight(1f))
                StatCard("⚡ PokéWorld", "Level ${state.pokemon?.level ?: "—"}", Modifier.weight(1f))
            } }
            state.pokemon?.let { poke -> item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("PokéWorld progress", style = MaterialTheme.typography.titleMedium)
                Text("${poke.totalCaught} caught · ${poke.coins} coins · ${poke.totalKm} km walked")
                Text("XP ${poke.xpProgress} / ${poke.xpRequired}${poke.team?.let { " · $it team" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            } } } }
            state.preferences?.let { prefs -> item { PreferencesCard(prefs, state.saving, viewModel::updatePrefs) } }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Alchemy level up", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(secret, { secret = it }, label = { Text("Secret password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { viewModel.levelUp(secret); secret = "" }, enabled = !state.saving) { Text("Level up") }
            } } }
            state.message?.let { message -> item { Text(message, color = if (message.contains("saved") || message.contains("increased")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable private fun StatCard(title: String, value: String, modifier: Modifier) = Card(modifier) { Column(Modifier.padding(16.dp)) { Text(title, style = MaterialTheme.typography.labelLarge); Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) } }
@Composable private fun PreferencesCard(prefs: NotificationPrefs, saving: Boolean, save: (NotificationPrefs) -> Unit) = Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
    Text("Notification settings", style = MaterialTheme.typography.titleMedium)
    PreferenceToggle("Text messages", prefs.enableMessage, saving) { save(prefs.copy(enableMessage = it)) }
    PreferenceToggle("Voice joins", prefs.enableVoiceJoin, saving) { save(prefs.copy(enableVoiceJoin = it)) }
    PreferenceToggle("Mentions", prefs.enableMention, saving) { save(prefs.copy(enableMention = it)) }
    PreferenceToggle("Now playing music", prefs.enableMusic, saving) { save(prefs.copy(enableMusic = it)) }
    PreferenceToggle("Push notifications", prefs.enablePush, saving) { save(prefs.copy(enablePush = it)) }
} }
@Composable private fun PreferenceToggle(label: String, checked: Boolean, disabled: Boolean, update: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, update, enabled = !disabled) }