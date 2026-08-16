package com.alexdyakin.lexicon.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.alexdyakin.lexicon.MainActivity
import com.alexdyakin.lexicon.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Posts Lexicon alerts into the system notification shade. */
@Singleton
class LexiconNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val CHANNEL_ID = "lexicon_alerts"
        private const val CHANNEL_NAME = "Lexicon alerts"
        private const val CHANNEL_DESCRIPTION = "Messages, events and updates from Lexicon"
    }

    /**
     * Channels must exist before the first notification is posted, and creating one
     * that already exists is a no-op, so this is safe to call repeatedly.
     */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = CHANNEL_DESCRIPTION
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** True when the OS will actually display what we post. */
    fun canPost(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun show(id: Int, title: String, body: String, link: String?) {
        if (!canPost()) return
        ensureChannel()

        // Deep link target is carried through so tapping opens the right screen.
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!link.isNullOrBlank()) putExtra(EXTRA_LINK, link)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "Lexicon" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}

const val EXTRA_LINK = "lexicon_link"
