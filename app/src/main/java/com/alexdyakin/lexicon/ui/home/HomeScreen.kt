package com.alexdyakin.lexicon.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.Icons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import kotlinx.coroutines.awaitCancellation
import com.alexdyakin.lexicon.ui.components.LaunchedWhileResumed
import com.alexdyakin.lexicon.ui.notifications.NotificationsViewModel

/**
 * Unread pip for the Alerts action.
 *
 * Kept as its own composable because the `actions` slot is a `RowScope`, which makes
 * a bare `AnimatedVisibility` resolve to the RowScope overload and fail to compile.
 */
@Composable
private fun UnreadBadge(unreadCount: Int) {
    AnimatedVisibility(
        visible = unreadCount > 0,
        enter = scaleIn(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
        exit = scaleOut(tween(160)) + fadeOut(tween(160)),
    ) {
        Badge {
            // The count rolls up or down rather than snapping to the new value.
            AnimatedContent(
                targetState = unreadCount.coerceAtMost(99),
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInVertically { it } + fadeIn())
                            .togetherWith(slideOutVertically { -it } + fadeOut())
                    } else {
                        (slideInVertically { -it } + fadeIn())
                            .togetherWith(slideOutVertically { it } + fadeOut())
                    }
                },
                label = "unreadCount",
            ) { count -> Text(count.toString()) }
        }
    }
}

data class Destination(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    displayName: String,
    onOpen: (String) -> Unit,
    onLogout: () -> Unit,
    notificationsViewModel: NotificationsViewModel = hiltViewModel(),
) {
    val username = displayName
    val notificationState by notificationsViewModel.state.collectAsState()

    // The notification stream is open only while this screen is in front of the user; Web Push
    // covers the backgrounded case.
    LaunchedWhileResumed {
        try {
            notificationsViewModel.startStream()
            awaitCancellation()
        } finally {
            notificationsViewModel.stopStream()
        }
    }


    val destinations = remember {
        listOf(
            Destination("lexicon", "Lexicon", "Audiobooks, music, video", R.drawable.logo_runed),
            Destination("alchemy", "Alchemy", "Forage, brew, consume", R.drawable.forage),
            Destination("holdfast", "Holdfast", "Manage your settlement", R.drawable.holdfast_icon),
            Destination("pokemon", "Pokémon", "Gotta catch all them that alex has implemented", R.drawable.ic_pokeball),
            Destination("voice", "Voice", "Communication hub. basically Discord clone", R.drawable.ic_microphone),
            Destination("events", "Events", "Plans and public polls", R.drawable.logo_runed),
            Destination("lexi", "Lexi", "Voice assistant. Say “Hey Jarvis”", R.drawable.ic_lexi),
        )
    }

    // Also a hand-copy of ScreenScaffold's body, forked only because this screen has no back
    // button — which the shared scaffold has always supported via `onBack = null`.
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold(
            title = "Lexicon",
            onBack = null,
            // Two actions, as ScreenScaffold allows. Three text buttons ("Alerts", "Profile",
            // "Sign out") left the title a few characters wide on a 360dp phone.
            actions = {
                IconButton(onClick = { onOpen("notifications") }) {
                    BadgedBox(badge = { UnreadBadge(notificationState.unreadCount) }) {
                        Icon(Icons.Filled.Notifications, contentDescription = "Notifications")
                    }
                }
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = "Account")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Profile") }, onClick = { menuOpen = false; onOpen("profile") })
                        DropdownMenuItem(text = { Text("Sign out") }, onClick = { menuOpen = false; onLogout() })
                    }
                }
            },
        ) { padding ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The greeting lives here rather than in the app bar, where three
                // action buttons left the title slot too narrow to show a full name.
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Welcome back, $username",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(destinations, key = { it.key }) { destination ->
                    DestinationTile(destination) { onOpen(destination.key) }
                }
            }
        }
    }
}

@Composable
private fun DestinationTile(destination: Destination, onClick: () -> Unit) {
    // Each tile used to drift on its own `rememberInfiniteTransition` — seven of them, on the
    // app's start destination, each asking for a frame every vsync. The grid never reached an
    // idle state, so the Choreographer ran continuously for the whole time Home was open.
    //
    // The drift is gone. It is also what the system "remove animations" accessibility setting
    // asks for, which the old version ignored.
    val float = 1f

    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "press",
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            // Tall enough that a two-line subtitle is never clipped
            .heightIn(min = 210.dp)
            .scale(press)
            .clickable {
                pressed = true
                onClick()
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(96.dp)
                        .alpha(0.35f)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    Color.Transparent,
                                )
                            ),
                            shape = RoundedCornerShape(48.dp),
                        )
                )
                Image(
                    painter = painterResource(destination.icon),
                    contentDescription = null,
                    modifier = Modifier
                        .size(74.dp)
                        .scale(float)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                destination.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                destination.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
