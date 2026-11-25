package com.alexdyakin.lexicon.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One signal for "the server no longer accepts this sign-in".
 *
 * An expired or revoked token used to surface as about forty separate "sign in again"
 * messages, one per screen, with the app still showing the signed-in UI and no way back to
 * the sign-in screen short of the Sign out button. [AuthInterceptor] reports a 401 here,
 * once per session; the app shell answers it by signing out and showing the sign-in screen.
 */
@Singleton
class SessionExpiry @Inject constructor() {
    private val reported = AtomicBoolean(false)
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    /** Called from OkHttp threads; only the first report of a session gets through. */
    fun report() {
        if (reported.compareAndSet(false, true)) _events.tryEmit(Unit)
    }

    /** A new session has started. */
    fun reset() = reported.set(false)
}
