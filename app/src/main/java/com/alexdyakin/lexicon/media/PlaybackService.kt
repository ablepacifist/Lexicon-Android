package com.alexdyakin.lexicon.media

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.alexdyakin.lexicon.data.di.BaseOkHttp
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Owns the single ExoPlayer instance and publishes it as a MediaSession.
 *
 * This is the reason the app is native: a MediaSession gives lock-screen controls,
 * the playback notification, Bluetooth/headset buttons and Android Auto for free, and
 * keeps playing when the app is backgrounded — none of which a browser tab can do.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject @BaseOkHttp lateinit var okHttpClient: OkHttpClient

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // Reuse the app's OkHttp client so streaming requests carry the bearer token
        // and share the connection pool. The server already supports range requests
        // (Accept-Ranges / Content-Range), so seeking works natively.
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)   // pause when headphones unplug
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Swiping the app away while paused should not leave a dead notification
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
