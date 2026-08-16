package com.alexdyakin.lexicon.data.di

import android.content.Context
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.AuthInterceptor
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.api.AlchemyApi
import com.alexdyakin.lexicon.data.api.AuthApi
import com.alexdyakin.lexicon.data.api.EventApi
import com.alexdyakin.lexicon.data.api.MediaApi
import com.alexdyakin.lexicon.data.api.PlaybackApi
import com.alexdyakin.lexicon.data.api.PlaylistApi
import com.alexdyakin.lexicon.data.api.ProfileApi
import com.alexdyakin.lexicon.data.api.LiveStreamApi
import com.alexdyakin.lexicon.data.api.PokemonApi
import com.alexdyakin.lexicon.data.api.NotificationApi
import com.alexdyakin.lexicon.data.api.AppUpdateApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.JavaNetCookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.net.CookieManager
import java.net.CookiePolicy
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class BaseOkHttp
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SseOkHttp
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class WsOkHttp
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class UploadOkHttp

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class LexiconRetrofit
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AlchemyRetrofit
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class PokemonRetrofit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true      // servers return more fields than we model
        coerceInputValues = true
        explicitNulls = false
        // Load-bearing: without this, kotlinx.serialization omits any property still
        // holding its default, which silently dropped platform="mobile" from the login
        // body and broke sign-in. Pinned by LoginRequestSerializationTest.
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun tokenStore(@ApplicationContext context: Context): TokenStore = TokenStore(context)

    /**
     * One base client so all four hosts share a connection pool and cache.
     * Every other client is derived from this with newBuilder().
     */
    @Provides
    @Singleton
    @BaseOkHttp
    fun baseOkHttp(
        @ApplicationContext context: Context,
        tokenStore: TokenStore,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        // MobileTokenAuthFilter calls getSession(true), so without a cookie jar every
        // bearer request mints a fresh server-side session. This matches the browser.
        val cookieJar = JavaNetCookieJar(
            CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }
        )
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .addInterceptor(logging)
            .cookieJar(cookieJar)
            .cache(Cache(context.cacheDir.resolve("http"), 50L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** SSE streams idle between heartbeats — a read timeout would kill them. */
    @Provides
    @Singleton
    @SseOkHttp
    fun sseOkHttp(@BaseOkHttp base: OkHttpClient): OkHttpClient =
        base.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /**
     * The browser gets WebSocket keepalive from the platform; Android does not, and
     * carrier NAT will silently drop an idle socket without pings.
     */
    @Provides
    @Singleton
    @WsOkHttp
    fun wsOkHttp(@BaseOkHttp base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

    /** Large uploads must not be cut off by write/call timeouts. */
    @Provides
    @Singleton
    @UploadOkHttp
    fun uploadOkHttp(@BaseOkHttp base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    private fun retrofit(baseUrl: String, client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides @Singleton @LexiconRetrofit
    fun lexiconRetrofit(@BaseOkHttp c: OkHttpClient, json: Json) = retrofit(ApiUrls.LEXICON, c, json)

    @Provides @Singleton @AlchemyRetrofit
    fun alchemyRetrofit(@BaseOkHttp c: OkHttpClient, json: Json) = retrofit(ApiUrls.ALCHEMY, c, json)

    @Provides @Singleton @PokemonRetrofit
    fun pokemonRetrofit(@BaseOkHttp c: OkHttpClient, json: Json) = retrofit(ApiUrls.POKEMON, c, json)

    @Provides @Singleton
    fun authApi(@LexiconRetrofit r: Retrofit): AuthApi = r.create(AuthApi::class.java)

    @Provides @Singleton
    fun mediaApi(@LexiconRetrofit r: Retrofit): MediaApi = r.create(MediaApi::class.java)

    @Provides @Singleton
    fun playlistApi(@LexiconRetrofit r: Retrofit): PlaylistApi = r.create(PlaylistApi::class.java)

    @Provides @Singleton
    fun playbackApi(@LexiconRetrofit r: Retrofit): PlaybackApi = r.create(PlaybackApi::class.java)

    @Provides @Singleton
    fun eventApi(@LexiconRetrofit r: Retrofit): EventApi = r.create(EventApi::class.java)

    @Provides @Singleton
    fun profileApi(@LexiconRetrofit r: Retrofit): ProfileApi = r.create(ProfileApi::class.java)

    @Provides @Singleton
    fun liveStreamApi(@LexiconRetrofit r: Retrofit): LiveStreamApi = r.create(LiveStreamApi::class.java)

    @Provides @Singleton
    fun notificationApi(@LexiconRetrofit r: Retrofit): NotificationApi = r.create(NotificationApi::class.java)

    @Provides @Singleton
    fun appUpdateApi(@LexiconRetrofit r: Retrofit): AppUpdateApi = r.create(AppUpdateApi::class.java)

    @Provides @Singleton
    fun alchemyApi(@AlchemyRetrofit r: Retrofit): AlchemyApi = r.create(AlchemyApi::class.java)

    @Provides @Singleton
    fun pokemonApi(@PokemonRetrofit r: Retrofit): PokemonApi = r.create(PokemonApi::class.java)
}
