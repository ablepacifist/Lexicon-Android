package com.alexdyakin.lexicon.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/**
 * Every repository call returns one of these rather than throwing.
 *
 * The important case is [Unauthorized]: previously an expired or rotated token just
 * produced a per-screen failure with no way to react, so the app sat there signed out
 * without knowing it.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Failure(val message: String, val code: Int? = null) : ApiResult<Nothing>
    data object Unauthorized : ApiResult<Nothing>

    val successOrNull: T? get() = (this as? Success)?.data
}

suspend inline fun <T> safeApiCall(crossinline block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: CancellationException) {
    // The caller's scope is going away (screen left). Swallowing this as a Failure kept
    // the coroutine running and writing an error into state nobody was watching.
    throw e
} catch (e: HttpException) {
    when (e.code()) {
        401, 403 -> ApiResult.Unauthorized
        else -> ApiResult.Failure("Server error (HTTP ${e.code()}).", e.code())
    }
} catch (e: IOException) {
    ApiResult.Failure("Can't reach the server. Check your connection.")
} catch (e: Exception) {
    // e.message is developer text (a serializer complaint, a null check); keep it in logcat.
    Log.w("Lexicon", "API call failed", e)
    ApiResult.Failure("Something went wrong. Please try again.")
}
