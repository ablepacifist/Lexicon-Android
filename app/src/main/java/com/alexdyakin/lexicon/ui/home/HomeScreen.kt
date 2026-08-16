package com.alexdyakin.lexicon.ui.home

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
import com.alexdyakin.lexicon.R
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

    val destinations = remember {
        listOf(
            Destination("lexicon", "Lexicon", "Audiobooks, music, video", R.drawable.logo_runed),
            Destination("alchemy", "Alchemy", "Forage, brew, consume", R.drawable.forage),
            Destination("holdfast", "Holdfast", "Manage your settlement", R.drawable.holdfast_icon),
            Destination("pokemon", "Pokémon", "Gotta chatch all them that alex has implemented", R.drawable.ic_pokeball),
            Destination("voice", "Voice", "Comunicaton hub. basically Discord clone", R.drawable.ic_microphone),
            Destination("events", "Events", "Plans and public polls", R.drawable.logo_runed),
        )
    }

    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.bg_lexicon_room),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.88f))
                    )
                )
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "Lexicon",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Welcome back, $username",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                    ),
                    actions = {
                        BadgedBox(badge = { UnreadBadge(notificationState.unreadCount) }) {
                            TextButton(onClick = { onOpen("notifications") }) { Text("Alerts") }
                        }
                        TextButton(onClick = { onOpen("profile") }) { Text("Profile") }
                        TextButton(onClick = onLogout) { Text("Sign out") }
                    },
                )
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
                items(destinations, key = { it.key }) { destination ->
                    DestinationTile(destination) { onOpen(destination.key) }
                }
            }
        }
    }
}

@Composable
private fun DestinationTile(destination: Destination, onClick: () -> Unit) {
    // Each tile drifts on its own slow cycle so the grid never looks static
    val transition = rememberInfiniteTransition(label = destination.key)
    val float by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2600 + destination.key.length * 260,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "float",
    )

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
