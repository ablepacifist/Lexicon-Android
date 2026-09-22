package com.alexdyakin.lexicon.lexi

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.data.bridge.BridgeConnection
import com.alexdyakin.lexicon.data.bridge.BridgeCredentials
import com.alexdyakin.lexicon.voice.VoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * One real turn through the whole assistant: the real microphone opened, the real brain
 * over the tunnel, the real text-to-speech. The question is typed rather than spoken,
 * because an emulator has no one to talk to it.
 *
 * Opt-in, because it needs the gateway's agent token and a running brain, and it leaves a
 * chat behind on the brain (under the `local-default` account). Skipped unless a token is
 * passed:
 *
 *     adb shell am instrument -w -e class com.alexdyakin.lexicon.lexi.LexiLiveTurnTest \
 *         -e lexiToken <token> com.alexdyakin.lexicon.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The token is removed from the device again afterwards.
 */
@RunWith(AndroidJUnit4::class)
class LexiLiveTurnTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    private object SignedOut : BridgeCredentials {
        override val isSignedIn = false
        override suspend fun mintSsoToken(): String = error("not used")
    }

    @Test
    fun answersATypedQuestionOutLoud() = runBlocking {
        val token = InstrumentationRegistry.getArguments().getString("lexiToken")
        assumeTrue("pass -e lexiToken to run the live turn", !token.isNullOrBlank())

        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        val settings = LexiSettingsStore(app)
        assertTrue(settings.setAgentToken(token!!))

        val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        val assistant = LexiAssistant(
            context = app,
            settingsStore = settings,
            tokenStore = TokenStore(app),
            brain = BrainClient(http),
            speechModels = SpeechModelInstaller(
                http = http,
                modelDir = app.filesDir.resolve("lexi/vosk"),
                workDir = app.cacheDir.resolve("lexi-speech-model"),
            ),
            voiceCall = VoiceSession(BridgeConnection(http, SignedOut, Json)),
        )

        val seen = Collections.synchronizedList(ArrayList<String>())
        val watcher = CoroutineScope(Dispatchers.Default).launch {
            assistant.state.collect { seen += it.javaClass.simpleName }
        }

        try {
            assistant.start()
            withTimeout(90_000) {
                val ready = assistant.state.first { it == LexiState.Listening || it is LexiState.Failed }
                assertTrue("Lexi did not start: $ready", ready == LexiState.Listening)
            }

            assistant.askText("What day of the week is it? Answer in one short sentence.")
            val turn = withTimeout(180_000) { assistant.turns.first { it.isNotEmpty() }.first() }
            withTimeout(10_000) { assistant.state.first { it == LexiState.Listening } }

            Log.i(TAG, "heard=\"${turn.heard}\" reply=\"${turn.reply}\" failed=${turn.failed} states=$seen")
            assertFalse("the turn failed: ${turn.reply}", turn.failed)
            assertTrue(turn.reply.isNotBlank())
            assertTrue("never spoke: $seen", "Speaking" in seen)
        } finally {
            watcher.cancel()
            assistant.stop()
            settings.setAgentToken("")
        }
    }

    private companion object {
        const val TAG = "LexiLiveTurnTest"
    }
}
