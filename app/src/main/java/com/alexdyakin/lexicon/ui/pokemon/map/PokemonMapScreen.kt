package com.alexdyakin.lexicon.ui.pokemon.map

import com.alexdyakin.lexicon.ui.theme.MapColors
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.Gym
import com.alexdyakin.lexicon.data.Pokestop
import com.alexdyakin.lexicon.data.PokemonSpawn
import com.alexdyakin.lexicon.ui.components.LaunchedWhileResumed
import com.alexdyakin.lexicon.ui.components.LexButton
import com.alexdyakin.lexicon.ui.components.LexTone
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import com.alexdyakin.lexicon.ui.theme.Radii
import com.alexdyakin.lexicon.ui.theme.Sizes
import com.alexdyakin.lexicon.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The live world map: what is around the player, and what they can do with it.
 *
 * Built the same way as the website's Leaflet map — OpenStreetMap tiles, the player at
 * the centre, a 200 m ring marking what is reachable — but the entities on top are
 * Compose rather than map-library markers, which is what lets the sprites come through
 * Coil and the markers be themed and animated like everything else in the app.
 */
@Composable
fun PokemonMapScreen(
    onBack: () -> Unit,
    viewModel: PokemonMapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var map by remember { mutableStateOf<OsmMapHandle?>(null) }
    var catching by remember { mutableStateOf<PokemonSpawn?>(null) }
    // Follow until the player drags the map themselves.
    val followPlayer = map?.userMoved != true

    val context = LocalContext.current
    // Asked once per visit. It used to be keyed to every resume, so refusing re-opened the
    // system dialog each time the app came back to the front.
    var askedForLocation by rememberSaveable { mutableStateOf(false) }
    // After "Don't ask again" the system dialog no longer appears at all; only Settings can help.
    var locationBlocked by rememberSaveable { mutableStateOf(false) }
    val requestLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        viewModel.refreshPermission()
        val granted = grants.values.any { it }
        val activity = context as? Activity
        locationBlocked = !granted && activity != null &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val askForLocation = {
        askedForLocation = true
        requestLocation.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    LaunchedWhileResumed(Unit) {
        // Permission and the location switch can both change in Settings while we are away.
        viewModel.refreshPermission()
        if (!askedForLocation && !viewModel.state.value.permissionGranted) askForLocation()
    }

    // GPS and polling both stop when the screen is not resumed. `GET /nearby` seeds new
    // spawns as a side effect, so a poll running in the background would keep changing
    // the world and holding the radio open for a map nobody is looking at.
    LaunchedWhileResumed(state.permissionGranted) {
        if (state.permissionGranted) viewModel.observeLocation()
    }
    LaunchedWhileResumed(state.permissionGranted) {
        if (!state.permissionGranted) return@LaunchedWhileResumed
        while (true) {
            delay(MapWorld.POLL_INTERVAL_MS)
            viewModel.refresh()
        }
    }

    // Keep the map under the player until they pan away themselves.
    val fix = state.fix
    LaunchedWhileResumed(fix, followPlayer, map) {
        if (followPlayer && fix != null) map?.centreOn(fix.lat, fix.lng)
    }

    ScreenScaffold(
        title = "Map",
        onBack = onBack,
        actions = {
            if (state.loading) {
                CircularProgressIndicator(
                    Modifier.size(Sizes.icon),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.permissionGranted -> LocationGate(
                    title = "Location needed",
                    body = "The map shows what is around you, so it needs your location. " +
                        "Nothing is stored on the device beyond the current fix.",
                    action = if (locationBlocked) "Open settings" else "Grant location",
                    onAction = {
                        if (locationBlocked) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } else {
                            askForLocation()
                        }
                    },
                )

                !state.locationEnabled -> LocationGate(
                    title = "Location is switched off",
                    body = "Turn location on and come back. The map picks it up as soon " +
                        "as you return.",
                    action = "Location settings",
                    onAction = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )

                else -> MapSurface(
                    state = state,
                    onMapReady = { map = it },
                    map = map,
                    onSelect = viewModel::select,
                    onPlaceAt = viewModel::placeAt,
                )
            }

            // Overlaid controls and sheets
            if (state.permissionGranted && state.locationEnabled) {
                // OpenStreetMap's tile policy requires this credit wherever its tiles are shown.
                Text(
                    "© OpenStreetMap contributors",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                        .padding(horizontal = Spacing.xs, vertical = 2.dp),
                )
                MapControls(
                    state = state,
                    following = followPlayer,
                    onRecentre = {
                        map?.clearUserMoved()
                        state.fix?.let { map?.centreOn(it.lat, it.lng) }
                    },
                    onPlaceStop = { viewModel.startPlacing(PlacingMode.STOP) },
                    onPlaceGym = { viewModel.startPlacing(PlacingMode.GYM) },
                    onCancelPlacing = viewModel::cancelPlacing,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.lg),
                )
            }

            state.error.takeIf { it.isNotEmpty() }?.let { error ->
                Banner(error, Modifier.align(Alignment.TopCenter).padding(Spacing.md))
            }
            state.message.takeIf { it.isNotEmpty() }?.let { message ->
                Toast(message, viewModel::consumeMessage, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    // Asked when the server says the player has no team, because gyms do nothing until
    // they pick. "Not now" closes it for this visit.
    if (state.permissionGranted && state.needsTeam) {
        TeamPickerDialog(onPick = viewModel::setTeam, onDismiss = viewModel::dismissTeamPicker)
    }

    state.pendingPlacement?.let { point ->
        PlacementDialog(
            mode = state.placing,
            onConfirm = viewModel::confirmPlacement,
            onDismiss = viewModel::cancelPlacing,
        )
    }

    state.selection?.let { selection ->
        SelectionSheet(
            selection = selection,
            state = state,
            onDismiss = { viewModel.select(null) },
            onSpinStop = viewModel::spinStop,
            onLureStop = viewModel::lureStop,
            onSpinGym = viewModel::spinGym,
            onCatch = {
                catching = it
                // Close the sheet as the throw screen opens, or it stays stacked
                // underneath it.
                viewModel.select(null)
            },
        )
    }

    // Above everything, in the activity's own window.
    catching?.let { spawn ->
        CatchScreen(
            spawn = spawn,
            state = state,
            viewModel = viewModel,
            onClose = {
                catching = null
                viewModel.select(null)
            },
        )
    }
}

/** The tiles, plus every entity drawn on top of them. */
@Composable
private fun MapSurface(
    state: PokemonMapUiState,
    map: OsmMapHandle?,
    onMapReady: (OsmMapHandle) -> Unit,
    onSelect: (MapSelection) -> Unit,
    onPlaceAt: (Double, Double) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        OsmMap(handle = onMapReady, modifier = Modifier.fillMaxSize())

        val handle = map ?: return@Box

        // While placing, taps are claimed to pick a point. Otherwise this overlay adds
        // no pointer handling at all, so pans and pinches fall straight through to the
        // map view underneath; claiming them unconditionally would freeze the map.
        val overlay = if (state.placing != PlacingMode.NONE) {
            Modifier.fillMaxSize().pointerInput(state.placing) {
                detectTapGestures { offset ->
                    handle.toGeo(offset)?.let { (lat, lng) -> onPlaceAt(lat, lng) }
                }
            }
        } else {
            Modifier.fillMaxSize()
        }

        Box(overlay) {
            state.fix?.let { fix ->
                RangeRings(handle, fix)
                handle.toScreen(fix.lat, fix.lng)?.let { PlayerPuck(it) }
            }

            state.stops.forEach { stop ->
                handle.toScreen(stop.lat, stop.lng)?.let { at ->
                    StopMarker(
                        stop = stop,
                        at = at,
                        inRange = state.inRange(stop.lat, stop.lng),
                        onClick = { onSelect(MapSelection.StopTarget(stop)) },
                    )
                }
            }

            state.gyms.forEach { gym ->
                handle.toScreen(gym.lat, gym.lng)?.let { at ->
                    GymMarker(
                        gym = gym,
                        at = at,
                        inRange = state.inRange(gym.lat, gym.lng),
                        onClick = { onSelect(MapSelection.GymTarget(gym)) },
                    )
                }
            }

            // Spawns last so they sit above the scenery, as on the website.
            state.spawns.forEach { spawn ->
                handle.toScreen(spawn.lat, spawn.lng)?.let { at ->
                    SpawnMarker(
                        spawn = spawn,
                        at = at,
                        inRange = state.inRange(spawn.lat, spawn.lng),
                        onClick = { onSelect(MapSelection.SpawnTarget(spawn)) },
                    )
                }
            }
        }
    }
}

/**
 * The accuracy circle and the 200 m interaction ring, at their true ground size.
 *
 * The ring is the single most useful thing on the map: everything inside it can be
 * caught or spun and everything outside it cannot, and the server enforces the same
 * number, so drawing it to scale is what stops "too far away" coming as a surprise.
 */
@Composable
private fun RangeRings(handle: OsmMapHandle, fix: PlayerFix) {
    val centre = handle.toScreen(fix.lat, fix.lng) ?: return
    val rangePx = handle.pixelsFor(MapWorld.INTERACTION_RANGE_M, fix.lat)
    val accuracyPx = handle.pixelsFor(fix.accuracyM.toDouble(), fix.lat)
    val primary = MaterialTheme.colorScheme.primary

    Canvas(Modifier.fillMaxSize()) {
        if (accuracyPx > 1f) {
            drawCircle(primary.copy(alpha = 0.10f), radius = accuracyPx, center = centre)
        }
        if (rangePx > 1f) {
            drawCircle(primary.copy(alpha = 0.06f), radius = rangePx, center = centre)
            drawCircle(
                primary.copy(alpha = 0.45f),
                radius = rangePx,
                center = centre,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
            )
        }
    }
}

@Composable
private fun PlayerPuck(at: androidx.compose.ui.geometry.Offset) {
    MarkerSlot(at, size = 42.dp) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
            modifier = Modifier.size(42.dp),
        ) {}
    }
}

/**
 * A wild Pokemon. Out of range it is dimmed rather than hidden, matching the website's
 * "sightings" treatment: knowing something is nearby but unreachable is the thing that
 * makes a player walk.
 */
@Composable
private fun SpawnMarker(
    spawn: PokemonSpawn,
    at: androidx.compose.ui.geometry.Offset,
    inRange: Boolean,
    onClick: () -> Unit,
) {
    MarkerSlot(at, size = 48.dp, anchorBottom = true) {
        AsyncImage(
            model = ApiUrls.spriteUrl(spawn.spriteKey),
            contentDescription = spawn.speciesName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(48.dp)
                .then(if (inRange) Modifier else Modifier.clip(CircleShape))
                .clickable(onClick = onClick)
                .then(
                    if (inRange) Modifier
                    else Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape),
                ),
            alpha = if (inRange) 1f else 0.45f,
        )
    }
}

@Composable
private fun StopMarker(
    stop: Pokestop,
    at: androidx.compose.ui.geometry.Offset,
    inRange: Boolean,
    onClick: () -> Unit,
) {
    val lured = stop.lureExpiresAt != null
    val tint = when {
        lured -> MapColors.Lure
        stop.canSpin && inRange -> MaterialTheme.colorScheme.primary
        else -> MapColors.Inactive
    }
    // Drawn at 32dp, tapped at 48dp: the tap area is the minimum touch size around it.
    MarkerSlot(at, size = Sizes.touch) {
        Box(
            Modifier
                .size(Sizes.touch)
                .clickable(onClick = onClick, role = Role.Button)
                .semantics { contentDescription = "Pokéstop ${stop.name}" },
            contentAlignment = Alignment.Center,
        ) {
        Surface(
            shape = CircleShape,
            color = tint,
            border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
            modifier = Modifier.size(32.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (lured) "✿" else "◆",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        }
    }
}

@Composable
private fun GymMarker(
    gym: Gym,
    at: androidx.compose.ui.geometry.Offset,
    inRange: Boolean,
    onClick: () -> Unit,
) {
    // Drawn at 40dp, tapped at 48dp, still standing on its coordinate.
    MarkerSlot(at, size = Sizes.touch, anchorBottom = true) {
        Box(
            Modifier
                .size(Sizes.touch)
                .clickable(onClick = onClick, role = Role.Button)
                .semantics { contentDescription = "Gym ${gym.name}" },
            contentAlignment = Alignment.BottomCenter,
        ) {
        Surface(
            shape = Radii.card,
            color = MapWorld.teamColor(gym.controllingTeam).copy(alpha = if (inRange) 1f else 0.5f),
            border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
            modifier = Modifier.size(40.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    gym.defenders.size.takeIf { it > 0 }?.toString() ?: "—",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        }
    }
}

/**
 * Positions a marker at a screen point.
 *
 * Markers are centred on their coordinate, except spawns and gyms which stand on it —
 * the same anchoring the website gives them, so a Pokemon appears to be standing in
 * the street rather than floating over it.
 */
@Composable
private fun MarkerSlot(
    at: androidx.compose.ui.geometry.Offset,
    size: androidx.compose.ui.unit.Dp,
    anchorBottom: Boolean = false,
    content: @Composable () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val half = with(density) { size.toPx() / 2f }
    val full = with(density) { size.toPx() }
    Box(
        Modifier.offset {
            IntOffset(
                (at.x - half).roundToInt(),
                (if (anchorBottom) at.y - full else at.y - half).roundToInt(),
            )
        },
    ) { content() }
}

@Composable
private fun LocationGate(title: String, body: String, action: String?, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(Spacing.gutter), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(Spacing.lg))
                LexButton(action, onClick = onAction)
            }
        }
    }
}

@Composable
private fun MapControls(
    state: PokemonMapUiState,
    following: Boolean,
    onRecentre: () -> Unit,
    onPlaceStop: () -> Unit,
    onPlaceGym: () -> Unit,
    onCancelPlacing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (state.placing != PlacingMode.NONE) {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant), shape = Radii.card) {
                Column(Modifier.padding(Spacing.md), horizontalAlignment = Alignment.End) {
                    Text(
                        if (state.placing == PlacingMode.STOP) "Tap the map to put a Pokéstop there"
                        else "Tap the map to put a gym there",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    LexButton("Cancel", onClick = onCancelPlacing, tone = LexTone.Destructive)
                }
            }
        } else {
            // On a solid background an outlined button is legible; over street tiles it
            // is not — roads and labels read straight through it. These sit on an
            // opaque card for that reason, which is also what the website does with
            // its floating controls.
            Surface(shape = Radii.card, color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Column(
                    Modifier.padding(Spacing.sm),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    LexButton("Add stop", onClick = onPlaceStop)
                    LexButton("Add gym", onClick = onPlaceGym)
                    if (!following) LexButton("Recentre", onClick = onRecentre)
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = Radii.card,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text,
            Modifier.padding(Spacing.md),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun Toast(text: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedWhileResumed(text) {
        delay(3_500)
        onDone()
    }
    Surface(
        modifier.padding(Spacing.lg),
        shape = Radii.card,
        color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TeamPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a team") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "Teams own gyms. This is permanent, as it is on the website.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MapWorld.TEAMS.forEach { team ->
                    Surface(
                        shape = Radii.card,
                        color = MapWorld.teamColor(team),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Sizes.touch)
                            .clickable { onPick(team) },
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                team.lowercase().replaceFirstChar(Char::titlecase),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { LexButton("Not now", onClick = onDismiss, tone = LexTone.Secondary) },
    )
}

@Composable
private fun PlacementDialog(
    mode: PlacingMode,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode == PlacingMode.STOP) "Name the Pokéstop" else "Name the gym") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 60) name = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { LexButton("Place", onClick = { onConfirm(name) }) },
        dismissButton = { LexButton("Cancel", onClick = onDismiss, tone = LexTone.Destructive) },
    )
}
