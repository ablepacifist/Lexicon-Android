package com.alexdyakin.lexicon.ui.login

import kotlinx.coroutines.CancellationException
import android.util.Log
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
    /** "Account created" after registering; it went straight back here with no word before. */
    val notice: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** Registration succeeded: fill in the new username and say so. */
    fun accountCreated(username: String) {
        _state.value = LoginUiState(username = username, notice = "Account created. Sign in with your new password.")
    }

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

        _state.value = current.copy(loading = true, error = null, notice = null)

        viewModelScope.launch {
            try {
                val response = authApi.login(
                    LoginRequest(username = current.username.trim(), password = current.password)
                )

                val token = response.mobileToken
                if (token.isNullOrEmpty()) {
                    // Login worked but no bearer token came back — almost always an older
                    // LexiconServer without the mobile branch.
                    Log.w("Lexicon", "Login succeeded without a mobileToken; is LexiconServer up to date?")
                    _state.value = _state.value.copy(
                        loading = false,
                        error = "Signed in, but the server didn't start a session for the app. Please try again later.",
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

            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = when (e.code()) {
                        400, 401 -> "Wrong username or password."
                        429 -> "Too many attempts. Wait a minute, then try again."
                        else -> "The server had a problem signing you in. Please try again in a moment."
                    },
                )
            } catch (e: Exception) {
                Log.w("Lexicon", "Login failed", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = "Couldn't reach the server. Check your connection and try again.",
                )
            }
        }
    }
}
