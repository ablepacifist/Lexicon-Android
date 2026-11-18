package com.alexdyakin.lexicon.ui.lexicon

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LexChip
import com.alexdyakin.lexicon.ui.components.PlayPauseButton
import com.alexdyakin.lexicon.ui.components.TransportBar
import com.alexdyakin.lexicon.ui.components.TransportButton
import com.alexdyakin.lexicon.ui.theme.Sizes
import com.alexdyakin.lexicon.ui.theme.Spacing
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import java.util.concurrent.TimeUnit

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** One transport slot. Matches TransportBar's contract, so this button mirrors the others. */
private val CONTROL_SLOT = Sizes.transportSlot

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: MediaViewModel = hiltViewModel(),
) {
    val player = viewModel.player
    val state by player.state.collectAsState()

    if (state.isVideo) {
        VideoPlayerScreen(onBack = onBack, player = player, state = state)
        return
    }

    // While the user drags the scrubber, show their position rather than the player's
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    val shown = if (scrubbing) scrubValue else state.progress

    ScreenScaffold(title = "Now playing", onBack = onBack) { padding ->
        if (state.mediaId == null) {
            EmptyBox(padding, "Nothing is playing.\n\nPick something from the library.")
            return@ScreenScaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PulsingArt(playing = state.isPlaying)

            Spacer(Modifier.height(32.dp))

            Text(
                state.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                state.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(28.dp))

            Slider(
                value = shown,
                onValueChange = { scrubbing = true; scrubValue = it },
                onValueChangeFinished = {
                    player.seekToFraction(scrubValue)
                    scrubbing = false
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    formatTime((shown * state.durationMs).toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatTime(state.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))

            // This row used to lay seven fixed 48dp controls into 304dp of space and centre the
            // overflow, so in audiobook mode shuffle was clipped off the left edge and the speed
            // button off the right. TransportBar keeps at most two controls a side — 288dp, which
            // fits a 320dp phone — and pushes shuffle and speed into a second wrapped row rather
            // than letting anything fall off the screen.
            TransportBar(
                center = {
                    PlayPauseButton(
                        playing = state.isPlaying,
                        onClick = player::togglePlayPause,
                    )
                },
                leading = {
                    if (state.isAudiobook) {
                        SeekBySecondsButton(
                            seconds = 15,
                            forward = false,
                            onClick = { player.seekBy(-15_000L) },
                        )
                    }
                    TransportButton(
                        icon = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        onClick = player::previous,
                        enabled = state.hasPrevious,
                    )
                },
                trailing = {
                    TransportButton(
                        icon = Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        onClick = player::next,
                        enabled = state.hasNext,
                    )
                    if (state.isAudiobook) {
                        SeekBySecondsButton(
                            seconds = 30,
                            forward = true,
                            onClick = { player.seekBy(30_000L) },
                        )
                    }
                },
                extras = {
                    LexChip(
                        label = "Shuffle",
                        selected = state.shuffle,
                        onClick = player::toggleShuffle,
                        leadingIcon = Icons.Filled.Shuffle,
                    )
                    if (state.isAudiobook) {
                        LexChip(
                            label = "${trimSpeed(state.speed)}×",
                            selected = state.speed != 1f,
                            onClick = {
                                val i = SPEEDS.indexOfFirst { it >= state.speed - 0.01f }
                                viewModel.setSpeed(SPEEDS[(if (i < 0) 1 else i + 1) % SPEEDS.size])
                            },
                        )
                    }
                },
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "Playing continues when you leave the app — check your lock screen.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Circular-arrow button with the seek amount drawn inside it. Material only ships
 * Replay5/10/30, so the arrow is mirrored for the forward direction and the number
 * is overlaid, which keeps both buttons the same shape and width.
 */
@Composable
private fun SeekBySecondsButton(seconds: Int, forward: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(CONTROL_SLOT)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Replay,
                contentDescription = if (forward) "Forward $seconds seconds" else "Back $seconds seconds",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(34.dp)
                    .scale(scaleX = if (forward) -1f else 1f, scaleY = 1f),
            )
            Text(
                seconds.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Slow breathing disc while playing; still when paused. */
@Composable
private fun PulsingArt(playing: Boolean) {
    val transition = rememberInfiniteTransition(label = "art")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val scale = if (playing) pulse else 1f

    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(230.dp)
                .scale(scale)
                .alpha(if (playing) 0.45f else 0.2f)
                .background(
                    Brush.radialGradient(
                        listOf(MaterialTheme.colorScheme.primary, Color.Transparent)
                    ),
                    shape = CircleShape,
                )
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            modifier = Modifier.size(150.dp).scale(scale),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("♪", style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun trimSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else "%.2f".format(speed).trimEnd('0').trimEnd('.')

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
