package com.alexdyakin.lexicon.ui.alchemy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.Ingredient
import com.alexdyakin.lexicon.data.Potion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlchemyScreen(
    onBack: () -> Unit,
    onOpenKnowledge: () -> Unit,
    viewModel: AlchemyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var firstIngredientId by remember { mutableStateOf<Int?>(null) }
    var secondIngredientId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.error, state.message) {
        val text = state.error ?: state.message
        if (text != null) {
            snackbarHost.showSnackbar(text)
            viewModel.clearMessages()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.bg_dashboard),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.85f))
                    )
                )
        )

        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHost) },
            topBar = {
                TopAppBar(
                    title = { Text("Alchemy") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = { TextButton(onClick = onOpenKnowledge) { Text("Book") } },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                        navigationIconContentColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ForageStation(
                    phase = state.foragePhase,
                    found = state.foundIngredient,
                    onForage = viewModel::forage,
                    onDismiss = viewModel::dismissForageResult,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "Ingredients",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(start = 20.dp, bottom = 6.dp),
                )

                if (state.loading) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (state.inventory.ingredients.isEmpty() && state.inventory.potions.isEmpty()) {
                    Text(
                        "Nothing gathered yet. Forage to fill your satchel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.inventory.ingredients.isNotEmpty()) item {
                            BrewPanel(
                                first = state.inventory.ingredients.firstOrNull { it.id == firstIngredientId }?.name,
                                second = state.inventory.ingredients.firstOrNull { it.id == secondIngredientId }?.name,
                                enabled = firstIngredientId != null && secondIngredientId != null,
                                onBrew = {
                                    viewModel.brew(firstIngredientId!!, secondIngredientId!!)
                                    firstIngredientId = null
                                    secondIngredientId = null
                                },
                            )
                        }
                        items(state.inventory.ingredients, key = { it.id }) { ingredient ->
                            IngredientCard(
                                ingredient = ingredient,
                                onConsume = { viewModel.consume(ingredient.id, ingredient.name) },
                                selected = ingredient.id == firstIngredientId || ingredient.id == secondIngredientId,
                                onSelect = {
                                    when {
                                        firstIngredientId == ingredient.id -> firstIngredientId = null
                                        secondIngredientId == ingredient.id -> secondIngredientId = null
                                        firstIngredientId == null -> firstIngredientId = ingredient.id
                                        secondIngredientId == null -> secondIngredientId = ingredient.id
                                        else -> { firstIngredientId = secondIngredientId; secondIngredientId = ingredient.id }
                                    }
                                },
                            )
                        }
                        if (state.inventory.potions.isNotEmpty()) {
                            item { Text("Potions", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                            items(state.inventory.potions, key = { "potion-${it.id}" }) { potion ->
                                PotionCard(potion) { viewModel.consumePotion(potion.id, potion.name) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The forage button and its result.
 *
 * Idle      — the satchel icon breathes gently, inviting a tap.
 * Searching — it rocks side to side while the request is in flight.
 * Found     — the result card springs in over a burst of light.
 */
@Composable
private fun ForageStation(
    phase: ForagePhase,
    found: String?,
    onForage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "forage")

    val breathe by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    val rock by transition.animateFloat(
        initialValue = -11f,
        targetValue = 11f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rock",
    )

    val glow by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    val searching = phase == ForagePhase.Searching

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Warm halo behind the icon, brighter while searching
        Box(
            Modifier
                .size(210.dp)
                .alpha(if (searching) glow else glow * 0.5f)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                            Color.Transparent,
                        )
                    ),
                    shape = MaterialTheme.shapes.extraLarge,
                )
        )

        Image(
            painter = painterResource(R.drawable.forage),
            contentDescription = "Forage",
            modifier = Modifier
                .size(168.dp)
                .scale(if (searching) 1.05f else breathe)
                .rotate(if (searching) rock else 0f)
                .clickable(enabled = phase == ForagePhase.Idle) { onForage() },
        )

        // Result springs in and overlays the station until dismissed
        AnimatedVisibility(
            visible = phase == ForagePhase.Found,
            enter = fadeIn(tween(220)) + scaleIn(
                initialScale = 0.6f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            ),
            exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.85f),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 10.dp,
                modifier = Modifier.clickable { onDismiss() },
            ) {
                Column(
                    Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Foraged",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        found.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "tap to dismiss",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        if (phase == ForagePhase.Idle) {
            Text(
                "Tap to forage",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun IngredientCard(ingredient: Ingredient, onConsume: () -> Unit, selected: Boolean, onSelect: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            )
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.consume),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ingredient.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val knownEffectsSummary = if (ingredient.effects.isEmpty()) {
                        "Known effects: unknown"
                    } else {
                        "Known effects: ${ingredient.effects.joinToString { it.title }}"
                    }
                    Text(
                        "Quantity: ${ingredient.quantity} · $knownEffectsSummary",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onConsume) { Text("Eat") }
                TextButton(onClick = onSelect) { Text(if (selected) "Selected" else "Brew") }
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                if (ingredient.effects.isEmpty()) {
                    Text(
                        "Known effects: unknown",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ingredient.effects.forEach { effect ->
                        Text(
                            "• ${effect.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (effect.description.isNotBlank()) {
                            Text(
                                effect.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrewPanel(first: String?, second: String?, enabled: Boolean, onBrew: () -> Unit) = Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .9f)),
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
) {
    Column(Modifier.padding(16.dp)) {
        Text("Brew a potion", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("${first ?: "Choose an ingredient"} + ${second ?: "choose another"}", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onBrew, enabled = enabled, modifier = Modifier.padding(top = 10.dp)) { Text("Brew") }
    }
}

@Composable
private fun PotionCard(potion: Potion, onConsume: () -> Unit) = Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .9f)),
    modifier = Modifier.fillMaxWidth(),
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.drink), null, Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(potion.name, style = MaterialTheme.typography.titleSmall)
                Text("Quantity: ${potion.quantity}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onConsume) { Text("Drink") }
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Text(
                potion.description.ifBlank { "No description." },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Duration: ${"%.1f".format(potion.duration)} min · Brew level: ${potion.brewLevel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Dice: ${potion.dice.ifBlank { "None" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (potion.effects.isEmpty()) {
                Text(
                    "Effects: unknown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Text(
                    "Effects:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                potion.effects.forEach { effect ->
                    Text(
                        "• ${effect.title}: ${effect.description}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
