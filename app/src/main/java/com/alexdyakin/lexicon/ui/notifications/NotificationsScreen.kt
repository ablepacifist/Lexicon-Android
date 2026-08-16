package com.alexdyakin.lexicon.ui.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.AppNotification
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.NotificationApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.data.di.SseOkHttp
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import javax.inject.Inject

data class NotificationsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val unreadCount: Int = 0,
    val items: List<AppNotification> = emptyList(),
    val message: String? = null,
)

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val api: NotificationApi,
    private val tokenStore: TokenStore,
    @SseOkHttp private val sseClient: OkHttpClient,
    private val json: Json,
) : ViewModel() {
    private val _state = MutableStateFlow(NotificationsUiState())
    val state: StateFlow<NotificationsUiState> = _state.asStateFlow()

    private var eventSource: EventSource? = null

    init {
        refresh()
        connectStream()
    }

    private fun connectStream() {
        val request = Request.Builder().url("${ApiUrls.LEXICON}api/notifications/stream?userId=${tokenStore.userId}").build()
        eventSource = EventSources.createFactory(sseClient).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    "init" -> runCatching { json.parseToJsonElement(data).jsonObject["unreadCount"]?.jsonPrimitive?.content?.toInt() }.getOrNull()?.let { count -> _state.value = _state.value.copy(unreadCount = count) }
                    "notification" -> runCatching { json.decodeFromString<AppNotification>(data) }.getOrNull()?.let { notification ->
                        _state.value = _state.value.copy(items = listOf(notification) + _state.value.items.filter { it.id != notification.id }, unreadCount = _state.value.unreadCount + 1)
                    }
                }
            }
        })
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, message = null)
        val id = tokenStore.userId
        val history = safeApiCall { api.history(id) }
        val unread = safeApiCall { api.unreadCount(id) }
        _state.value = NotificationsUiState(
            loading = false,
            items = history.successOrNull.orEmpty(),
            unreadCount = unread.successOrNull?.count ?: 0,
            message = (history as? ApiResult.Failure)?.message ?: (unread as? ApiResult.Failure)?.message,
        )
    }

    fun markAllRead() = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true, message = null)
        when (val result = safeApiCall { api.markAllRead(tokenStore.userId) }) {
            is ApiResult.Success -> _state.value = _state.value.copy(saving = false, unreadCount = 0, message = "All notifications marked as read.")
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, message = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, message = "Sign in again to manage notifications.")
        }
    }

    override fun onCleared() {
        eventSource?.cancel()
        super.onCleared()
    }
}

@Composable
fun NotificationsScreen(onBack: () -> Unit, viewModel: NotificationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    ScreenScaffold("Notifications", onBack, R.drawable.bg_lexicon_room) { padding ->
        when {
            state.loading -> LoadingBox(padding)
            state.items.isEmpty() -> EmptyBox(padding, "No notifications yet")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${state.unreadCount} unread", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = viewModel::markAllRead, enabled = state.unreadCount > 0 && !state.saving) { Text("Mark all as read") }
                        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    }
                }
                items(state.items, key = { it.id }) { notification ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(notification.title.ifBlank { notification.type }, style = MaterialTheme.typography.titleMedium)
                            Text(notification.body)
                            val attribution = listOf(notification.fromUsername.takeIf { it.isNotBlank() }, notification.createdAt.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
                            if (attribution.isNotBlank()) Text(attribution, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
