package com.alexdyakin.lexicon.ui.pokemon.map

import com.alexdyakin.lexicon.ui.theme.ThrowQualityColors
import com.alexdyakin.lexicon.data.userMessage
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import coil.compose.AsyncImage
import com.alexdyakin.lexicon.data.ApiUrls
import com.alexdyakin.lexicon.data.PokemonSpawn
import com.alexdyakin.lexicon.ui.components.ActionRow
import com.alexdyakin.lexicon.ui.components.LexButton
import com.alexdyakin.lexicon.ui.components.LexChip
import com.alexdyakin.lexicon.ui.components.LexIconButton
import com.alexdyakin.lexicon.ui.components.LexTone
import com.alexdyakin.lexicon.ui.components.SplitActions
import com.alexdyakin.lexicon.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The throw.
 *
 * A faithful port of the website's game, not of its renderer: the site draws a THREE.js
 * scene, which has no equivalent here worth carrying, so this is 2D Compose. What is
 * kept identical is everything the player can feel or the server can see — the
 * shrinking target ring, the bonus tiers at 0.90 / 0.70 / 0.40, curveball detection
 * from the drag trail by summing cross products past a threshold of 1200, and the rule
 * that a miss costs nothing.
 *
 * A miss never reaches the server. `/catch` consumes a ball and a berry whichever way
 * it resolves, so posting a throw that visibly sailed wide would take the player's
 * items for an attempt they could see had failed.
 */
@Composable
fun CatchScreen(
    spawn: PokemonSpawn,
    state: PokemonMapUiState,
    viewModel: PokemonMapViewModel,
    onClose: () -> Unit,
) {
    // Not a Dialog.
    //
    // In a Dialog window this screen lost whatever was laid out last — "Run away", then
    // the ball selectors — to clipping at the bottom, through an overlay layout, a
    // weighted Column and a Scaffold alike. The dialog window is taller than the
    // visible screen, so its bottom edge falls past what is drawn, and no amount of
    // inset padding inside it recovers that. Rendered as an overlay in the activity's
    // own window the insets are ordinary and the bar lands where it should.
    CatchArena(spawn = spawn, state = state, viewModel = viewModel, onClose = onClose)
}

/** Where a throw ended up, so the UI and the server call agree on one outcome. */
private sealed interface ThrowPhase {
    data object Aiming : ThrowPhase
    data object Flying : ThrowPhase
    data class Shaking(val tier: String) : ThrowPhase
    data class Caught(val name: String) : ThrowPhase
    data class BrokeFree(val message: String) : ThrowPhase
    data class Missed(val reason: String) : ThrowPhase
}

@Composable
private fun CatchArena(
    spawn: PokemonSpawn,
    state: PokemonMapUiState,
    viewModel: PokemonMapViewModel,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var phase by remember { mutableStateOf<ThrowPhase>(ThrowPhase.Aiming) }
    var arena by remember { mutableStateOf(Offset.Zero) } // width/height in px
    var ball by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var trail by remember { mutableStateOf(listOf<Offset>()) }
    var tier by remember { mutableStateOf("") }
    var curve by remember { mutableStateOf(0) }

    // Only balls the player actually holds can be selected, and the same for berries.
    val availableBalls = remember(state.items) {
        Ball.entries.filter { state.itemCount(it.type) > 0 }
    }
    val availableBerries = remember(state.items) {
        Berry.entries.filter { state.itemCount(it.type) > 0 }
    }
    var selectedBall by remember(availableBalls) {
        mutableStateOf(availableBalls.firstOrNull() ?: Ball.POKEBALL)
    }
    var selectedBerry by remember { mutableStateOf<Berry?>(null) }

    // The target ring, pulsing the way it does on the website: the tighter it is when
    // the ball is released, the better the throw.
    val ringTransition = rememberInfiniteTransition(label = "ring")
    val ringFraction by ringTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringFraction",
    )

    val flight = remember { Animatable(0f) }

    // Target: the Pokemon sits at 38% height, the ball rests near the bottom.
    val targetCentre = Offset(arena.x / 2f, arena.y * 0.38f)
    // 0.66, not 0.82: the control stack at the bottom is three rows tall, and a ball
    // resting at 0.82 sat on top of its own hint text.
    val restingBall = Offset(arena.x / 2f, arena.y * 0.66f)
    val hitRadius = arena.x * 0.16f

    // Put the ball at rest once the arena has real dimensions.
    remember(arena) {
        if (arena != Offset.Zero && !dragging && phase == ThrowPhase.Aiming) ball = restingBall
        true
    }

    fun resetForAnotherThrow() {
        phase = ThrowPhase.Aiming
        ball = restingBall
        trail = emptyList()
        tier = ""
        curve = 0
    }

    /** Flies the ball, then resolves the throw with the server if it connected. */
    fun release(velocity: Offset, ringAtRelease: Float, spin: Int) {
        if (phase != ThrowPhase.Aiming) return
        val launchFrom = ball
        // A flick with no upward intent is a drop, not a throw.
        if (velocity.y > -200f && hypot(velocity.x, velocity.y) < 400f) {
            ball = restingBall
            return
        }

        // Where it lands: carried sideways by the flick, and bent by any curve.
        val travel = (launchFrom.y - targetCentre.y).coerceAtLeast(1f)
        val flightSeconds = 0.90f // THROW_TIME on the website
        val driftX = velocity.x * flightSeconds * 0.35f + spin * arena.x * 0.08f
        val landing = Offset(launchFrom.x + driftX, targetCentre.y)
        val offBy = abs(landing.x - targetCentre.x)

        // Bonus from how tight the ring was, with the website's tier thresholds.
        val bonus = (1f - ((ringAtRelease - 0.35f) / 0.65f)).coerceIn(0f, 1f)
        val earnedTier = when {
            bonus >= 0.90f -> "Excellent"
            bonus >= 0.70f -> "Great"
            bonus >= 0.40f -> "Nice"
            else -> ""
        }

        phase = ThrowPhase.Flying
        scope.launch {
            flight.snapTo(0f)
            flight.animateTo(1f, tween((flightSeconds * 1000).toInt(), easing = LinearEasing)) {
                // A parabola: straight line to the landing point, lifted by an arc.
                val t = this.value
                val arc = -4f * travel * 0.35f * (t - t * t)
                ball = Offset(
                    launchFrom.x + (landing.x - launchFrom.x) * t,
                    launchFrom.y + (landing.y - launchFrom.y) * t + arc,
                )
            }

            if (offBy > hitRadius) {
                phase = ThrowPhase.Missed("It sailed past.")
                delay(900)
                resetForAnotherThrow()
                return@launch
            }

            tier = earnedTier
            phase = ThrowPhase.Shaking(earnedTier)
            delay(1_200)

            val outcome = viewModel.throwBall(spawn, selectedBall, selectedBerry)
            phase = outcome.fold(
                onSuccess = { result ->
                    if (result.success) {
                        ThrowPhase.Caught(result.pokemon?.speciesName ?: spawn.speciesName)
                    } else {
                        ThrowPhase.BrokeFree(result.message.ifEmpty { "It broke free!" })
                    }
                },
                // A failed request is not the Pokémon breaking free; say what went wrong.
                onFailure = { ThrowPhase.BrokeFree(it.userMessage("The throw didn't go through. Try again.")) },
            )
        }
    }

    // A Scaffold, not a Column with a weighted middle.
    //
    // Both the overlay version and the weighted-Column version squeezed the control
    // stack: the stack was handed a fixed ~170px whatever its contents needed, so its
    // last child measured zero and simply never drew. Scaffold measures its bars at
    // their natural height first and gives the body what is left, which is exactly the
    // rule this screen needs and gets it right without arithmetic here.
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.surface,
                ),
            ),
        ),
    ) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { CatchHeader(spawn, tier, onClose) },
        bottomBar = {
            CatchControls(
                phase = phase,
                state = state,
                availableBalls = availableBalls,
                availableBerries = availableBerries,
                selectedBall = selectedBall,
                selectedBerry = selectedBerry,
                onSelectBall = { selectedBall = it },
                onSelectBerry = { selectedBerry = it },
                onRetry = { resetForAnotherThrow() },
                onClose = onClose,
            )
        },
        // The ground is painted by the Box around this Scaffold, not here: the inset
        // padding has to sit inside the background, or the map shows through the strip
        // behind the gesture bar.
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    ) { bodyPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(bodyPadding)
                .onSizeChanged { arena = Offset(it.width.toFloat(), it.height.toFloat()) },
        ) {
        // Target ring, drawn under the sprite so the sprite reads as being inside it.
        if (arena != Offset.Zero && phase == ThrowPhase.Aiming) {
            val ringColor = when {
                ringFraction < 0.45f -> ThrowQualityColors.Excellent
                ringFraction < 0.62f -> ThrowQualityColors.Great
                else -> ThrowQualityColors.Nice
            }
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = ringColor.copy(alpha = 0.9f),
                    radius = hitRadius * ringFraction * 1.6f,
                    center = targetCentre,
                    style = Stroke(width = 6f),
                )
            }
        }

        // The Pokemon
        if (arena != Offset.Zero) {
            val spriteSize = 140.dp
            val spritePx = with(density) { spriteSize.toPx() }
            AsyncImage(
                model = ApiUrls.spriteUrl(spawn.spriteKey),
                contentDescription = spawn.speciesName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (targetCentre.x - spritePx / 2f).roundToInt(),
                            (targetCentre.y - spritePx / 2f).roundToInt(),
                        )
                    }
                    .size(spriteSize),
            )
        }

        // The ball, draggable while aiming
        if (arena != Offset.Zero) {
            val ballSize = 56.dp
            val ballPx = with(density) { ballSize.toPx() }
            AsyncImage(
                model = ApiUrls.itemSpriteUrl(selectedBall.sprite),
                contentDescription = selectedBall.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (ball.x - ballPx / 2f).roundToInt(),
                            (ball.y - ballPx / 2f).roundToInt(),
                        )
                    }
                    .size(ballSize)
                    .then(
                        if (phase != ThrowPhase.Aiming) Modifier else Modifier.pointerInput(
                            arena, selectedBall, availableBalls,
                        ) {
                            detectDragGestures(
                                onDragStart = {
                                    dragging = true
                                    trail = listOf(ball)
                                },
                                onDrag = { _, delta ->
                                    ball += delta
                                    // Bounded: the trail is only needed for the spin
                                    // calculation, which looks at the last dozen points.
                                    trail = (trail + ball).takeLast(16)
                                },
                                onDragCancel = {
                                    dragging = false
                                    ball = restingBall
                                },
                                onDragEnd = {
                                    dragging = false
                                    val spin = detectSpin(trail)
                                    curve = spin
                                    val velocity = throwVelocity(trail)
                                    release(velocity, ringFraction, spin)
                                },
                            )
                        },
                    ),
            )
        }
        }
    }
    }
}

/**
 * The bottom bar: the outcome of a throw, or the ball and berry selectors.
 *
 * A Scaffold bottom bar rather than the last child of a Column, because as a Column
 * child it was handed a fixed height that did not fit its contents and silently
 * dropped the last row. A bar is measured at its natural height.
 */
@Composable
private fun CatchControls(
    phase: ThrowPhase,
    state: PokemonMapUiState,
    availableBalls: List<Ball>,
    availableBerries: List<Berry>,
    selectedBall: Ball,
    selectedBerry: Berry?,
    onSelectBall: (Ball) -> Unit,
    onSelectBerry: (Berry?) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
            when (val current = phase) {
                is ThrowPhase.Caught -> {
                    Text(
                        "Gotcha! ${current.name} was caught.",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LexButton("Done", onClick = onClose, tone = LexTone.Primary, modifier = Modifier.fillMaxWidth())
                }

                is ThrowPhase.BrokeFree -> {
                    Text(
                        current.message,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SplitActions(
                        secondary = {
                            LexButton("Leave", onClick = onClose, modifier = Modifier.weight(1f))
                        },
                        primary = {
                            LexButton(
                                "Try again",
                                onClick = onRetry,
                                tone = LexTone.Primary,
                                modifier = Modifier.weight(1f),
                            )
                        },
                    )
                }

                is ThrowPhase.Missed -> Text(
                    current.reason,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                ThrowPhase.Flying, is ThrowPhase.Shaking -> Spacer(Modifier.height(Spacing.xl))

                ThrowPhase.Aiming -> {
                    if (availableBalls.isEmpty()) {
                        Text(
                            "No balls left. Spin a Pokéstop to restock.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LexButton("Leave", onClick = onClose, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(
                            "Flick the ball. Curve it for a bonus.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ActionRow {
                            availableBalls.forEach { option ->
                                LexChip(
                                    label = "${option.label} (${state.itemCount(option.type)})",
                                    selected = option == selectedBall,
                                    onClick = { onSelectBall(option) },
                                )
                            }
                        }
                        if (availableBerries.isNotEmpty()) {
                            ActionRow {
                                availableBerries.forEach { option ->
                                    LexChip(
                                        label = "${option.label} (${state.itemCount(option.type)})",
                                        selected = option == selectedBerry,
                                        onClick = {
                                            onSelectBerry(if (selectedBerry == option) null else option)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }
}

/**
 * Name, level, the throw tier once one is earned, and the way out.
 *
 * The dismiss control lives up here rather than under the ball selectors. As a row at
 * the bottom of the control stack it was squeezed to zero height and never drawn, and
 * a full-screen modal is dismissed from the top by convention anyway — so this is both
 * the reliable place for it and the expected one.
 */
@Composable
private fun CatchHeader(spawn: PokemonSpawn, tier: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        LexIconButton(
            icon = Icons.Filled.Close,
            contentDescription = "Run away",
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).padding(Spacing.sm),
        )
        Column(
            Modifier.fillMaxWidth().padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Text(spawn.speciesName, style = MaterialTheme.typography.titleLarge)
        Text(
            "Level ${spawn.level}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tier.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                tier.uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = when (tier) {
                    "Excellent" -> ThrowQualityColors.Excellent
                    "Great" -> ThrowQualityColors.Great
                    else -> ThrowQualityColors.Nice
                    },
                )
            }
        }
    }
}

/**
 * Curveball detection, identical to the website's `detectSpin`: sum the 2D cross
 * products of consecutive drag deltas and take the sign once the total passes a
 * threshold. A straight flick sums to roughly nothing; a loop builds up one way or the
 * other, and the sign is which way it curves.
 */
private fun detectSpin(trail: List<Offset>): Int {
    if (trail.size < 8) return 0
    var cross = 0f
    for (i in 1 until trail.size - 1) {
        val ax = trail[i].x - trail[i - 1].x
        val ay = trail[i].y - trail[i - 1].y
        val bx = trail[i + 1].x - trail[i].x
        val by = trail[i + 1].y - trail[i].y
        cross += ax * by - ay * bx
    }
    if (abs(cross) < 1200f) return 0
    return if (cross > 0) 1 else -1
}

/** Release velocity in pixels per second, from the tail of the drag. */
private fun throwVelocity(trail: List<Offset>): Offset {
    if (trail.size < 2) return Offset.Zero
    val recent = trail.takeLast(5)
    val delta = recent.last() - recent.first()
    // The drag samples arrive about one per frame, so the tail spans roughly
    // (n-1)/60 of a second. Precise enough to tell a flick from a nudge.
    val seconds = ((recent.size - 1) / 60f).coerceAtLeast(1f / 60f)
    return Offset(delta.x / seconds, delta.y / seconds)
}
