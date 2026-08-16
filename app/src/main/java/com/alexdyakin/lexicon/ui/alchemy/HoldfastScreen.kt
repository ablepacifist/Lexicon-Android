package com.alexdyakin.lexicon.ui.alchemy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.AdvanceHoldfastRequest
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.BuildHoldfastRequest
import com.alexdyakin.lexicon.data.CreateHoldfastRequest
import com.alexdyakin.lexicon.data.DepositHoldfastRequest
import com.alexdyakin.lexicon.data.Holdfast
import com.alexdyakin.lexicon.data.HoldfastBuildingMenuItem
import com.alexdyakin.lexicon.data.HoldfastEvent
import com.alexdyakin.lexicon.data.HoldfastStatus
import com.alexdyakin.lexicon.data.ReplantHoldfastRequest
import com.alexdyakin.lexicon.data.ToggleFoodMarketRequest
import com.alexdyakin.lexicon.data.WithdrawHoldfastRequest
import com.alexdyakin.lexicon.data.api.AlchemyApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HoldfastTab { STATUS, TIME, BUILD, TREASURY, LOG, HELP }

data class WithdrawForm(
    val gold: String = "",
    val beer: String = "",
    val wine: String = "",
    val grain: String = "",
    val tools: String = "",
)

data class HoldfastUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val holdfasts: List<Holdfast> = emptyList(),
    val selectedGroupName: String? = null,
    val selectedStatus: HoldfastStatus? = null,
    val activeTab: HoldfastTab = HoldfastTab.STATUS,
    val events: List<HoldfastEvent> = emptyList(),
    val logLoading: Boolean = false,
    val lastActionEvents: List<String> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
)

@HiltViewModel
class HoldfastViewModel @Inject constructor(private val api: AlchemyApi) : ViewModel() {
    private val _state = MutableStateFlow(HoldfastUiState())
    val state = _state.asStateFlow()

    init {
        refreshHoldfasts()
    }

    fun closeSelection() {
        _state.value = _state.value.copy(
            selectedGroupName = null,
            selectedStatus = null,
            activeTab = HoldfastTab.STATUS,
            events = emptyList(),
            lastActionEvents = emptyList(),
        )
    }

    fun refreshHoldfasts() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        when (val result = safeApiCall { api.holdfasts() }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(loading = false, holdfasts = result.data)
                _state.value.selectedGroupName?.let { refreshSelectedStatus(it) }
            }
            is ApiResult.Failure -> _state.value = _state.value.copy(loading = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(loading = false, error = "Action was rejected.")
        }
    }

    fun selectHoldfast(holdfast: Holdfast) = viewModelScope.launch {
        _state.value = _state.value.copy(
            selectedGroupName = holdfast.groupName,
            selectedStatus = null,
            activeTab = HoldfastTab.STATUS,
            events = emptyList(),
            lastActionEvents = emptyList(),
            notice = null,
            error = null,
        )
        refreshSelectedStatus(holdfast.groupName)
    }

    fun setTab(tab: HoldfastTab) {
        _state.value = _state.value.copy(activeTab = tab)
        if (tab == HoldfastTab.LOG) {
            val groupName = _state.value.selectedGroupName ?: return
            if (_state.value.events.isEmpty()) {
                loadEvents(groupName)
            }
        }
    }

    fun create(groupName: String, holdfastName: String) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true, notice = null, error = null)
        when (val result = safeApiCall { api.createHoldfast(CreateHoldfastRequest(groupName, holdfastName)) }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(saving = false)
                refreshHoldfasts()
                selectHoldfast(result.data)
            }
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Action was rejected.")
        }
    }

    fun advance(days: Int) = viewModelScope.launch {
        val groupName = _state.value.selectedGroupName ?: return@launch
        _state.value = _state.value.copy(saving = true, notice = null, error = null, lastActionEvents = emptyList())
        when (val result = safeApiCall { api.advanceHoldfast(AdvanceHoldfastRequest(groupName, days)) }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(saving = false, selectedStatus = result.data, lastActionEvents = result.data.events, notice = "Time advanced.")
                refreshHoldfasts()
            }
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Action was rejected.")
        }
    }

    fun build(buildingType: String) = viewModelScope.launch {
        val groupName = _state.value.selectedGroupName ?: return@launch
        _state.value = _state.value.copy(saving = true, notice = null, error = null)
        when (val result = safeApiCall { api.buildHoldfast(BuildHoldfastRequest(groupName, buildingType)) }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(saving = false, selectedStatus = result.data, notice = result.data.message.ifBlank { "Building updated." })
                refreshHoldfasts()
            }
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Action was rejected.")
        }
    }

    fun deposit(amount: String) = viewModelScope.launch {
        val groupName = _state.value.selectedGroupName ?: return@launch
        val gold = amount.toDoubleOrNull() ?: 0.0
        if (gold <= 0.0) {
            _state.value = _state.value.copy(error = "Enter a positive gold amount.")
            return@launch
        }
        _state.value = _state.value.copy(saving = true, notice = null, error = null)
        when (val result = safeApiCall { api.depositHoldfast(DepositHoldfastRequest(groupName, gold)) }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(saving = false, notice = "Deposited ${formatGold(gold)}.")
                refreshSelectedStatus(groupName)
                refreshHoldfasts()
            }
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Action was rejected.")
        }
    }

    fun withdraw(form: WithdrawForm) = viewModelScope.launch {
        val groupName = _state.value.selectedGroupName ?: return@launch
        _state.value = _state.value.copy(saving = true, notice = null, error = null)
        val result = safeApiCall {
            api.withdrawHoldfast(
                WithdrawHoldfastRequest(
                    groupName = groupName,
                    gold = form.gold.toDoubleOrNull() ?: 0.0,
                    beer = form.beer.toIntOrNull() ?: 0,
                    wine = form.wine.toIntOrNull() ?: 0,
                    grain = form.grain.toIntOrNull() ?: 0,
                    tools = form.tools.toIntOrNull() ?: 0,
                ),
            )
        }
        when (result) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(saving = false, notice = result.data.message.ifBlank { "Resources withdrawn." })
                refreshSelectedStatus(groupName)
                refreshHoldfasts()
            }
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Action was rejected.")
        }
    }

    fun replant(fieldType: String) = viewModelScope.launch {
        val groupName = _state.value.selectedGroupName ?: return@launch
        _state.value = _state.value.copy(saving = true, notice = null, error = null)
        when (val result = safeApiCall { api.replantHoldfast(ReplantHoldfastRequest(groupName, fieldType)) }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(saving = false, selectedStatus = result.data, notice = result.data.message.ifBlank { "Field replanted." })
                refreshHoldfasts()
            }
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Action was rejected.")
        }
    }

    fun toggleFoodMarket() = viewModelScope.launch {
        val groupName = _state.value.selectedGroupName ?: return@launch
        _state.value = _state.value.copy(saving = true, notice = null, error = null)
        when (val result = safeApiCall { api.toggleFoodMarket(ToggleFoodMarketRequest(groupName)) }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(saving = false, notice = "Food market toggled.")
                refreshSelectedStatus(groupName)
                refreshHoldfasts()
            }
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Action was rejected.")
        }
    }

    fun deleteSelected() = viewModelScope.launch {
        val groupName = _state.value.selectedGroupName ?: return@launch
        _state.value = _state.value.copy(saving = true, notice = null, error = null)
        when (val result = safeApiCall { api.deleteHoldfast(groupName) }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(
                    saving = false,
                    selectedGroupName = null,
                    selectedStatus = null,
                    activeTab = HoldfastTab.STATUS,
                    events = emptyList(),
                    lastActionEvents = emptyList(),
                    notice = result.data.message.ifBlank { "Holdfast deleted." },
                )
                refreshHoldfasts()
            }
            is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(saving = false, error = "Action was rejected.")
        }
    }

    fun loadEvents() {
        val groupName = _state.value.selectedGroupName ?: return
        loadEvents(groupName)
    }

    private fun refreshSelectedStatus(groupName: String) = viewModelScope.launch {
        when (val result = safeApiCall { api.holdfast(groupName) }) {
            is ApiResult.Success -> _state.value = _state.value.copy(selectedStatus = result.data)
            is ApiResult.Failure -> _state.value = _state.value.copy(error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(error = "Action was rejected.")
        }
    }

    private fun loadEvents(groupName: String) = viewModelScope.launch {
        _state.value = _state.value.copy(logLoading = true, error = null)
        when (val result = safeApiCall { api.holdfastEvents(groupName) }) {
            is ApiResult.Success -> _state.value = _state.value.copy(logLoading = false, events = result.data)
            is ApiResult.Failure -> _state.value = _state.value.copy(logLoading = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(logLoading = false, error = "Action was rejected.")
        }
    }
}

@Composable
fun HoldfastScreen(onBack: () -> Unit, viewModel: HoldfastViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var createGroup by remember { mutableStateOf("") }
    var createName by remember { mutableStateOf("") }
    var advanceDays by remember { mutableStateOf("7") }
    var depositAmount by remember { mutableStateOf("") }
    var withdrawForm by remember { mutableStateOf(WithdrawForm()) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
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
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } },
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
            state.loading -> LoadingBox(padding)
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
            state.selectedStatus == null -> LoadingBox(padding)
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
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (holdfasts.isEmpty()) {
            item { EmptyBox(PaddingValues(32.dp), "No holdfasts have been founded.") }
        } else {
            items(holdfasts, key = { it.groupName }) { holdfast ->
                Card(Modifier.fillMaxWidth()) {
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
                state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
        item {
            when (state.activeTab) {
                HoldfastTab.STATUS -> StatusTab(selected)
                HoldfastTab.TIME -> TimeTab(advanceDays, onAdvanceDaysChange, state.lastActionEvents, state.saving, onAdvance)
                HoldfastTab.BUILD -> BuildTab(selected, state.saving, onBuild)
                HoldfastTab.TREASURY -> TreasuryTab(holdfast, depositAmount, onDepositAmountChange, withdrawForm, onWithdrawFormChange, state.saving, onDeposit, onWithdraw, onToggleFoodMarket)
                HoldfastTab.LOG -> LogTab(state.events, state.logLoading, onLoadEvents)
                HoldfastTab.HELP -> HelpTab()
            }
        }
    }
}

@Composable
private fun StatusTab(selected: HoldfastStatus) {
    val holdfast = selected.holdfast
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                MetricGrid(
                    listOf(
                        "Day" to holdfast.daysElapsed.toString(),
                        "Population" to holdfast.population.toString(),
                        "Happiness" to "${holdfast.happiness.toInt()}%",
                        "Target" to "${holdfast.targetHappiness.toInt()}%",
                        "Protection" to selected.protection.toString(),
                        "Raid chance" to "${selected.raidChance}%",
                        "Income/day" to formatGold(selected.dailyIncome),
                        "Net/day" to formatGold(selected.netDailyGold),
                        "Food" to "${selected.daysOfFood} day(s)",
                        "Spoil" to "${selected.nextSpoilIn} day(s)",
                    )
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Resources", style = MaterialTheme.typography.titleMedium)
                MetricGrid(
                    listOf(
                        "Gold" to formatGold(holdfast.gold),
                        "Silver" to holdfast.silver.toString(),
                        "Food" to holdfast.food.toString(),
                        "Beer" to holdfast.beer.toString(),
                        "Grain" to holdfast.grain.toString(),
                        "Wine" to holdfast.wine.toString(),
                        "Tools" to holdfast.tools.toString(),
                        "Wood" to holdfast.wood.toString(),
                        "Stone" to holdfast.stone.toString(),
                        "Iron" to holdfast.iron.toString(),
                    )
                )
                Text("Food market: ${if (holdfast.foodMarketEnabled) "enabled" else "disabled"}")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Built structures", style = MaterialTheme.typography.titleMedium)
                if (holdfast.buildings.isEmpty()) {
                    Text("No buildings yet.")
                } else {
                    holdfast.buildings.entries.sortedBy { it.key }.forEach { (type, count) ->
                        Text("${formatBuildingName(type)} ×$count")
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fields", style = MaterialTheme.typography.titleMedium)
                MetricGrid(
                    listOf(
                        "Wheat" to holdfast.wheatFieldPlantDays.size.toString(),
                        "Vegetable" to holdfast.vegetableGardenPlantDays.size.toString(),
                        "Orchard" to holdfast.orchardPlantDays.size.toString(),
                        "Vineyard" to holdfast.vineyardPlantDays.size.toString(),
                        "Rye" to holdfast.ryeFieldPlantDays.size.toString(),
                        "Berry" to holdfast.berryPatchPlantDays.size.toString(),
                        "Mushroom" to holdfast.mushroomCavePlantDays.size.toString(),
                    )
                )
                val plantedFields = holdfast.wheatFieldPlantDays.size + holdfast.vegetableGardenPlantDays.size + holdfast.orchardPlantDays.size + holdfast.vineyardPlantDays.size + holdfast.ryeFieldPlantDays.size + holdfast.berryPatchPlantDays.size + holdfast.mushroomCavePlantDays.size
                Text("Planted fields / groves: $plantedFields")
            }
        }
    }
}

@Composable
private fun TimeTab(advanceDays: String, onAdvanceDaysChange: (String) -> Unit, events: List<String>, saving: Boolean, onAdvance: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Advance time", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(advanceDays, onAdvanceDaysChange, label = { Text("Days to advance") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = onAdvance, enabled = !saving) { Text("Advance") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Latest advance events", style = MaterialTheme.typography.titleMedium)
                if (events.isEmpty()) {
                    Text("No recent advance events.")
                } else {
                    events.forEach { event -> Text(event) }
                }
            }
        }
    }
}

@Composable
private fun BuildTab(selected: HoldfastStatus, saving: Boolean, onBuild: (String) -> Unit) {
    val available = selected.buildingMenu.filter { it.status == "available" }
    val locked = selected.buildingMenu.filter { it.status == "locked" }
    val maxed = selected.buildingMenu.filter { it.status == "maxed" }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Available", style = MaterialTheme.typography.titleMedium)
        BuildingGroup(available, saving, onBuild)
        Text("Locked", style = MaterialTheme.typography.titleMedium)
        BuildingGroup(locked, saving, onBuild)
        Text("Maxed", style = MaterialTheme.typography.titleMedium)
        BuildingGroup(maxed, saving, onBuild)
    }
}

@Composable
private fun BuildingGroup(items: List<HoldfastBuildingMenuItem>, saving: Boolean, onBuild: (String) -> Unit) {
    if (items.isEmpty()) {
        Text("None")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(item.description)
                    Text("Cost ${item.cost}g · current ${item.current}${item.max?.let { " / $it" } ?: ""}")
                    item.lockReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (item.resourceCost?.isNotEmpty() == true) {
                        Text("Resources: ${item.resourceCost.entries.joinToString { (key, value) -> "$value ${formatBuildingName(key)}" }}")
                    }
                    if (item.status == "available") {
                        Button(onClick = { onBuild(item.type) }, enabled = !saving) { Text("Build") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TreasuryTab(
    holdfast: Holdfast,
    depositAmount: String,
    onDepositAmountChange: (String) -> Unit,
    withdrawForm: WithdrawForm,
    onWithdrawFormChange: (WithdrawForm) -> Unit,
    saving: Boolean,
    onDeposit: (String) -> Unit,
    onWithdraw: (WithdrawForm) -> Unit,
    onToggleFoodMarket: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Treasury", style = MaterialTheme.typography.titleMedium)
                Text("${formatGold(holdfast.gold)} gold · ${holdfast.silver} silver")
                Text("${holdfast.beer} beer · ${holdfast.wine} wine · ${holdfast.grain} grain · ${holdfast.tools} tools")
                Text("${holdfast.food} food · ${holdfast.wood} wood · ${holdfast.stone} stone · ${holdfast.iron} iron")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Food market", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (holdfast.foodMarketEnabled) "Enabled" else "Disabled")
                    Switch(checked = holdfast.foodMarketEnabled, onCheckedChange = { onToggleFoodMarket() }, enabled = !saving)
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Deposit gold", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(depositAmount, onDepositAmountChange, label = { Text("Gold") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onDeposit(depositAmount) }, enabled = !saving && depositAmount.isNotBlank()) { Text("Deposit") }
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
                Button(onClick = { onWithdraw(withdrawForm) }, enabled = !saving) { Text("Withdraw") }
            }
        }
    }
}

@Composable
private fun LogTab(events: List<HoldfastEvent>, loading: Boolean, onLoadEvents: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onLoadEvents) { Text("Refresh log") }
        if (loading) {
            Text("Loading event log...")
        } else if (events.isEmpty()) {
            Text("No events recorded.")
        } else {
            events.forEach { event ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Day ${event.day}", style = MaterialTheme.typography.labelLarge)
                        Text(event.message)
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpTab() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Holdfast help", style = MaterialTheme.typography.titleMedium)
                Text("Status shows income, upkeep, protection, raid chance, food coverage, and the current field/building state.")
                Text("Time advances trigger production, raids, growth checks, and food consumption; the log tab shows the exact event trail.")
                Text("Build, treasury, and farming actions are all native controls here, so you can manage a holdfast without opening a browser page.")
            }
        }
    }
}

@Composable
private fun ResourceFieldRow(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun MetricGrid(items: List<Pair<String, String>>) {
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
private fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun formatBuildingName(type: String): String = type.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun formatGold(value: Double): String = "%.1f".format(value)