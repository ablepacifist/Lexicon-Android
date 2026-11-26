package com.alexdyakin.lexicon.ui.login

import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.alexdyakin.lexicon.data.RegisterRequest
import com.alexdyakin.lexicon.data.api.AuthApi
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RegisterUiState(
    val username: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class RegisterViewModel @Inject constructor(private val authApi: AuthApi) : ViewModel() {
    private val _state = MutableStateFlow(RegisterUiState())
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    fun update(transform: (RegisterUiState) -> RegisterUiState) { _state.value = transform(_state.value).copy(error = null) }

    fun register(onRegistered: (username: String) -> Unit) {
        val current = _state.value
        when {
            current.username.trim().length < 3 -> _state.value = current.copy(error = "Username must be at least 3 characters.")
            current.password.length < 6 -> _state.value = current.copy(error = "Password must be at least 6 characters.")
            current.password != current.confirmPassword -> _state.value = current.copy(error = "Passwords do not match.")
            else -> viewModelScope.launch {
                _state.value = current.copy(loading = true, error = null)
                val error = try {
                    authApi.register(RegisterRequest(
                        username = current.username.trim(), password = current.password,
                        confirmPassword = current.confirmPassword,
                    ))
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpException) {
                    refusal(e)
                } catch (e: IOException) {
                    "Can't reach the server. Check your connection."
                } catch (e: Exception) {
                    Log.w("Lexicon", "Registration failed", e)
                    "Couldn't create the account. Please try again."
                }
                if (error == null) onRegistered(current.username.trim()) else _state.value = current.copy(loading = false, error = error)
            }
        }
    }

    /**
     * The server refuses with a plain sentence ("Username already exists"), which
     * safeApiCall flattened to "Server error (HTTP 400)", so the player never learned why.
     * 5xx bodies carry exception text, so those stay generic.
     */
    private fun refusal(e: HttpException): String {
        if (e.code() in 400..499) {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()?.trim()
            if (!body.isNullOrBlank() && body.length <= 200 && body[0] != '<' && body[0] != '{') return body
        }
        return "Couldn't create the account right now. Please try again later."
    }
}

@Composable
fun RegisterScreen(onBackToLogin: () -> Unit, onRegistered: (username: String) -> Unit, viewModel: RegisterViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    // Until now this was the only screen in the app with no background at all — a flat surface
    // one tap from Login's full-bleed art, which made the two look like different apps.
    Box(Modifier.fillMaxSize()) {
        AuthBackground()
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Create account", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("Create one account for Lexicon, Alchemy, Pokémon and Voice.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(state.username, { viewModel.update { s -> s.copy(username = it) } }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.loading)
                OutlinedTextField(state.password, { viewModel.update { s -> s.copy(password = it) } }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(), enabled = !state.loading)
                OutlinedTextField(state.confirmPassword, { viewModel.update { s -> s.copy(confirmPassword = it) } }, label = { Text("Confirm password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(), enabled = !state.loading)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { viewModel.register(onRegistered) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                    if (state.loading) CircularProgressIndicator() else Text("Create account")
                }
                OutlinedButton(onClick = onBackToLogin, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("I already have an account") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}