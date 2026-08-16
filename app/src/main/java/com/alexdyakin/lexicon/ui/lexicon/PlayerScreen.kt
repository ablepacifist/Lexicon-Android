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
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import java.util.concurrent.TimeUnit

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** Uniform touch-target width for every transport control, so the row stays centred. */
private val CONTROL_SLOT = 48.dp

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

    ScreenScaffold(title = "Now playing", onBack = onBack, background = R.drawable.bg_lexicon_room) { padding ->
        if (state.mediaId == null) {
            EmptyBox(padding, "Nothing is playing.\n\nPick something from the library.")
            return@ScreenScaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp),
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

            // Gentle settle on the play button whenever playback starts or stops.
            val playScale by animateFloatAsState(
                targetValue = if (state.isPlaying) 1f else 0.94f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "playScale",
            )

            // Every control occupies the same 48.dp slot so the row stays visually
            // centred whatever combination of audiobook buttons is showing.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                IconButton(onClick = player::toggleShuffle, modifier = Modifier.size(CONTROL_SLOT)) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.alpha(if (state.shuffle) 1f else 0.4f),
                    )
                }
                if (state.isAudiobook) {
                    SeekBySecondsButton(
                        seconds = 15,
                        forward = false,
                        onClick = { player.seekBy(-15_000L) },
                    )
                }
                IconButton(
                    onClick = player::previous,
                    enabled = state.hasPrevious,
                    modifier = Modifier.size(CONTROL_SLOT),
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                }

                // Play/pause keeps its larger slot but is padded symmetrically so it
                // does not pull the row off centre.
                FilledIconButton(
                    onClick = player::togglePlayPause,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(72.dp)
                        .scale(playScale),
                ) {
                    // Crossfade rather than a hard swap between the two states.
                    AnimatedContent(
                        targetState = state.isPlaying,
                        transitionSpec = {
                            (fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.7f))
                                .togetherWith(fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.7f))
                        },
                        label = "playPause",
                    ) { playing ->
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                IconButton(
                    onClick = player::next,
                    enabled = state.hasNext,
                    modifier = Modifier.size(CONTROL_SLOT),
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                }
                if (state.isAudiobook) {
                    SeekBySecondsButton(
                        seconds = 30,
                        forward = true,
                        onClick = { player.seekBy(30_000L) },
                    )
                    IconButton(
                        onClick = {
                            val i = SPEEDS.indexOfFirst { it >= state.speed - 0.01f }
                            viewModel.setSpeed(SPEEDS[(if (i < 0) 1 else i + 1) % SPEEDS.size])
                        },
                        modifier = Modifier.size(CONTROL_SLOT),
                    ) {
                        Text(
                            "${trimSpeed(state.speed)}×",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

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
