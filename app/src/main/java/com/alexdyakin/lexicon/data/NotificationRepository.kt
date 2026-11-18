package com.alexdyakin.lexicon.data

import com.alexdyakin.lexicon.data.api.NotificationApi
import com.alexdyakin.lexicon.data.di.SseOkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import javax.inject.Inject
import javax.inject.Singleton

data class NotificationFeed(
    val loading: Boolean = true,
    val unreadCount: Int = 0,
    val items: List<AppNotification> = emptyList(),
    val message: String? = null,
)

/**
 * Single source of truth for the notification feed.
 *
 * The home badge and the notifications screen previously each built their own
 * [androidx.lifecycle.ViewModel] via `hiltViewModel()`, which Hilt scopes to the
 * nav back stack entry. That produced two independent copies of the unread count
 * and two SSE connections, so marking everything read on one screen left the
 * badge on the other untouched. Both now observe this singleton instead.
 */
@Singleton
class NotificationRepository @Inject constructor(
    private val api: NotificationApi,
    private val tokenStore: TokenStore,
    @SseOkHttp private val sseClient: OkHttpClient,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _feed = MutableStateFlow(NotificationFeed())
    val feed: StateFlow<NotificationFeed> = _feed.asStateFlow()

    private var eventSource: EventSource? = null
    private var streamUserId: Int? = null

    /** Safe to call from every observer; only the first call opens the stream. */
    fun start() {
        refresh()
        val userId = tokenStore.userId
        if (eventSource != null && streamUserId == userId) return
        connectStream(userId)
    }

    /**
     * Closes the live stream. There was no way to do this before: `start()` was called from the
     * Home screen's badge view model — the app's start destination — and the `EventSource` it
     * opened had no `cancel()` caller anywhere, so it stayed open for the life of the process.
     *
     * Safe to stop while backgrounded: Web Push (FCM) is what delivers notifications when the
     * app is not in front of the user, and `start()` refreshes the feed when they return.
     */
    fun stop() {
        eventSource?.cancel()
        eventSource = null
        streamUserId = null
    }

    private fun connectStream(userId: Int) {
        eventSource?.cancel()
        streamUserId = userId
        val request = Request.Builder()
            .url("${ApiUrls.LEXICON}api/notifications/stream?userId=$userId")
            .build()
        eventSource = EventSources.createFactory(sseClient).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    when (type) {
                        "init" -> runCatching {
                            json.parseToJsonElement(data).jsonObject["unreadCount"]
                                ?.jsonPrimitive?.content?.toInt()
                        }.getOrNull()?.let { count ->
                            _feed.update { it.copy(unreadCount = count) }
                        }

                        "notification" -> runCatching {
                            json.decodeFromString<AppNotification>(data)
                        }.getOrNull()?.let { notification ->
                            _feed.update { current ->
                                current.copy(
                                    items = listOf(notification) +
                                        current.items.filter { it.id != notification.id },
                                    unreadCount = current.unreadCount + 1,
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    fun refresh() {
        scope.launch {
            _feed.update { it.copy(loading = true, message = null) }
            val userId = tokenStore.userId
            // Independent calls, so they go together. There is no `async` anywhere else in
            // this app either — every multi-call refresh pays its round trips in series.
            val (history, unread) = coroutineScope {
                val h = async { safeApiCall { api.history(userId) } }
                val u = async { safeApiCall { api.unreadCount(userId) } }
                h.await() to u.await()
            }
            _feed.update {
                it.copy(
                    loading = false,
                    items = history.successOrNull.orEmpty(),
                    unreadCount = unread.successOrNull?.count ?: 0,
                    message = (history as? ApiResult.Failure)?.message
                        ?: (unread as? ApiResult.Failure)?.message,
                )
            }
        }
    }

    /** Clears the badge for every observer, not just the calling screen. */
    suspend fun markAllRead(): String? = when (val result = safeApiCall { api.markAllRead(tokenStore.userId) }) {
        is ApiResult.Success -> {
            _feed.update { it.copy(unreadCount = 0) }
            "All notifications marked as read."
        }

        is ApiResult.Failure -> result.message
        ApiResult.Unauthorized -> "Sign in again to manage notifications."
    }

    /** Called when a push arrives so the badge updates without waiting for a refresh. */
    fun onPushReceived(notification: AppNotification?) {
        if (notification == null) {
            _feed.update { it.copy(unreadCount = it.unreadCount + 1) }
            return
        }
        _feed.update { current ->
            if (current.items.any { it.id == notification.id }) current
            else current.copy(
                items = listOf(notification) + current.items,
                unreadCount = current.unreadCount + 1,
            )
        }
    }
}
