package com.alexdyakin.lexicon.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope

/**
 * Runs [block] only while the screen is actually in front of the user, restarting it when they
 * come back and cancelling it when they leave.
 *
 * `repeatOnLifecycle` appeared **zero times** in this app before, which is why a handful of
 * screens kept polling the network with the phone in a pocket: the torrent list fetched every
 * three seconds, the queue screen reopened a dead SSE connection every three seconds forever,
 * and none of it stopped until the user navigated away — backgrounding the app did nothing,
 * because `DisposableEffect` only fires on leaving composition.
 *
 * **Do not use this for anything in the media path.** Playback, the position tick and the
 * audiobook resume tracker are all meant to keep running with the screen off and while the user
 * is in another app; those are keyed off playback state instead. This is for work that exists
 * purely to keep a visible screen up to date.
 */
@Composable
fun LaunchedWhileResumed(
    vararg keys: Any?,
    block: suspend CoroutineScope.() -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, *keys) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { block() }
    }
}
