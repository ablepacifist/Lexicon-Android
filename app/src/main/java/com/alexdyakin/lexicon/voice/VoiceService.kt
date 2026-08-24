package com.alexdyakin.lexicon.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.alexdyakin.lexicon.MainActivity
import com.alexdyakin.lexicon.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps voice alive while the app is not on screen.
 *
 * Android silences microphone capture within seconds of an app losing foreground unless
 * a service of type `microphone` is running, so this exists purely to hold that claim —
 * the audio itself lives in [VoiceSession], which is a singleton shared with the UI.
 * The service therefore owns no audio state and can be started and stopped freely.
 *
 * On API 34+ both the `FOREGROUND_SERVICE_MICROPHONE` permission and the matching
 * `foregroundServiceType` in the manifest are mandatory; without either, the start call
 * throws rather than degrading.
 */
@AndroidEntryPoint
class VoiceService : Service() {

    @Inject
    lateinit var session: VoiceSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(notification("Connecting to voice…"))

        // The session is the source of truth; the service simply follows it, and stops
        // itself when voice ends by any route — including the server hanging up.
        watcher = scope.launch {
            session.state.collectLatest { state ->
                when (state) {
                    is VoiceState.Active -> notify(
                        if (state.muted) "Muted" else "Voice connected",
                    )

                    is VoiceState.Connecting -> notify("Connecting to voice…")

                    is VoiceState.Idle, is VoiceState.Failed -> stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Not sticky: a restart with no session would hold the microphone claim for a
        // call that is no longer happening.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lexicon voice")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Voice",
                // Low: an ongoing call indicator should not buzz or make noise.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    companion object {
        private const val CHANNEL_ID = "voice_session"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoiceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
