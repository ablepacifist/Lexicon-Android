package com.alexdyakin.lexicon.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.ui.components.ScreenScaffold

/**
 * Voice is the one area still to be built natively — the Mumble audio path
 * (Opus encode/decode plus the WebSocket protocol) is the largest remaining
 * piece of work in the project.
 *
 * This screen is deliberately honest about that rather than pretending to
 * connect. The microphone permission flow is real and wired up, since that is
 * the first thing the audio work will need.
 */
@Composable
fun VoiceScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val requestMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    ScreenScaffold(title = "Voice", onBack = onBack, background = R.drawable.bg_lexicon_room) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PulsingMic(active = micGranted)

            Spacer(Modifier.height(28.dp))

            Text(
                if (micGranted) "Microphone ready" else "Microphone not granted",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Text(
                "Native voice is still being built. The Mumble audio path — Opus " +
                    "encode/decode and the WebSocket protocol — is the largest " +
                    "remaining piece, and it is deliberately not faked here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )

            Spacer(Modifier.height(24.dp))

            if (!micGranted) {
                Button(
                    onClick = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text("Grant microphone access")
                }
            }
        }
    }
}

@Composable
private fun PulsingMic(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.25f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(180.dp)
                .scale(pulse)
                .alpha(if (active) 0.4f else 0.18f)
                .background(
                    Brush.radialGradient(
                        listOf(MaterialTheme.colorScheme.primary, Color.Transparent)
                    ),
                    shape = CircleShape,
                )
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "🎙",
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.alpha(if (active) 1f else 0.5f),
                )
            }
        }
    }
}
