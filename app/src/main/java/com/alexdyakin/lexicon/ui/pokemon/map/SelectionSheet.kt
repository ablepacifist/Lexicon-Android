package com.alexdyakin.lexicon.ui.pokemon.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.Gym
import com.alexdyakin.lexicon.data.GymDefender
import com.alexdyakin.lexicon.data.Pokestop
import com.alexdyakin.lexicon.data.PokemonSpawn
import com.alexdyakin.lexicon.ui.components.LexButton
import com.alexdyakin.lexicon.ui.components.LexTone
import com.alexdyakin.lexicon.ui.components.ListRow
import com.alexdyakin.lexicon.ui.components.SplitActions
import com.alexdyakin.lexicon.ui.theme.Radii
import com.alexdyakin.lexicon.ui.theme.Sizes
import com.alexdyakin.lexicon.ui.theme.Spacing

/**
 * What a tapped thing is, and what can be done to it.
 *
 * Every action states its own distance requirement rather than silently disabling: the
 * server rejects an out-of-range spin with "Too far away (nnn m)", and telling the
 * player the number up front is the difference between a map that teaches and one
 * that just refuses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionSheet(
    selection: MapSelection,
    state: PokemonMapUiState,
    onDismiss: () -> Unit,
    onSpinStop: (Pokestop) -> Unit,
    onLureStop: (Pokestop) -> Unit,
    onSpinGym: (Gym) -> Unit,
    // The throw screen is raised to the map rather than nested here: a full-screen view
    // inside a bottom sheet's composition inherits the sheet's window and layout, which
    // is how it ended up clipped.
    onCatch: (PokemonSpawn) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (selection) {
                is MapSelection.SpawnTarget -> SpawnDetail(
                    spawn = selection.spawn,
                    state = state,
                    onCatch = { onCatch(selection.spawn) },
                )

                is MapSelection.StopTarget -> StopDetail(
                    stop = selection.stop,
                    state = state,
                    onSpin = { onSpinStop(selection.stop) },
                    onLure = { onLureStop(selection.stop) },
                )

                is MapSelection.GymTarget -> GymDetail(
                    gym = selection.gym,
                    state = state,
                    onSpin = { onSpinGym(selection.gym) },
                )
            }
        }
    }
}

@Composable
private fun SpawnDetail(
    spawn: PokemonSpawn,
    state: PokemonMapUiState,
    onCatch: () -> Unit,
) {
    val metres = state.metresTo(spawn.lat, spawn.lng)
    val inRange = state.inRange(spawn.lat, spawn.lng)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        AsyncImage(
            model = ApiUrls.spriteUrl(spawn.spriteKey),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(Sizes.avatarLg),
        )
        Column(Modifier.weight(1f)) {
            Text(
                spawn.speciesName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Level ${spawn.level}" + (metres?.let { " · ${it} m away" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (inRange) {
        LexButton("Catch", onClick = onCatch, tone = LexTone.Primary, modifier = Modifier.fillMaxWidth())
    } else {
        OutOfRange(metres)
    }
}

@Composable
private fun StopDetail(
    stop: Pokestop,
    state: PokemonMapUiState,
    onSpin: () -> Unit,
    onLure: () -> Unit,
) {
    val metres = state.metresTo(stop.lat, stop.lng)
    val inRange = state.inRange(stop.lat, stop.lng)
    val lures = state.itemCount("LURE_MODULE")

    Text(
        stop.name.ifEmpty { "Pokéstop" },
        style = MaterialTheme.typography.titleLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        buildString {
            append(stop.biome.lowercase().replaceFirstChar(Char::titlecase))
            append(" biome")
            metres?.let { append(" · $it m away") }
            if (stop.lureExpiresAt != null) append(" · lured")
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when {
        !inRange -> OutOfRange(metres)
        !stop.canSpin -> Text(
            "On cooldown. Come back in a few minutes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // SplitActions, not ActionRow: two equal halves measured after each other, so
        // the pair is symmetric however long the lure count makes its label.
        else -> SplitActions(
            secondary = {
                LexButton(
                    if (lures > 0) "Lure ($lures)" else "Lure",
                    onClick = onLure,
                    enabled = lures > 0,
                    modifier = Modifier.weight(1f),
                )
            },
            primary = {
                LexButton(
                    "Spin",
                    onClick = onSpin,
                    tone = LexTone.Primary,
                    modifier = Modifier.weight(1f),
                )
            },
        )
    }
}

@Composable
private fun GymDetail(
    gym: Gym,
    state: PokemonMapUiState,
    onSpin: () -> Unit,
) {
    val metres = state.metresTo(gym.lat, gym.lng)
    val inRange = state.inRange(gym.lat, gym.lng)
    val team = gym.controllingTeam

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Surface(shape = Radii.card, color = MapWorld.teamColor(team), modifier = Modifier.size(Sizes.avatar)) {}
        Column(Modifier.weight(1f)) {
            Text(
                gym.name.ifEmpty { "Gym" },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(team?.lowercase()?.replaceFirstChar(Char::titlecase) ?: "Unclaimed")
                    metres?.let { append(" · $it m away") }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (gym.defenders.isNotEmpty()) {
        Text("Defenders", style = MaterialTheme.typography.labelLarge)
        // Bounded: a full gym holds six, and an unbounded list inside a sheet can grow
        // past the screen with no way to scroll to the actions underneath it.
        LazyColumn(Modifier.heightIn(max = 240.dp)) {
            items(gym.defenders, key = { it.id }) { DefenderRow(it) }
        }
    }

    when {
        !inRange -> OutOfRange(metres)
        !gym.canSpin -> Text(
            "The disc is on cooldown.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> LexButton(
            "Spin the disc",
            onClick = onSpin,
            tone = LexTone.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DefenderRow(defender: GymDefender) {
    ListRow(
        action = {
            Text(
                "${defender.motivation}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        AsyncImage(
            model = ApiUrls.spriteUrl(defender.spriteKey),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(Sizes.avatarSm),
        )
        Column(Modifier.weight(1f)) {
            Text(
                defender.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Lv ${defender.level} · slot ${defender.slot}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Says how far, not just that it is too far — the number is what makes it actionable. */
@Composable
private fun OutOfRange(metres: Int?) {
    Text(
        metres?.let {
            "Too far away — $it m. Get within ${MapWorld.INTERACTION_RANGE_M.toInt()} m."
        } ?: "Waiting for a GPS fix.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
