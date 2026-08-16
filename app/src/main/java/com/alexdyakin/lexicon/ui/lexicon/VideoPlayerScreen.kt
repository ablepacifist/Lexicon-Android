package com.alexdyakin.lexicon.ui.lexicon

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.media.PlayerConnection
import com.alexdyakin.lexicon.media.PlayerState
import com.alexdyakin.lexicon.ui.components.ScreenScaffold

/** Native Media3 video surface; it shares the background-capable MediaSession player. */
@Composable
fun VideoPlayerScreen(
    onBack: () -> Unit,
    player: PlayerConnection,
    state: PlayerState,
) {
    var fullscreen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity

    DisposableEffect(fullscreen) {
        val window = activity?.window
        val originalOrientation = activity?.requestedOrientation
        if (fullscreen && window != null) {
            WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            if (fullscreen && window != null) {
                WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = originalOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    BackHandler(enabled = fullscreen) { fullscreen = false }

    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(player, Modifier.fillMaxSize())
            FilledIconButton(
                onClick = { fullscreen = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) {
                Icon(Icons.Default.FullscreenExit, contentDescription = "Exit fullscreen")
            }
        }
        return
    }

    ScreenScaffold(title = "Video player", onBack = onBack, background = R.drawable.bg_lexicon_room) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 520.dp).background(Color.Black)) {
                VideoSurface(player, Modifier.fillMaxSize())
                FilledIconButton(
                    onClick = { fullscreen = true },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "Enter fullscreen")
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(PaddingValues(horizontal = 24.dp, vertical = 20.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.title.ifBlank { "Untitled video" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "Playback continues in the background. Use the fullscreen button for landscape viewing.",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoSurface(player: PlayerConnection, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = true
                controllerShowTimeoutMs = 3_500
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                this.player = player.playerOrNull()
            }
        },
        update = { it.player = player.playerOrNull() },
        modifier = modifier,
    )
}