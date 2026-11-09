package com.alexdyakin.lexicon.data.di

import android.content.Context
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.lexi.BrainClient
import com.alexdyakin.lexicon.lexi.LiveStreamFollower
import com.alexdyakin.lexicon.lexi.LiveStreamFollowing
import com.alexdyakin.lexicon.lexi.LexiMedia
import com.alexdyakin.lexicon.lexi.LexiVoicePlayback
import com.alexdyakin.lexicon.lexi.LexiconVoiceLibrary
import com.alexdyakin.lexicon.lexi.SpeechModelInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LexiModule {

    /**
     * The same timeouts as the Pi's client: a short connect so an unreachable base fails
     * over quickly, and a long read so a slow model has room between tokens. The brain
     * sends a keepalive every 10 s, so a healthy stream never gets near the limit.
     */
    @Provides
    @Singleton
    fun brainClient(@BaseOkHttp base: OkHttpClient, json: Json): BrainClient = BrainClient(
        base.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build(),
        json,
    )

    /**
     * The speech model lands in app-private files, not the cache, which Android is free to
     * clear; the archive is unpacked in the cache, which is exactly what it is for.
     *
     * The shared HTTP cache is turned off for this client: a 41 MB download would evict
     * everything else in it for a file that is only ever fetched once.
     */
    @Provides
    @Singleton
    fun speechModelInstaller(
        @ApplicationContext context: Context,
        @BaseOkHttp base: OkHttpClient,
    ): SpeechModelInstaller = SpeechModelInstaller(
        http = base.newBuilder()
            .cache(null)
            .readTimeout(60, TimeUnit.SECONDS)
            .build(),
        modelDir = context.filesDir.resolve("lexi/vosk"),
        workDir = context.cacheDir.resolve("lexi-speech-model"),
    )

    /**
     * The spoken media commands, over the app's own library APIs and background player, so a
     * voice request behaves exactly like tapping play.
     */
    @Provides
    @Singleton
    fun lexiMedia(
        library: LexiconVoiceLibrary,
        playback: LexiVoicePlayback,
        liveStream: LiveStreamFollowing,
    ): LexiMedia = LexiMedia(library, playback, liveStream)

    /**
     * Follows the communal stream over the same SSE feed the live stream screen uses, on the
     * client built for streams (no read timeout, so an idle feed is not killed).
     */
    @Provides
    @Singleton
    fun liveStreamFollowing(
        @SseOkHttp sse: OkHttpClient,
        json: Json,
        library: LexiconVoiceLibrary,
    ): LiveStreamFollowing = LiveStreamFollower(
        client = sse,
        json = json,
        updatesUrl = ApiUrls.LEXICON + "api/livestream/updates?channel=music",
        resolveTrack = { id -> library.mediaById(id) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}
