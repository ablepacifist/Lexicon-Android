package com.alexdyakin.lexicon.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.data.LoginRequest
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.AuthApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onUsername(value: String) {
        _state.value = _state.value.copy(username = value, error = null)
    }

    fun onPassword(value: String) {
        _state.value = _state.value.copy(password = value, error = null)
    }

    fun login(onSuccess: () -> Unit) {
        val current = _state.value
        if (current.username.isBlank() || current.password.isBlank()) {
            _state.value = current.copy(error = "Enter a username and password.")
            return
        }

        _state.value = current.copy(loading = true, error = null)

        viewModelScope.launch {
            try {
                val response = authApi.login(
                    LoginRequest(username = current.username.trim(), password = current.password)
                )

                val token = response.mobileToken
                if (token.isNullOrEmpty()) {
                    // Login worked but no bearer token came back — almost always an older
                    // LexiconServer without the mobile branch.
                    _state.value = _state.value.copy(
                        loading = false,
                        error = "Server did not return a mobile token. Is LexiconServer up to date?",
                    )
                    return@launch
                }

                tokenStore.apply {
                    this.token = token
                    userId = response.id
                    username = response.username
                }

                _state.value = _state.value.copy(loading = false, password = "")
                onSuccess()

            } catch (e: HttpException) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = if (e.code() == 401) "Invalid username or password."
                            else "Login failed (HTTP ${e.code()}).",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "Could not reach the server. ${e.message ?: ""}".trim(),
                )
            }
        }
    }
}
