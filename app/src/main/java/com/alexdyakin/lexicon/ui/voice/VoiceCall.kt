package com.alexdyakin.lexicon.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexdyakin.lexicon.voice.VoiceState

/**
 * The connected-call strip, mirroring the web client's voice panel above the composer.
 *
 * Speakers are shown as a count rather than as names: the audio stream identifies people
 * by Mumble session id while presence uses `web_<userId>`, and the server publishes no
 * mapping between the two. Inventing one would be guesswork, so the strip reports how
 * many voices are audible and leaves naming to the member list.
 */
@Composable
internal fun VoiceBar(
    state: VoiceState,
    channelName: String?,
    onToggleMute: () -> Unit,
    onLeave: () -> Unit,
) {
    val label = when (state) {
        is VoiceState.Connecting -> "Connecting…"
        is VoiceState.Active -> when {
            state.speakers.isEmpty() -> "Voice connected"
            state.speakers.size == 1 -> "1 person speaking"
            else -> "${state.speakers.size} people speaking"
        }

        is VoiceState.Failed -> state.reason
        is VoiceState.Idle -> return
    }

    val accent = when (state) {
        is VoiceState.Active -> BridgeColors.textPositive
        is VoiceState.Failed -> BridgeColors.textDanger
        else -> BridgeColors.textWarning
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(BridgeColors.bgTertiary)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            channelName?.let {
                Text(it, color = BridgeColors.textMuted, fontSize = 12.sp)
            }
        }

        if (state is VoiceState.Active) {
            IconButton(onClick = onToggleMute) {
                Icon(
                    if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (state.muted) "Unmute" else "Mute",
                    tint = if (state.muted) BridgeColors.textDanger else BridgeColors.interactiveNormal,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }

        IconButton(onClick = onLeave) {
            Icon(
                Icons.Filled.CallEnd,
                contentDescription = "Leave voice",
                tint = BridgeColors.textDanger,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
