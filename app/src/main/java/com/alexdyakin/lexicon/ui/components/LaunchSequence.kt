package com.alexdyakin.lexicon.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.alexdyakin.lexicon.R
import kotlinx.coroutines.delay

/** Red pulled from the cube's own rune glow, so the drawn ring belongs to the artwork. */
private val RuneRed = Color(0xFFE03A2F)
private val RuneEmber = Color(0xFFFF7A45)

/**
 * The cold-start sequence: the sigil arrives, a ring of runes is inscribed around it,
 * and the whole thing hands over to the app.
 *
 * The cube stays the original raster. It is a photoreal render — texture, lighting and
 * several hundred individual rune marks — and tracing that to vector would produce
 * thousands of paths that weigh more and look worse than the image does. What *is* worth
 * drawing as vector is the ring around it, because a path can be drawn *on*: the arc
 * sweeps from nothing to a full circle, which is the part that reads as an animation
 * rather than as a picture being moved about.
 *
 * Timing is deliberately under a second and a half. A launch animation is something the
 * user sees every single time they open the app, so it has to be over before it starts
 * being furniture — [onFinished] fires as the last frame fades.
 */
@Composable
fun LaunchSequence(onFinished: () -> Unit) {
    // Each stage is its own Animatable so the sequence is written as a script rather
    // than as one progress value with magic thresholds cut out of it.
    val cubeScale = remember { Animatable(0.55f) }
    val cubeAlpha = remember { Animatable(0f) }
    val ringSweep = remember { Animatable(0f) }
    val settleSpin = remember { Animatable(-14f) }
    val exitAlpha = remember { Animatable(1f) }

    val shimmer = rememberInfiniteTransition(label = "shimmer")
    val emberPulse by shimmer.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ember",
    )
    val drift by shimmer.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(9000, easing = LinearEasing)),
        label = "drift",
    )

    LaunchedEffect(Unit) {
        // The sigil lands first, overshooting very slightly so it has some weight.
        cubeAlpha.animateTo(1f, tween(320))
        cubeScale.animateTo(1f, tween(520, easing = EaseOutBack))
        settleSpin.animateTo(0f, tween(520, easing = FastOutSlowInEasing))
        // Then the ring is inscribed around it.
        ringSweep.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        delay(140)
        exitAlpha.animateTo(0f, tween(280))
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFF1A1512), Color(0xFF070605)),
                ),
            )
            .alpha(exitAlpha.value),
        contentAlignment = Alignment.Center,
    ) {
        // The ring sits behind the cube so the cube's corners overlap it, which reads as
        // one object rather than as a picture with a circle stuck on top.
        Canvas(Modifier.size(240.dp).rotate(drift)) {
            val stroke = 3.dp.toPx()
            val inset = stroke * 2
            val arcSize = Size(size.width - inset, size.height - inset)
            val topLeft = Offset(inset / 2, inset / 2)

            // The outer ring draws itself on.
            drawArc(
                color = RuneRed.copy(alpha = 0.85f),
                startAngle = -90f,
                sweepAngle = 360f * ringSweep.value,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )

            // Tick marks appear only as the sweep passes them, so the ring is inscribed
            // rather than revealed.
            val ticks = 24
            repeat(ticks) { index ->
                val fraction = index.toFloat() / ticks
                if (fraction > ringSweep.value) return@repeat
                rotate(degrees = fraction * 360f, pivot = center) {
                    val long = index % 4 == 0
                    val length = if (long) 14.dp.toPx() else 7.dp.toPx()
                    drawLine(
                        color = if (long) RuneEmber.copy(alpha = emberPulse) else RuneRed.copy(alpha = 0.6f),
                        start = Offset(center.x, inset / 2 + stroke),
                        end = Offset(center.x, inset / 2 + stroke + length),
                        strokeWidth = if (long) 3.dp.toPx() else 1.5.dp.toPx(),
                    )
                }
            }

            // An inner ring trails the outer one, closing a beat later.
            val innerSweep = (ringSweep.value * 1.35f - 0.35f).coerceIn(0f, 1f)
            if (innerSweep > 0f) {
                val innerInset = 34.dp.toPx()
                drawArc(
                    color = RuneEmber.copy(alpha = 0.5f * emberPulse),
                    startAngle = -90f,
                    sweepAngle = -360f * innerSweep,
                    useCenter = false,
                    topLeft = Offset(innerInset, innerInset),
                    size = Size(size.width - innerInset * 2, size.height - innerInset * 2),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

        Image(
            painter = painterResource(R.drawable.logo_lexicon),
            contentDescription = null,
            modifier = Modifier
                .size(132.dp)
                .scale(cubeScale.value)
                .rotate(settleSpin.value)
                .alpha(cubeAlpha.value),
        )
    }
}
