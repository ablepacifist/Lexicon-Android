package com.alexdyakin.lexicon.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import retrofit2.HttpException
import java.io.IOException

/**
 * `runCatching` for coroutines.
 *
 * Plain `runCatching { async { … } }` inside `viewModelScope.launch` crashes the app when
 * one of the `async` calls fails: the failed child cancels the *parent* launch, the
 * exception escapes `runCatching`, and nothing handles it. The map did exactly that on
 * every network drop. Here the block runs in its own [coroutineScope], so a failing child
 * fails only this call and comes back as [Result.failure].
 *
 * Cancellation is rethrown rather than captured: when the screen is left, the work should
 * stop, not carry on and write a "failure" into state nobody is watching.
 */
suspend fun <T> attempt(block: suspend CoroutineScope.() -> T): Result<T> = try {
    Result.success(coroutineScope(block))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

private const val TAG = "Lexicon"
private const val NO_CONNECTION = "Can't reach the server. Check your connection."

/**
 * A failure as a sentence for the player.
 *
 * Never the raw exception text ("HTTP 500 ", "Unable to resolve host …", a stack-ish
 * message): the detail goes to logcat. A 4xx whose body is a short human sentence
 * ("Too far away") is shown, because the game servers write those for players.
 */
fun Throwable.userMessage(fallback: String): String {
    Log.w(TAG, fallback, this)
    return when (this) {
        is IOException -> NO_CONNECTION
        is HttpException -> if (code() in 400..499) readableBody() ?: fallback else fallback
        else -> fallback
    }
}

/** Retrofit buffers error bodies, so reading one here does no network I/O. */
private fun HttpException.readableBody(): String? = runCatching {
    response()?.errorBody()?.string()?.trim()
}.getOrNull()?.takeIf { text ->
    text.isNotEmpty() && text.length <= 160 && !text.startsWith("<") && !text.startsWith("{")
}
