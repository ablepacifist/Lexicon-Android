package com.alexdyakin.lexicon.ui.alchemy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
// Operator extensions behind `by` delegation. Their names never appear in the source,
// so they look unused to tooling that matches on identifiers.
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.Holdfast
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import kotlinx.coroutines.delay

@Composable
fun HoldfastScreen(onBack: () -> Unit, viewModel: HoldfastViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    // rememberSaveable throughout: a rotation or a process death used to wipe a
    // half-filled withdrawal form and reopen the screen with empty fields.
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var createGroup by rememberSaveable { mutableStateOf("") }
    var createName by rememberSaveable { mutableStateOf("") }
    var advanceDays by rememberSaveable { mutableStateOf("7") }
    var depositAmount by rememberSaveable { mutableStateOf("") }
    // WithdrawForm is not Parcelable and kotlin-parcelize is not enabled, so it is
    // saved as the five strings it actually holds.
    var withdrawForm by rememberSaveable(stateSaver = WithdrawFormSaver) { mutableStateOf(WithdrawForm()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    // The ViewModel signals a successful deposit/withdraw by bumping this counter; the
    // field text stays owned by the composable so typing never round-trips the StateFlow.
    LaunchedEffect(state.formResetToken) {
        if (state.formResetToken > 0) {
            depositAmount = ""
            withdrawForm = WithdrawForm()
        }
    }

    // Successes are transient; failures stay until dismissed or superseded.
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(4_000)
            viewModel.dismissNotice()
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false; createGroup = ""; createName = "" },
            title = { Text("Found a Holdfast") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(createGroup, { createGroup = it }, label = { Text("Group name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(createName, { createName = it }, label = { Text("Holdfast name") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.create(createGroup.trim(), createName.trim().ifBlank { "Lockwood" })
                        createGroup = ""
                        createName = ""
                        showCreate = false
                    },
                    enabled = createGroup.isNotBlank() && !state.saving,
                ) {
                    Text("Found")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false; createGroup = ""; createName = "" }) { Text("Cancel") }
            },
        )
    }

    if (confirmDelete) {
        val selected = state.selectedStatus?.holdfast
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete holdfast?") },
            text = { Text("Delete ${selected?.holdfastName ?: "this holdfast"}? This cannot be undone.") },
            confirmButton = {
                Button(onClick = { viewModel.deleteSelected(); confirmDelete = false }, enabled = !state.saving) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    ScreenScaffold(
        when {
            state.selectedStatus != null -> state.selectedStatus!!.holdfast.holdfastName
            state.selectedGroupName != null -> state.selectedGroupName!!
            else -> "Holdfasts"
        },
        {
            if (state.selectedGroupName == null) onBack() else viewModel.closeSelection()
        },
        R.drawable.bg_dashboard,
    ) { padding ->
        when {
            // Only a genuinely empty first load blanks the screen. Refreshing the list
            // after an action must not replace a detail view that is already on show.
            state.listLoading && state.holdfasts.isEmpty() && state.selectedGroupName == null ->
                LoadingBox(padding)
            state.selectedGroupName == null -> HoldfastList(
                holdfasts = state.holdfasts,
                loading = state.saving,
                error = state.error,
                notice = state.notice,
                onCreate = { showCreate = true },
                onSelect = viewModel::selectHoldfast,
                onRefresh = viewModel::refreshHoldfasts,
                padding = padding,
            )
            state.selectedStatus == null && state.detailLoading -> LoadingBox(padding)
            // Previously this fell through to LoadingBox forever, with the error only
            // rendered by composables that were not on screen: a dead spinner.
            state.selectedStatus == null -> HoldfastLoadFailed(
                groupName = state.selectedGroupName.orEmpty(),
                error = state.error,
                retrying = state.detailLoading,
                onRetry = viewModel::retrySelectedStatus,
                padding = padding,
            )
            else -> HoldfastDetail(
                state = state,
                padding = padding,
                advanceDays = advanceDays,
                onAdvanceDaysChange = { advanceDays = it },
                depositAmount = depositAmount,
                onDepositAmountChange = { depositAmount = it },
                withdrawForm = withdrawForm,
                onWithdrawFormChange = { withdrawForm = it },
                onAdvance = { viewModel.advance(advanceDays.toIntOrNull()?.coerceIn(1, 365) ?: 7) },
                onBuild = viewModel::build,
                onDeposit = viewModel::deposit,
                onWithdraw = viewModel::withdraw,
                onReplant = viewModel::replant,
                onToggleFoodMarket = viewModel::toggleFoodMarket,
                onDelete = { confirmDelete = true },
                onTabSelected = viewModel::setTab,
                onLoadEvents = viewModel::loadEvents,
            )
        }
    }
}

/** Shown when the status fetch failed and there is nothing to display yet. */
@Composable
private fun HoldfastLoadFailed(
    groupName: String,
    error: String?,
    retrying: Boolean,
    onRetry: () -> Unit,
    padding: PaddingValues,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Couldn't load ${groupName.ifBlank { "this holdfast" }}.",
            style = MaterialTheme.typography.titleMedium,
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = onRetry, enabled = !retrying) {
            Text(if (retrying) "Retrying…" else "Retry")
        }
    }
}

@Composable
private fun HoldfastList(
    holdfasts: List<Holdfast>,
    loading: Boolean,
    error: String?,
    notice: String?,
    onCreate: () -> Unit,
    onSelect: (Holdfast) -> Unit,
    onRefresh: () -> Unit,
    padding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onCreate, enabled = !loading) { Text("Found a holdfast") }
                TextButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
            }
            HoldfastBanner(notice, MaterialTheme.colorScheme.primary)
            HoldfastBanner(error, MaterialTheme.colorScheme.error)
        }
        if (holdfasts.isEmpty()) {
            item { EmptyBox(PaddingValues(32.dp), "No holdfasts have been founded.") }
        } else {
            items(holdfasts, key = { it.groupName }) { holdfast ->
                Card(Modifier.fillMaxWidth().animateItem()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(holdfast.holdfastName, style = MaterialTheme.typography.titleMedium)
                        MetricGrid(
                            items = listOf(
                                "Group" to holdfast.groupName,
                                "Day" to holdfast.daysElapsed.toString(),
                                "Pop" to holdfast.population.toString(),
                                "Gold" to formatGold(holdfast.gold),
                            )
                        )
                        Button(onClick = { onSelect(holdfast) }, enabled = !loading) { Text("Manage") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldfastDetail(
    state: HoldfastUiState,
    padding: PaddingValues,
    advanceDays: String,
    onAdvanceDaysChange: (String) -> Unit,
    depositAmount: String,
    onDepositAmountChange: (String) -> Unit,
    withdrawForm: WithdrawForm,
    onWithdrawFormChange: (WithdrawForm) -> Unit,
    onAdvance: () -> Unit,
    onBuild: (String) -> Unit,
    onDeposit: (String) -> Unit,
    onWithdraw: (WithdrawForm) -> Unit,
    onReplant: (String) -> Unit,
    onToggleFoodMarket: () -> Unit,
    onDelete: () -> Unit,
    onTabSelected: (HoldfastTab) -> Unit,
    onLoadEvents: () -> Unit,
) {
    val selected = state.selectedStatus ?: return
    val holdfast = selected.holdfast
    val tabs = listOf(HoldfastTab.STATUS, HoldfastTab.TIME, HoldfastTab.BUILD, HoldfastTab.TREASURY, HoldfastTab.LOG, HoldfastTab.HELP)
    val tabTitles = mapOf(
        HoldfastTab.STATUS to "Status",
        HoldfastTab.TIME to "Time",
        HoldfastTab.BUILD to "Build",
        HoldfastTab.TREASURY to "Treasury",
        HoldfastTab.LOG to "Log",
        HoldfastTab.HELP to "Help",
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(holdfast.holdfastName, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${holdfast.groupName} · ${holdfast.castleType.replace('_', ' ')}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onDelete, enabled = !state.saving) { Text("Delete") }
                }
                HoldfastBanner(state.notice, MaterialTheme.colorScheme.primary)
                HoldfastBanner(state.error, MaterialTheme.colorScheme.error)
            }
        }
        item {
            ScrollableTabRow(selectedTabIndex = tabs.indexOf(state.activeTab), edgePadding = 0.dp) {
                tabs.forEach { tab ->
                    Tab(
                        selected = state.activeTab == tab,
                        onClick = { onTabSelected(tab) },
                        text = {
                            Text(
                                tabTitles[tab] ?: tab.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
        when (state.activeTab) {
            // onReplant reaches the UI here for the first time. It was passed into
            // HoldfastDetail and declared as a parameter, but never referenced, so
            // POST /api/holdfast/replant was unreachable from the app.
            HoldfastTab.STATUS -> item {
                StatusTab(selected = selected, saving = state.saving, onReplant = onReplant)
            }
            HoldfastTab.TIME -> item { TimeTab(advanceDays, onAdvanceDaysChange, state.lastActionEvents, state.saving, onAdvance) }
            // Emits one list item per building card instead of ~35 in a single item.
            HoldfastTab.BUILD -> buildTab(selected, state.saving, onBuild)
            HoldfastTab.TREASURY -> item { TreasuryTab(selected, depositAmount, onDepositAmountChange, withdrawForm, onWithdrawFormChange, state.saving, onDeposit, onWithdraw, onToggleFoodMarket) }
            HoldfastTab.LOG -> item { LogTab(state.events, state.logLoading, onLoadEvents) }
            HoldfastTab.HELP -> item { HelpTab() }
        }
    }
}

/**
 * Notice/error banner that slides in and out rather than making the layout jump.
 * Keeps the last message while collapsing so the text does not vanish mid-animation.
 */
@Composable
private fun HoldfastBanner(message: String?, color: Color) {
    var lastMessage by remember { mutableStateOf(message.orEmpty()) }
    if (message != null) lastMessage = message
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Text(lastMessage, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}
