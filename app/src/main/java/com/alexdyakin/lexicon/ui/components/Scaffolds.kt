package com.alexdyakin.lexicon.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.ui.theme.LexGround
import com.alexdyakin.lexicon.ui.theme.Spacing

/**
 * Shared chrome for every screen in the app.
 *
 * Two things changed here, and both are load-bearing.
 *
 * **There is no background image.** This used to take a `background: Int` and paint a full-bleed
 * `painterResource` under a 55–88% black gradient. The art was almost entirely hidden by its own
 * scrim, and the bitmap was expensive out of all proportion: `bg_dashboard.jpg` is 2048×1536, it
 * sits in the density-less `res/drawable/` folder so the platform *upscales* it on decode, and it
 * was decoded again on nearly every navigation. The ground is now a gradient, which looks the
 * same and costs nothing.
 *
 * **It can supply the content gutter.** Previously it supplied none, so all 31 screens invented
 * their own — 4, 12, 16, 20, 24 or 28dp, and two screens had no horizontal padding at all. That
 * single omission is most of why the app looked misaligned.
 *
 * [gutter] defaults to zero only because the handover is staged: most screens still pass their
 * own `PaddingValues`, and defaulting it to [Spacing.gutter] now would *double* their padding
 * rather than fix it. As each screen is rebuilt it passes `gutter = Spacing.gutter` and drops its
 * own horizontal padding, and once none are left the default flips and this note goes away.
 *
 * [actions] takes **at most two** icons. Three text actions is what crushed the title slot on
 * Home and Pokémon; a third action belongs in an overflow menu or in the content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    gutter: Dp = 0.dp,
    content: @Composable (PaddingValues) -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(LexGround)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        }
                    },
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            },
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
        ) { inner ->
            content(inner.plusGutter(gutter))
        }
    }
}

/**
 * Adds the horizontal gutter to the Scaffold's own insets, rather than replacing them, so a
 * screen gets one set of padding that is correct on both axes and in RTL.
 */
@Composable
private fun PaddingValues.plusGutter(gutter: Dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction) + gutter,
        end = calculateEndPadding(direction) + gutter,
        top = calculateTopPadding(),
        bottom = calculateBottomPadding(),
    )
}

/**
 * A heading for a group of content, already on the gutter.
 *
 * Screens used to fight their own parent for this — `AlchemyScreen` set
 * `Modifier.align(Alignment.Start)` inside a centred `Column` and then added `padding(start = 20)`
 * to fake a gutter, which left the heading 4dp out of line with the 16dp cards it labelled.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = Spacing.md, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        action?.invoke()
    }
}

@Composable
fun LoadingBox(padding: PaddingValues = PaddingValues()) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding),
        Alignment.Center,
    ) {
        LexiconLoader()
    }
}

/**
 * The loading state, built from the logo the app already ships.
 *
 * One animation, not three. This renders on *every* screen's loading state, and three concurrent
 * infinite transitions per appearance meant the frame clock never went idle while anything was
 * loading anywhere in the app.
 */
@Composable
fun LexiconLoader(size: Dp = 88.dp) {
    val spin by rememberInfiniteTransition(label = "lexiconLoader").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(2400, easing = LinearEasing)),
        label = "spin",
    )

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { 0.22f },
            modifier = Modifier
                .fillMaxSize()
                .rotate(spin),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
            trackColor = Color.Transparent,
        )
        Image(
            painter = painterResource(R.drawable.logo_lexicon),
            contentDescription = "Loading",
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

@Composable
fun EmptyBox(padding: PaddingValues = PaddingValues(), message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding),
        Alignment.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(Spacing.xl),
        )
    }
}
