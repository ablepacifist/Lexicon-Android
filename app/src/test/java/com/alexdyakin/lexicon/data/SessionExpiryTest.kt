package com.alexdyakin.lexicon.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionExpiryTest {

    @Test
    fun manyFailingRequestsSignOutOnce() = runTest(UnconfinedTestDispatcher()) {
        val expiry = SessionExpiry()
        var signals = 0
        val job = expiry.events.onEach { signals++ }.launchIn(this)
        repeat(5) { expiry.report() } // a screen's worth of parallel calls all answering 401
        assertEquals(1, signals)
        job.cancel()
    }

    @Test
    fun theNextSessionCanExpireToo() = runTest(UnconfinedTestDispatcher()) {
        val expiry = SessionExpiry()
        var signals = 0
        val job = expiry.events.onEach { signals++ }.launchIn(this)
        expiry.report()
        expiry.reset() // signed in again
        expiry.report()
        assertEquals(2, signals)
        job.cancel()
    }
}
