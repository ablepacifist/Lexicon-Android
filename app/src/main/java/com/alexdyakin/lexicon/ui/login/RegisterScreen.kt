package com.alexdyakin.lexicon.ui.login

import androidx.compose.foundation.layout.Arrangement
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
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.RegisterRequest
import com.alexdyakin.lexicon.data.api.AuthApi
import com.alexdyakin.lexicon.data.safeApiCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RegisterUiState(
    val username: String = "",
    val email: String = "",
    val displayName: String = "",
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

    fun register(onRegistered: () -> Unit) {
        val current = _state.value
        when {
            current.username.trim().length < 3 -> _state.value = current.copy(error = "Username must be at least 3 characters.")
            current.password.length < 6 -> _state.value = current.copy(error = "Password must be at least 6 characters.")
            current.password != current.confirmPassword -> _state.value = current.copy(error = "Passwords do not match.")
            else -> viewModelScope.launch {
                _state.value = current.copy(loading = true, error = null)
                when (val result = safeApiCall {
                    authApi.register(RegisterRequest(
                        username = current.username.trim(), password = current.password,
                        confirmPassword = current.confirmPassword, email = current.email.trim(),
                        displayName = current.displayName.trim(),
                    ))
                }) {
                    is ApiResult.Success -> onRegistered()
                    is ApiResult.Failure -> _state.value = current.copy(loading = false, error = result.message)
                    ApiResult.Unauthorized -> _state.value = current.copy(loading = false, error = "Registration is unavailable right now.")
                }
            }
        }
    }
}

@Composable
fun RegisterScreen(onBackToLogin: () -> Unit, viewModel: RegisterViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Create account", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("Create one account for Lexicon, Alchemy, Pokémon and Voice.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(state.username, { viewModel.update { s -> s.copy(username = it) } }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.loading)
                OutlinedTextField(state.displayName, { viewModel.update { s -> s.copy(displayName = it) } }, label = { Text("Display name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.loading)
                OutlinedTextField(state.email, { viewModel.update { s -> s.copy(email = it) } }, label = { Text("Email (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.loading)
                OutlinedTextField(state.password, { viewModel.update { s -> s.copy(password = it) } }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !state.loading)
                OutlinedTextField(state.confirmPassword, { viewModel.update { s -> s.copy(confirmPassword = it) } }, label = { Text("Confirm password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !state.loading)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { viewModel.register(onBackToLogin) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                    if (state.loading) CircularProgressIndicator() else Text("Create account")
                }
                OutlinedButton(onClick = onBackToLogin, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("I already have an account") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}