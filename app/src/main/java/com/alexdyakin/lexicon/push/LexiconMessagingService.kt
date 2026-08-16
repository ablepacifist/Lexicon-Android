package com.alexdyakin.lexicon.push

import com.alexdyakin.lexicon.data.AppNotification
import com.alexdyakin.lexicon.data.NotificationRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives pushes from FCM.
 *
 * The backend should send **data** messages rather than `notification` ones: a
 * `notification` payload is rendered by the system only while the app is
 * backgrounded and never reaches [onMessageReceived] in the foreground, which would
 * leave the in-app badge out of sync. A data message always lands here.
 *
 * Expected payload keys: `id`, `type`, `title`, `body`, `fromUsername`, `link`.
 */
@AndroidEntryPoint
class LexiconMessagingService : FirebaseMessagingService() {

    @Inject lateinit var notifier: LexiconNotifier
    @Inject lateinit var repository: NotificationRepository
    @Inject lateinit var pushTokenRegistrar: PushTokenRegistrar

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        // Fires on install, app data clear, and periodic token rotation.
        scope.launch { pushTokenRegistrar.register(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"].orEmpty().ifBlank { message.notification?.title.orEmpty() }
        val body = data["body"].orEmpty().ifBlank { message.notification?.body.orEmpty() }
        val link = data["link"]
        val id = data["id"]?.toLongOrNull()

        notifier.show(
            id = (id ?: System.currentTimeMillis()).toInt(),
            title = title,
            body = body,
            link = link,
        )

        // Keep the in-app badge and feed consistent with what was just shown.
        repository.onPushReceived(
            id?.let {
                AppNotification(
                    id = it,
                    type = data["type"].orEmpty(),
                    title = title,
                    body = body,
                    fromUsername = data["fromUsername"].orEmpty(),
                    link = link.orEmpty(),
                )
            },
        )
    }
}
