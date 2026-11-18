package com.alexdyakin.lexicon.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alexdyakin.lexicon.ui.theme.LexPalette
import com.alexdyakin.lexicon.ui.theme.Radii
import com.alexdyakin.lexicon.ui.theme.Sizes
import com.alexdyakin.lexicon.ui.theme.Spacing

/**
 * How much weight a control carries.
 *
 * Making this explicit is what stops the arbitrary assignment the app had: two peer view-switchers
 * rendered as a filled button and an outlined one, a destructive "Remove" rendered as the heaviest
 * control on its card, outlined-before-filled on one screen and filled-before-outlined on another.
 */
enum class LexTone {
    /** One per screen region. The thing you are meant to do. */
    Primary,

    /** The default. Everything that is an ordinary action. */
    Secondary,

    /** Low emphasis — "cancel", "not now", navigation that is not the point of the screen. */
    Quiet,

    /**
     * Deletes something. Always **outlined** in the error colour, never filled: a destructive
     * action should be reachable, not inviting.
     */
    Destructive,
}

/**
 * The app's button. Every one is [Sizes.control] tall, single-line and ellipsised, so a long label
 * or a large font scale makes text shorter rather than making the row overflow.
 */
@Composable
fun LexButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: LexTone = LexTone.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(Sizes.iconSm),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    val sizing = modifier.defaultMinSize(minHeight = Sizes.control)
    val shape = MaterialTheme.shapes.medium

    when (tone) {
        LexTone.Primary -> Button(onClick, sizing, enabled, shape) { content() }
        LexTone.Secondary -> OutlinedButton(onClick, sizing, enabled, shape) { content() }
        LexTone.Quiet -> TextButton(onClick, sizing, enabled, shape) { content() }
        LexTone.Destructive -> OutlinedButton(
            onClick,
            sizing,
            enabled,
            shape,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { content() }
    }
}

/**
 * An icon-only control. Always at least [Sizes.touch] across, which the 15 hand-rolled clickable
 * `Surface`s it replaces were not — those also had no ripple and no disabled colours.
 *
 * This is the one Back control (the scaffold's arrow) and the one icon-form delete.
 */
@Composable
fun LexIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: LexTone = LexTone.Quiet,
    size: Dp = Sizes.touch,
    iconSize: Dp = Sizes.icon,
) {
    val tint = when (tone) {
        LexTone.Primary -> MaterialTheme.colorScheme.primary
        LexTone.Secondary -> MaterialTheme.colorScheme.onSurface
        LexTone.Quiet -> MaterialTheme.colorScheme.onSurfaceVariant
        LexTone.Destructive -> MaterialTheme.colorScheme.error
    }
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize))
    }
}

/**
 * A selectable chip. Replaces the clickable `Surface` pills — filter pills, speed chips, tabs —
 * which looked like chips but had none of the behaviour.
 */
@Composable
fun LexChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = Sizes.controlSm),
        enabled = enabled,
        shape = Radii.pill,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = leadingIcon?.let {
            { Icon(it, contentDescription = null, modifier = Modifier.size(Sizes.iconSm)) }
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

/**
 * The one play/pause control, replacing five.
 *
 * There were a `FilledIconButton` at 72dp, three near-identical hand-built
 * `Box` + `Brush.linearGradient` + `clickable` circles at 60dp (the third pointlessly wrapped in a
 * `Surface`), and an `IconButton` containing the **emoji** `"⏸"`/`"▶"`. The gradient look of the
 * hand-built ones is kept — it is the nicest of the five — but it now sits inside a real
 * `FilledIconButton`, so it gets ripple, the minimum touch target and disabled handling for free.
 */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.transportPrimary,
    enabled: Boolean = true,
) {
    // A small spring on each press, carried over from PlayerScreen — it was the one piece of
    // feedback the other four implementations lacked.
    val scale by animateFloatAsState(
        targetValue = if (playing) 1f else 0.94f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "playScale",
    )

    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(size).scale(scale),
        enabled = enabled,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(listOf(LexPalette.Indigo, LexPalette.Violet)),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = playing,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "playPause",
            ) { isPlaying ->
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.45f),
                )
            }
        }
    }
}

/** A transport side control: fixed slot width, so both sides of a [TransportBar] stay mirrored. */
@Composable
fun TransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LexIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tone = LexTone.Secondary,
        size = Sizes.transportSlot,
        iconSize = Sizes.iconLg,
    )
}
