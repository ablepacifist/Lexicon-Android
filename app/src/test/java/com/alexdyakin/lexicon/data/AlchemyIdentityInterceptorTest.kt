package com.alexdyakin.lexicon.data

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AlchemyIdentityInterceptorTest {
    private val lexicon = MockWebServer()
    private val alchemy = MockWebServer()
    private lateinit var client: OkHttpClient

    @Before
    fun start() {
        lexicon.start()
        alchemy.start()
        val plain = OkHttpClient()
        client = plain.newBuilder()
            .addInterceptor(AlchemyIdentityInterceptor(plain, Json { ignoreUnknownKeys = true }, lexicon.url("/").toString()))
            .build()
    }

    @After
    fun stop() {
        lexicon.shutdown()
        alchemy.shutdown()
    }

    private fun get(path: String, header: String? = null) {
        val builder = Request.Builder().url(alchemy.url(path))
        if (header != null) builder.header(AlchemyIdentityInterceptor.SSO_HEADER, header)
        alchemy.enqueue(MockResponse().setBody("{}"))
        client.newCall(builder.build()).execute().close()
    }

    @Test
    fun aPlayerScopedRequestCarriesAFreshToken() {
        lexicon.enqueue(MockResponse().setBody("""{"token":"abc123","expiresInSeconds":60}"""))
        get("/api/player/inventory/7")
        val mint = lexicon.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", mint.method)
        assertEquals("/api/auth/sso/generate-token", mint.path)
        assertEquals("abc123", alchemy.takeRequest().getHeader("X-Lexicon-Sso"))
    }

    @Test
    fun brewAndForageAreCoveredToo() {
        listOf("/api/potion/brew", "/api/game/forage/7", "/api/player/knowledge/7").forEach { path ->
            lexicon.enqueue(MockResponse().setBody("""{"token":"t"}"""))
            get(path)
            assertEquals(path, "t", alchemy.takeRequest().getHeader("X-Lexicon-Sso"))
        }
    }

    @Test
    fun otherRoutesMintNothing() {
        get("/api/ingredients")
        assertNull(alchemy.takeRequest().getHeader("X-Lexicon-Sso"))
        assertEquals(0, lexicon.requestCount)
    }

    @Test
    fun aTokenTheCallerAlreadyAddedIsKept() {
        get("/api/player/inventory/7", header = "holdfast-own")
        assertEquals("holdfast-own", alchemy.takeRequest().getHeader("X-Lexicon-Sso"))
        assertEquals(0, lexicon.requestCount)
    }

    @Test
    fun whenNoTokenCanBeMintedTheRequestStillGoes() {
        lexicon.enqueue(MockResponse().setResponseCode(401))
        get("/api/player/inventory/7")
        assertNull(alchemy.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("X-Lexicon-Sso"))
    }
}
