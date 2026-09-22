package com.alexdyakin.lexicon.data.di

import android.content.Context
import com.alexdyakin.lexicon.lexi.BrainClient
import com.alexdyakin.lexicon.lexi.SpeechModelInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
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
}
