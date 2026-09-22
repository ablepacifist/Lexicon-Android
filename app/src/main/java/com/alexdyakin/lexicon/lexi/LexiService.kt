package com.alexdyakin.lexicon.lexi

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.alexdyakin.lexicon.MainActivity
import com.alexdyakin.lexicon.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps Lexi listening while the phone is in a pocket.
 *
 * Android cuts a background app off from the microphone within seconds unless a foreground
 * service of type `microphone` is running, so this exists to hold that claim and show the
 * notification that comes with it. The loop itself lives in [LexiAssistant].
 *
 * Two platform rules shape how it starts, and both mean Lexi resumes when the app is next
 * opened rather than on its own:
 *
 *  - A microphone foreground service may not be started from the background. Android 14
 *    refuses one launched from `BOOT_COMPLETED`, so there is no start-on-boot.
 *  - For the same reason the service is not sticky: a system restart after the process
 *    was killed happens in the background, where the microphone is unavailable, and would
 *    only produce a notification for a listener that cannot hear.
 */
@AndroidEntryPoint
class LexiService : Service() {

    @Inject lateinit var assistant: LexiAssistant
    @Inject lateinit var settings: LexiSettingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (!startForegroundCompat(notification(LexiState.Starting("Starting…")))) {
            stopSelf()
            return
        }

        assistant.start()
        watcher = scope.launch {
            assistant.state.collect { state ->
                when (state) {
                    // The assistant keeps a failure on screen for the user to read; the
                    // service has nothing left to hold the microphone for.
                    is LexiState.Off, is LexiState.Failed -> stopSelf()
                    else -> notify(state)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ASK -> assistant.askNow()
            ACTION_TURN_OFF -> {
                settings.setEnabled(false)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        // Leave a failure in place so the screen can explain it.
        if (assistant.state.value !is LexiState.Failed) assistant.stop()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        // Missing microphone permission, or an attempt to start from the background.
        Log.w(TAG, "Could not start in the foreground", e)
        false
    }

    private fun notify(state: LexiState) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(state))
    }

    private fun notification(state: LexiState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "lexi"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lexi")
            .setContentText(describe(state))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Ask", action(ACTION_ASK))
            .addAction(0, "Turn off", action(ACTION_TURN_OFF))
            .build()
    }

    private fun action(name: String) = PendingIntent.getService(
        this,
        name.hashCode(),
        Intent(this, LexiService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Lexi", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
    }

    companion object {
        private const val TAG = "LexiService"
        private const val CHANNEL_ID = "lexi_assistant"
        private const val NOTIFICATION_ID = 4202
        private const val ACTION_ASK = "com.alexdyakin.lexicon.lexi.ASK"
        private const val ACTION_TURN_OFF = "com.alexdyakin.lexicon.lexi.TURN_OFF"

        /** Must be called while the app is in the foreground; see the class notes. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LexiService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LexiService::class.java))
        }

        fun describe(state: LexiState): String = when (state) {
            LexiState.Off -> "Off"
            is LexiState.Starting -> state.detail
            LexiState.Listening -> "Listening for “Hey Jarvis”"
            LexiState.Capturing -> "Listening to you…"
            is LexiState.Thinking -> "Thinking…"
            is LexiState.Speaking -> "Speaking…"
            LexiState.PausedForCall -> "Paused while you're in a voice call"
            is LexiState.Failed -> state.reason
        }
    }
}
