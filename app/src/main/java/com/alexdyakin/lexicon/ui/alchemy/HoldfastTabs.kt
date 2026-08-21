package com.alexdyakin.lexicon.ui.alchemy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alexdyakin.lexicon.data.Holdfast
import com.alexdyakin.lexicon.data.HoldfastBuildingMenuItem
import com.alexdyakin.lexicon.data.HoldfastEvent
import com.alexdyakin.lexicon.data.HoldfastStatus
import java.util.Locale

@Composable
internal fun StatusTab(
    selected: HoldfastStatus,
    saving: Boolean = false,
    onReplant: (String) -> Unit = {},
) {
    val holdfast = selected.holdfast
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                MetricGrid(
                    listOf(
                        "Day" to formatElapsed(holdfast.daysElapsed),
                        "Castle" to formatBuildingName(holdfast.castleType),
                        "Population" to holdfast.population.toString(),
                        "Happiness" to "${holdfast.happiness.toInt()}% (target ${holdfast.targetHappiness.toInt()}%)",
                        "Protection" to formatGold(selected.protection),
                        "Raid chance" to "${selected.raidChance}%",
                        "Raids survived" to holdfast.raidsSurvived.toString(),
                        "Net/day" to formatGold(selected.netDailyGold),
                    )
                )
                // Income and upkeep were both being received and never shown; the net
                // figure alone hides how much of it is already spoken for.
                Text(
                    "Income ${formatGold(selected.dailyIncome)}g  ·  upkeep ${formatGold(selected.dailyUpkeep)}g",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                populationTrend(selected.avgDailyGrowth, selected.populationHistory.size)?.let { trend ->
                    val arrow = when (trend) {
                        "rising" -> "↑"
                        "falling" -> "↓"
                        else -> "→"
                    }
                    Text(
                        "Population $trend $arrow  (${formatGold(selected.avgDailyGrowth)}/day)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } ?: Text(
                    "Population trend: tracking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Stores", style = MaterialTheme.typography.titleMedium)
                val need = dailyFoodNeed(holdfast.population)
                StatusChips(
                    listOf(
                        ChipSpec("Gold", formatGold(holdfast.gold), HoldfastLevel.OK),
                        ChipSpec("Food", holdfast.food.toString(), foodLevel(holdfast.food, holdfast.population)),
                        ChipSpec("Beer", holdfast.beer.toString(), HoldfastLevel.OK),
                        ChipSpec("Wine", holdfast.wine.toString(), HoldfastLevel.OK),
                        ChipSpec("Grain", holdfast.grain.toString(), HoldfastLevel.OK),
                        ChipSpec("Tools", holdfast.tools.toString(), HoldfastLevel.OK),
                        ChipSpec("Wood", holdfast.wood.toString(), HoldfastLevel.OK),
                        ChipSpec("Stone", holdfast.stone.toString(), HoldfastLevel.OK),
                        ChipSpec("Iron", holdfast.iron.toString(), HoldfastLevel.OK),
                        ChipSpec("Silver", holdfast.silver.toString(), HoldfastLevel.OK),
                    )
                )
                Text(
                    "Eats $need food/day  ·  ${formatDaysOfFood(selected.daysOfFood)} left",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (holdfast.food <= 0) {
                    Text(
                        "No food: buying rations at ${formatGold(rationCost(holdfast.population))}g/day. " +
                            "If the gold runs out too, happiness falls 3/day.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HoldfastDanger,
                    )
                }
                if (spoilLevel(selected.nextSpoilIn) != HoldfastLevel.OK) {
                    Text(
                        "Spoilage in ${formatSpoilIn(selected.nextSpoilIn)} (shelf life ${selected.foodShelfLife}d)",
                        style = MaterialTheme.typography.bodySmall,
                        color = levelColor(spoilLevel(selected.nextSpoilIn)),
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Built structures", style = MaterialTheme.typography.titleMedium)
                // The map carries every known building type, most of them at zero, which
                // buried the handful actually built under ~30 rows of "×0".
                val built = holdfast.buildings.filterValues { it > 0 }.entries.sortedBy { it.key }
                if (built.isEmpty()) {
                    Text("No buildings yet.")
                } else {
                    built.forEach { (type, count) -> Text("${formatBuildingName(type)} ×$count") }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Growing crops", style = MaterialTheme.typography.titleMedium)
                val planted = HOLDFAST_CROPS.filter { it.plantDays(holdfast).isNotEmpty() }
                if (planted.isEmpty()) {
                    Text("Nothing is planted.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    planted.forEach { crop -> CropRows(crop, selected) }
                }
            }
        }

        // Only annuals go fallow, and only when a built field has no crop in it.
        val fallow = HOLDFAST_CROPS.filter { fallowCount(holdfast, it) > 0 }
        AnimatedVisibility(
            visible = fallow.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Fallow fields", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Annual crops do not replant themselves after harvest.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    fallow.forEach { crop ->
                        val count = fallowCount(holdfast, crop)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${crop.label}: $count need replanting",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = { onReplant(crop.type) },
                                enabled = !saving,
                            ) {
                                Text("Replant — ${replantCost(holdfast, crop)}g")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One labelled group of fields, with a growth bar per planted field. */
@Composable
private fun CropRows(crop: CropKind, selected: HoldfastStatus) {
    val holdfast = selected.holdfast
    val days = crop.plantDays(holdfast)
    val harvestDays = harvestDaysFor(crop, selected)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${crop.label} (${days.size})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (harvestDays == null) {
            // Vineyards: no meaningful harvest, so state the production rate instead.
            val item = selected.buildingMenu.firstOrNull { it.type == crop.type }
            val amount = item?.productionAmount
            val every = item?.productionDays
            Text(
                if (amount != null && every != null) {
                    "Produces $amount ${item.productionItem.orEmpty()} each, every ${every}d"
                } else {
                    "Produces on its own schedule"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        days.sorted().forEachIndexed { index, plantDay ->
            val target = cropProgress(holdfast.daysElapsed, plantDay, harvestDays)
            val progress by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(900, easing = FastOutSlowInEasing),
                label = "crop-${crop.type}-$index",
            )
            val grown = cropGrownDays(holdfast.daysElapsed, plantDay, harvestDays)
            val ready = target >= 1f
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f),
                    color = if (ready) HoldfastOk else MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (ready) "ready" else "$grown/${harvestDays}d",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun TimeTab(advanceDays: String, onAdvanceDaysChange: (String) -> Unit, events: List<String>, saving: Boolean, onAdvance: () -> Unit) {
    // The server rejects anything outside 1..365 (HoldfastController.java:78), so say so
    // here rather than letting the request round-trip to a rejection.
    val parsed = advanceDays.toIntOrNull()
    val valid = parsed != null && parsed in 1..365

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Advance time", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    advanceDays,
                    onAdvanceDaysChange,
                    label = { Text("Days to advance") },
                    singleLine = true,
                    isError = advanceDays.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (advanceDays.isNotBlank() && !valid) {
                    Text("Enter a whole number of days, 1 to 365.", style = MaterialTheme.typography.bodySmall, color = HoldfastDanger)
                }
                Button(onClick = onAdvance, enabled = !saving && valid) {
                    Text(if (valid) "Advance ${parsed} day(s)" else "Advance")
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Latest advance events", style = MaterialTheme.typography.titleMedium)
                if (events.isEmpty()) {
                    Text("No recent advance events.")
                } else {
                    events.forEach { event -> EventLine(event) }
                }
            }
        }
    }
}

/**
 * One event line, coloured by severity. Shared by the Time and Log tabs so the two
 * cannot drift apart on what counts as a raid, a famine or a good harvest.
 */
@Composable
internal fun EventLine(message: String, prefix: String? = null) {
    val notable = eventIsNotable(message)
    Text(
        if (prefix != null) "$prefix $message" else message,
        style = MaterialTheme.typography.bodySmall,
        color = if (notable) levelColor(eventLevel(message)) else MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * Emits the Build tab straight into the parent list.
 *
 * This one tab renders ~35 cards. As a single `item { }` the whole lot composed
 * eagerly on every recomposition; emitting one item per card lets the list do its job.
 * The other tabs are small enough to stay as a single item.
 */
internal fun LazyListScope.buildTab(
    selected: HoldfastStatus,
    saving: Boolean,
    onBuild: (String) -> Unit,
) {
    val holdfast = selected.holdfast
    // Web order, and grouping means an unexpected status can never be dropped silently.
    val groups = selected.buildingMenu.groupBy { it.status }
    val sections = listOf(
        "available" to "Available",
        "maxed" to "Maxed out",
        "locked" to "Locked",
    )
    val known = sections.map { it.first }.toSet()

    sections.forEach { (status, title) ->
        val items = groups[status].orEmpty()
        if (items.isEmpty()) return@forEach
        item(key = "section-$status") {
            Text("$title (${items.size})", style = MaterialTheme.typography.titleMedium)
        }
        items(items, key = { "build-${it.type}" }) { menuItem ->
            BuildCard(menuItem, holdfast, saving, onBuild)
        }
    }

    // Anything the server starts sending with a new status still shows up.
    val other = groups.filterKeys { it !in known }.values.flatten()
    if (other.isNotEmpty()) {
        item(key = "section-other") {
            Text("Other (${other.size})", style = MaterialTheme.typography.titleMedium)
        }
        items(other, key = { "build-other-${it.type}" }) { menuItem ->
            BuildCard(menuItem, holdfast, saving, onBuild)
        }
    }
}

@Composable
private fun BuildCard(
    item: HoldfastBuildingMenuItem,
    holdfast: Holdfast,
    saving: Boolean,
    onBuild: (String) -> Unit,
) {
    val buildable = item.status == "available"
    val affordable = holdfast.canAfford(item.resourceCost) && holdfast.gold >= item.cost
    // Deliberately NOT dimmed with alpha. These cards sit on a busy illustrated
    // background, and fading them made the text compete with the map. An explicit
    // line says the same thing and stays readable. The button stays live either way:
    // the server's refusal names the exact shortfall better than a dead control.
    val short = buildable && !affordable

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (item.max != null) "${item.current} / ${item.max}" else "${item.current}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.description.isNotBlank()) {
                Text(item.description, style = MaterialTheme.typography.bodySmall)
            }

            BuildingEffects(item)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${item.cost}g",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (buildable && holdfast.gold < item.cost) HoldfastDanger else MaterialTheme.colorScheme.onSurface,
                )
                // A carpenter discounts every build; show what it saved.
                if (item.baseCost > item.cost) {
                    Text(
                        "${item.baseCost}g",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = TextDecoration.LineThrough,
                    )
                }
            }

            item.resourceCost?.takeIf { it.isNotEmpty() }?.let { costs ->
                StatusChips(
                    costs.entries.sortedBy { it.key }.map { (resource, needed) ->
                        val have = holdfast.resourceAmount(resource)
                        ChipSpec(
                            label = resource.replaceFirstChar { it.uppercase() },
                            value = "$have/$needed",
                            level = if (have >= needed) HoldfastLevel.OK else HoldfastLevel.DANGER,
                        )
                    }
                )
            }

            item.lockReason?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = HoldfastWarn)
            }

            if (short) {
                Text(
                    if (holdfast.gold < item.cost) "Not enough gold yet" else "Not enough materials yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = HoldfastWarn,
                )
            }
            if (buildable) {
                Button(onClick = { onBuild(item.type) }, enabled = !saving) { Text("Build") }
            }
        }
    }
}

/** Per-day and per-harvest effects, all from fields the menu already carries. */
@Composable
private fun BuildingEffects(item: HoldfastBuildingMenuItem) {
    val badges = buildList {
        if (item.dailySilver > 0) add("+${trimNumber(item.dailySilver)} silver/day")
        if (item.dailyUpkeep > 0) add("-${trimNumber(item.dailyUpkeep)} upkeep/day")
        if (item.happiness != 0.0) add("${if (item.happiness > 0) "+" else ""}${trimNumber(item.happiness)} happiness")
        val food = item.harvestFood
        val days = item.harvestDays
        if (food != null && food > 0 && days != null && days > 0) add("+$food food / ${days}d")
        val gold = item.harvestGold
        if (gold != null && gold > 0 && days != null && days > 0) add("+${trimNumber(gold)}g / ${days}d")
        val amount = item.productionAmount
        val pDays = item.productionDays
        val pItem = item.productionItem
        if (amount != null && amount > 0 && pDays != null && pDays > 0 && !pItem.isNullOrBlank()) {
            add("+$amount $pItem / ${pDays}d")
        }
    }
    if (badges.isEmpty()) return
    Text(
        badges.joinToString("  ·  "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}


@Composable
internal fun TreasuryTab(
    selected: HoldfastStatus,
    depositAmount: String,
    onDepositAmountChange: (String) -> Unit,
    withdrawForm: WithdrawForm,
    onWithdrawFormChange: (WithdrawForm) -> Unit,
    saving: Boolean,
    onDeposit: (String) -> Unit,
    onWithdraw: (WithdrawForm) -> Unit,
    onToggleFoodMarket: () -> Unit,
) {
    val holdfast = selected.holdfast
    val markets = holdfast.buildings["food_market"] ?: 0

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Treasury", style = MaterialTheme.typography.titleMedium)
                Text("Gold ${formatGold(holdfast.gold)}  ·  silver ${holdfast.silver}")
                Text("Beer ${holdfast.beer}  ·  wine ${holdfast.wine}  ·  grain ${holdfast.grain}  ·  tools ${holdfast.tools}")
                Text("Food ${holdfast.food}  ·  wood ${holdfast.wood}  ·  stone ${holdfast.stone}  ·  iron ${holdfast.iron}")
            }
        }

        // Only meaningful with a market built; the server ignores the flag otherwise
        // (HoldfastManager.java:213 requires food_market > 0).
        if (markets > 0) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Food market", style = MaterialTheme.typography.titleMedium)
                    // status.foodMarketEnabled is the value the server recomputes.
                    val enabled = selected.foodMarketEnabled
                    Text(
                        if (enabled) "Selling surplus food" else "Not selling",
                        color = if (enabled) HoldfastOk else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Up to ${foodMarketDailyCap(markets)} food/day → ${formatGold(foodMarketDailyGold(markets))}g/day",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Keeps a reserve of ${foodMarketReserve(holdfast.population)} food; " +
                            "shelf life ${selected.foodShelfLife}d.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (enabled) {
                        val today = foodMarketSellable(holdfast.food, holdfast.population, markets)
                        Text(
                            if (today > 0) "Today it would sell $today food." else "Nothing above the reserve to sell today.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (today > 0) HoldfastOk else HoldfastWarn,
                        )
                    }
                    Button(onClick = onToggleFoodMarket, enabled = !saving) {
                        Text(if (enabled) "Stop selling" else "Start selling")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Deposit gold", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    depositAmount,
                    onDepositAmountChange,
                    label = { Text("Gold") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onDeposit(depositAmount) },
                    enabled = !saving && (depositAmount.toDoubleOrNull() ?: 0.0) > 0.0,
                ) { Text("Deposit") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Withdraw resources", style = MaterialTheme.typography.titleMedium)
                ResourceFieldRow("Gold", withdrawForm.gold) { onWithdrawFormChange(withdrawForm.copy(gold = it)) }
                ResourceFieldRow("Beer", withdrawForm.beer) { onWithdrawFormChange(withdrawForm.copy(beer = it)) }
                ResourceFieldRow("Wine", withdrawForm.wine) { onWithdrawFormChange(withdrawForm.copy(wine = it)) }
                ResourceFieldRow("Grain", withdrawForm.grain) { onWithdrawFormChange(withdrawForm.copy(grain = it)) }
                ResourceFieldRow("Tools", withdrawForm.tools) { onWithdrawFormChange(withdrawForm.copy(tools = it)) }
                Button(
                    onClick = { onWithdraw(withdrawForm) },
                    enabled = !saving && withdrawForm.isValid,
                ) { Text("Withdraw") }
            }
        }
    }
}

@Composable
internal fun LogTab(events: List<HoldfastEvent>, loading: Boolean, onLoadEvents: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (events.isEmpty()) "Event history" else "Event history (${events.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = onLoadEvents, enabled = !loading) {
                        Text(if (loading) "Refreshing…" else "Refresh")
                    }
                }
                when {
                    loading && events.isEmpty() -> Text("Loading…")
                    events.isEmpty() -> Text("Nothing has happened yet.")
                    // Newest first: the interesting end of a long settlement history.
                    else -> events.asReversed().forEach { event ->
                        EventLine(event.message, prefix = "d${event.day}")
                    }
                }
            }
        }
    }
}

@Composable
internal fun HelpTab() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HelpCard(
            "Money",
            "Your holdfast earns a base 40g a day, plus 1g for every 10 people. Buildings add " +
                "silver on top, which markets multiply. Upkeep is subtracted from that, and the " +
                "Status tab shows income and upkeep separately so you can see how much of the " +
                "take is already committed.",
        )
        HelpCard(
            "Food",
            "Everyone eats. The settlement consumes a fifth of a unit per person per day, rounded " +
                "up, so 40 people eat 8 a day. Run out and the treasury buys emergency rations at " +
                "2g a head. Run out of gold as well and it is famine: happiness drops 3 a day " +
                "until you fix it.\n\nStored food keeps for 30 days, plus 15 more for every " +
                "granary. Anything older spoils.",
        )
        HelpCard(
            "Crops",
            "Wheat, rye and vegetable gardens are annuals: they yield once and then sit fallow " +
                "until you replant them, which costs seed money. The Status tab lists anything " +
                "fallow with a button to replant the lot.\n\nOrchards, vineyards, berry patches " +
                "and mushroom caves replant themselves. Vineyards are the odd one out — they make " +
                "wine on a seven-day cycle rather than a harvest.",
        )
        HelpCard(
            "People",
            "Happiness drifts toward a target set by your buildings and worked against by " +
                "crowding. Once it holds at 65 or better the settlement grows each week, and " +
                "churches, hospitals and aqueducts each add to how many arrive.",
        )
        HelpCard(
            "Raids",
            "Protection starts at 50 and rises with your castle, guard towers, blacksmiths, stone " +
                "walls and a keep. A large population and a fat daily income both work against " +
                "it. Raid chance runs from 8% down to a floor of 0.5% as protection climbs.\n\n" +
                "A raid steals gold, can level buildings and costs lives. Walls and the keep " +
                "cannot be destroyed.",
        )
        HelpCard(
            "Selling food",
            "Build a food market and it will sell surplus for 0.8g a unit, up to 5 a day per " +
                "market. It always holds back a reserve so trading cannot starve the settlement.",
        )
    }
}

@Composable
private fun HelpCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}


@Composable
internal fun ResourceFieldRow(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun MetricGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { (label, value) ->
                    MetricPill(label, value, Modifier.weight(1f))
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun formatBuildingName(type: String): String = type.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

// Locale.US is deliberate: a default-locale format yields "12,5" in much of Europe,
// which reads as a thousands separator next to the plain integer counts beside it.
internal fun formatGold(value: Double): String = String.format(Locale.US, "%.1f", value)

/** The server caps this at 999 to mean "not a concern". */
internal fun formatDaysOfFood(days: Int): String = if (days >= 999) "plenty" else "$days day(s)"

/** The server sends -1 when there is nothing in store to spoil. */
internal fun formatSpoilIn(days: Int): String = if (days < 0) "—" else "$days day(s)"

/** A single labelled value with a severity colour. */
internal data class ChipSpec(val label: String, val value: String, val level: HoldfastLevel)

internal fun levelColor(level: HoldfastLevel): Color = when (level) {
    HoldfastLevel.OK -> HoldfastOk
    HoldfastLevel.WARN -> HoldfastWarn
    HoldfastLevel.DANGER -> HoldfastDanger
}

/**
 * Wrapping row of value chips. Replaces the fixed two-per-row grid for resources so a
 * warning colour reads as a property of one value rather than of half a row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusChips(chips: List<ChipSpec>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            val tint = levelColor(chip.level)
            val emphasised = chip.level != HoldfastLevel.OK
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (emphasised) tint.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                border = if (emphasised) BorderStroke(1.dp, tint.copy(alpha = 0.6f)) else null,
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        chip.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        chip.value,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (emphasised) tint else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** Drops a pointless ".0" so badges read "+2 silver/day", not "+2.0 silver/day". */
internal fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else formatGold(value)
