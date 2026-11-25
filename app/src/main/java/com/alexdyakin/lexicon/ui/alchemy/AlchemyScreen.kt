package com.alexdyakin.lexicon.ui.alchemy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.Effect
import com.alexdyakin.lexicon.data.Ingredient
import com.alexdyakin.lexicon.data.Potion
import com.alexdyakin.lexicon.ui.components.ScreenScaffold

/**
 * Layout, top to bottom:
 *  - one scrolling list: the forage station, what happened this visit, ingredients, potions;
 *  - the brew bar, pinned under the list, so two ingredients can be picked anywhere in a long
 *    list and brewed without scrolling back up.
 *
 * Forage and brew results open a dialog that stays until it is closed. A snackbar used to
 * carry them, and it was gone before most people noticed it. Eating and drinking still use
 * the snackbar (they are expected), and also land in the "This visit" list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlchemyScreen(
    onBack: () -> Unit,
    onOpenKnowledge: () -> Unit,
    viewModel: AlchemyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.error, state.message) {
        val text = state.error ?: state.message
        if (text != null) {
            snackbarHost.showSnackbar(text)
            viewModel.clearMessages()
        }
    }

    if (state.askingForagePermission) {
        ForagePermissionDialog(onAnswer = viewModel::answerForagePermission)
    }
    state.outcome?.let { outcome ->
        OutcomeDialog(outcome, onDismiss = viewModel::dismissOutcome)
    }

    ScreenScaffold(
        title = "Alchemy",
        onBack = onBack,
        actions = { TextButton(onClick = onOpenKnowledge) { Text("Book") } },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "forage") {
                    ForageStation(phase = state.foragePhase, onForage = viewModel::requestForage)
                }

                if (state.recent.isNotEmpty()) {
                    item(key = "recent") { RecentCard(state.recent) }
                }

                item(key = "ingredients-title") { SectionTitle("Ingredients") }

                when {
                    state.loading && state.inventory.ingredients.isEmpty() -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    state.inventory.ingredients.isEmpty() -> item(key = "empty") {
                        Text(
                            "Nothing gathered yet. Forage to fill your satchel.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                    else -> items(state.inventory.ingredients, key = { it.id }) { ingredient ->
                        IngredientCard(
                            ingredient = ingredient,
                            selected = state.isSelected(ingredient.id),
                            onConsume = { viewModel.consume(ingredient.id, ingredient.name) },
                            onSelect = { viewModel.toggleIngredient(ingredient.id) },
                        )
                    }
                }

                if (state.inventory.potions.isNotEmpty()) {
                    item(key = "potions-title") { SectionTitle("Potions", Modifier.padding(top = 8.dp)) }
                    items(state.inventory.potions, key = { "potion-${it.id}" }) { potion ->
                        PotionCard(potion) { viewModel.consumePotion(potion.id, potion.name) }
                    }
                }
            }

            if (state.inventory.ingredients.isNotEmpty()) {
                BrewBar(
                    first = state.inventory.ingredients.firstOrNull { it.id == state.firstIngredientId }?.name,
                    second = state.inventory.ingredients.firstOrNull { it.id == state.secondIngredientId }?.name,
                    enabled = state.canBrew,
                    brewing = state.brewing,
                    onBrew = viewModel::brew,
                    onClear = viewModel::clearBrewSelection,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/**
 * The satchel and the Forage button.
 *
 * The picture is decoration only; it used to be the button, and at 168dp it was hit by
 * accident while scrolling. Foraging now takes a normal-sized button, then a yes to
 * "Did Alex say you could forage?".
 */
@Composable
private fun ForageStation(phase: ForagePhase, onForage: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "forage")
    val breathe by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val rock by transition.animateFloat(
        initialValue = -11f,
        targetValue = 11f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "rock",
    )
    val glow by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    val searching = phase == ForagePhase.Searching

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(104.dp)
                        .alpha(if (searching) glow else glow * 0.5f)
                        .background(
                            Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), Color.Transparent)),
                            shape = CircleShape,
                        )
                )
                Image(
                    painter = painterResource(R.drawable.forage),
                    contentDescription = null,
                    modifier = Modifier
                        .size(88.dp)
                        .scale(if (searching) 1.05f else breathe)
                        .rotate(if (searching) rock else 0f),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Forage", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (searching) "Searching the undergrowth…" else "Gather a new ingredient, with the DM's say-so.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onForage, enabled = !searching) {
                    Text(if (searching) "Foraging…" else "Forage")
                }
            }
        }
    }
}

/** The one decision the app makes itself: foraging only with the DM's permission. */
@Composable
private fun ForagePermissionDialog(onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        icon = { Image(painterResource(R.drawable.forage), contentDescription = null, modifier = Modifier.size(48.dp)) },
        title = { Text("Did Alex say you could forage?") },
        confirmButton = { Button(onClick = { onAnswer(true) }) { Text("Yes") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("No") } },
    )
}

/** A forage or brew result. Stays until closed, so it cannot be missed. */
@Composable
private fun OutcomeDialog(outcome: AlchemyOutcome, onDismiss: () -> Unit) {
    val (heading, icon) = when (outcome.kind) {
        OutcomeKind.FORAGED -> "You found" to R.drawable.forage
        OutcomeKind.BREWED -> "You brewed" to R.drawable.drink
        OutcomeKind.BREW_FAILED -> "The brew failed" to R.drawable.consume
    }
    val failed = outcome.kind == OutcomeKind.BREW_FAILED
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(64.dp)) },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(heading, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    outcome.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (outcome.detail.isNotBlank()) {
                    Text(outcome.detail, style = MaterialTheme.typography.bodyMedium)
                }
                if (outcome.effects.isNotEmpty()) {
                    Text("Effects", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    outcome.effects.forEach { EffectLine(it) }
                } else if (outcome.kind == OutcomeKind.FORAGED) {
                    Text(
                        "Its effects are still unknown. Brew with it or eat it to learn them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(if (failed) "OK" else "Nice") } },
    )
}

@Composable
private fun EffectLine(effect: Effect) {
    Column {
        Text("• ${effect.title}", style = MaterialTheme.typography.bodyMedium)
        if (effect.description.isNotBlank()) {
            Text(
                effect.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** What happened this visit, so a result is still visible after its dialog is closed. */
@Composable
private fun RecentCard(lines: List<AlchemyLogLine>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("This visit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            lines.forEach { line ->
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (line.kind == OutcomeKind.BREW_FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Pinned under the list. Shows the two chosen ingredients and brews them, wherever the
 * player has scrolled to.
 */
@Composable
private fun BrewBar(
    first: String?,
    second: String?,
    enabled: Boolean,
    brewing: Boolean,
    onBrew: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Brew a potion", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "${first ?: "Pick an ingredient"} + ${second ?: if (first == null) "another" else "pick another"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedVisibility(
                    visible = first == null && second == null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Text(
                        "Tap Brew on two ingredients in the list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
            }
            if (first != null || second != null) {
                TextButton(onClick = onClear, enabled = !brewing) { Text("Clear") }
            }
            Button(onClick = onBrew, enabled = enabled) { Text(if (brewing) "Brewing…" else "Brew") }
        }
    }
}

@Composable
private fun IngredientCard(ingredient: Ingredient, selected: Boolean, onConsume: () -> Unit, onSelect: () -> Unit) {
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
                if (selected) {
                    Button(onClick = onSelect) { Text("Chosen") }
                } else {
                    TextButton(onClick = onSelect) { Text("Brew") }
                }
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
                    ingredient.effects.forEach { EffectLine(it) }
                }
            }
        }
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
