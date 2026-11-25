package com.alexdyakin.lexicon.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AttemptTest {

    private fun http(code: Int, body: String) =
        HttpException(Response.error<Any>(code, body.toResponseBody("text/plain".toMediaTypeOrNull())))

    /** The map's refresh: parallel calls where one fails, inside a launched coroutine. */
    @Test
    fun aFailingAsyncChildIsAFailureNotACrash() = runTest {
        var outcome: Result<Int>? = null
        val job = launch {
            outcome = attempt {
                val ok = async { 1 }
                val broken = async<Int> { throw IOException("network down") }
                ok.await() + broken.await()
            }
        }
        advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertTrue(outcome!!.exceptionOrNull() is IOException)
    }

    @Test
    fun cancellationIsNotSwallowed() = runTest {
        val started = CompletableDeferred<Unit>()
        var reachedAfter = false
        val job = launch {
            attempt {
                started.complete(Unit)
                awaitCancellation()
            }
            reachedAfter = true
        }
        started.await()
        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertFalse("work carried on after the screen was left", reachedAfter)
    }

    @Test
    fun safeApiCallRethrowsCancellation() = runTest {
        var thrown: Throwable? = null
        try {
            safeApiCall<Unit> { throw CancellationException("left the screen") }
        } catch (e: CancellationException) {
            thrown = e
        }
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun safeApiCallHidesDeveloperText() = runTest {
        val r = safeApiCall<Unit> { throw IllegalStateException("Field 'id' is required for type Poll") }
        assertEquals("Something went wrong. Please try again.", (r as ApiResult.Failure).message)
    }

    @Test
    fun userMessageKeepsShortPlayerSentencesFromA4xx() {
        assertEquals("Too far away", http(400, "Too far away").userMessage("fallback"))
    }

    @Test
    fun userMessageHidesServerErrorsAndMarkup() {
        assertEquals("fallback", http(500, "Error: NullPointerException").userMessage("fallback"))
        assertEquals("fallback", http(404, "<html>Not Found</html>").userMessage("fallback"))
        assertEquals("fallback", http(400, """{"status":400}""").userMessage("fallback"))
        assertEquals("fallback", IllegalStateException("boom").userMessage("fallback"))
    }

    @Test
    fun userMessageSaysWhenThereIsNoConnection() {
        assertEquals(
            "Can't reach the server. Check your connection.",
            IOException("Unable to resolve host").userMessage("fallback"),
        )
    }
}
