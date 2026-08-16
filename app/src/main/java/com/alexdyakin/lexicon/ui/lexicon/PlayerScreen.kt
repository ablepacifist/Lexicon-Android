package com.alexdyakin.lexicon.ui.lexicon

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                IconButton(onClick = player::toggleShuffle) {
                    Text(
                        "🔀",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.alpha(if (state.shuffle) 1f else 0.4f),
                    )
                }
                if (state.isAudiobook) {
                    IconButton(onClick = { player.seekBy(-15_000L) }) {
                        Text("↺15", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = player::previous, enabled = state.hasPrevious) {
                    Text("⏮", style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                FilledIconButton(
                    onClick = player::togglePlayPause,
                    modifier = Modifier.size(72.dp),
                ) {
                    Text(
                        if (state.isPlaying) "⏸" else "▶",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                IconButton(onClick = player::next, enabled = state.hasNext) {
                    Text("⏭", style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (state.isAudiobook) {
                    IconButton(onClick = {
                        val i = SPEEDS.indexOfFirst { it >= state.speed - 0.01f }
                        viewModel.setSpeed(SPEEDS[(if (i < 0) 1 else i + 1) % SPEEDS.size])
                    }) {
                        Text(
                            "${trimSpeed(state.speed)}×",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { player.seekBy(30_000L) }) {
                        Text("30↻", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
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
