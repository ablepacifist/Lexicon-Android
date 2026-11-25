package com.alexdyakin.lexicon.ui.notifications

import com.alexdyakin.lexicon.ui.components.ErrorBox
import com.alexdyakin.lexicon.data.parseTimestampMillis
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
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
import com.alexdyakin.lexicon.data.NotificationRepository
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import kotlinx.coroutines.awaitCancellation
import com.alexdyakin.lexicon.ui.components.LaunchedWhileResumed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val unreadCount: Int = 0,
    val items: List<AppNotification> = emptyList(),
    val message: String? = null,
    /** Why the list could not be loaded; shown instead of "No notifications yet". */
    val loadError: String? = null,
)

/**
 * Thin wrapper over [NotificationRepository]. Every screen that shows notification
 * state - the home badge included - reads the same singleton feed, so marking
 * everything read clears the badge everywhere at once.
 */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: NotificationRepository,
) : ViewModel() {
    private val saving = MutableStateFlow(false)
    private val localMessage = MutableStateFlow<String?>(null)

    val state: StateFlow<NotificationsUiState> =
        combine(repository.feed, saving, localMessage) { feed, isSaving, message ->
            NotificationsUiState(
                loading = feed.loading,
                saving = isSaving,
                unreadCount = feed.unreadCount,
                items = feed.items,
                message = message ?: feed.message,
                loadError = feed.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationsUiState())

    /**
     * Driven by the screen rather than by `init`, so the live stream is open only while a
     * screen that shows notifications is actually in front of the user. It used to start in
     * `init` on the app's start destination and never stop.
     */
    fun startStream() = repository.start()

    fun stopStream() = repository.stop()

    fun refresh() = repository.refresh()

    fun markAllRead() = viewModelScope.launch {
        saving.value = true
        localMessage.value = null
        localMessage.value = repository.markAllRead()
        saving.value = false
    }
}

@Composable
fun NotificationsScreen(onBack: () -> Unit, viewModel: NotificationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    LaunchedWhileResumed {
        try {
            viewModel.startStream()
            awaitCancellation()
        } finally {
            viewModel.stopStream()
        }
    }

    ScreenScaffold("Notifications", onBack) { padding ->
        when {
            state.loading && state.items.isEmpty() -> LoadingBox(padding)
            // A failed load used to read as an empty inbox.
            state.items.isEmpty() && state.loadError != null -> ErrorBox(state.loadError!!, viewModel::refresh, padding)
            state.items.isEmpty() -> EmptyBox(padding, "No notifications yet")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier.animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // No colour was set, so on the dark ground it took the default dark text and vanished.
                        Text("${state.unreadCount} unread", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                        // The button collapses away once everything is read rather
                        // than sitting there greyed out.
                        AnimatedVisibility(
                            visible = state.unreadCount > 0,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Button(onClick = viewModel::markAllRead, enabled = !state.saving) {
                                Text("Mark all as read")
                            }
                        }
                        AnimatedVisibility(
                            visible = state.message != null,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Text(state.message.orEmpty(), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                items(state.items, key = { it.id }) { notification ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            // New arrivals slide down into place instead of popping in.
                            .animateItem(
                                placementSpec = tween(300),
                                fadeInSpec = tween(300),
                                fadeOutSpec = tween(200),
                            ),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(notification.title.ifBlank { notification.type }, style = MaterialTheme.typography.titleMedium)
                            Text(notification.body)
                            val attribution = listOf(notification.fromUsername.takeIf { it.isNotBlank() }, relativeTime(notification.createdAt)).filterNotNull().joinToString(" · ")
                            if (attribution.isNotBlank()) Text(attribution, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/** "5 min. ago", "Yesterday"; the raw `2026-11-24T19:03:11.204` was shown before. */
private fun relativeTime(raw: String): String? {
    val millis = parseTimestampMillis(raw).takeIf { it > 0 } ?: return null
    return DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}
