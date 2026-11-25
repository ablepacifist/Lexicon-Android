package com.alexdyakin.lexicon.ui.alchemy

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.data.AdvanceHoldfastRequest
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.BuildHoldfastRequest
import com.alexdyakin.lexicon.data.CreateHoldfastRequest
import com.alexdyakin.lexicon.data.DepositHoldfastRequest
import com.alexdyakin.lexicon.data.Holdfast
import com.alexdyakin.lexicon.data.HoldfastAsset
import com.alexdyakin.lexicon.data.HoldfastEvent
import com.alexdyakin.lexicon.data.HoldfastStatus
import com.alexdyakin.lexicon.data.HoldfastEventEntry
import com.alexdyakin.lexicon.data.LexiconIdentity
import com.alexdyakin.lexicon.data.Permissions
import com.alexdyakin.lexicon.data.ReplantHoldfastRequest
import com.alexdyakin.lexicon.data.ToggleFoodMarketRequest
import com.alexdyakin.lexicon.data.WithdrawHoldfastRequest
import com.alexdyakin.lexicon.data.api.AlchemyApi
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

enum class HoldfastTab { STATUS, TIME, BUILD, TREASURY, LOG, HELP }

data class WithdrawForm(
    val gold: String = "",
    val beer: String = "",
    val wine: String = "",
    val grain: String = "",
    val tools: String = "",
) {
    private val fields get() = listOf(gold, beer, wine, grain, tools)

    /** True when every filled field is a number and at least one is positive. */
    val isValid: Boolean
        get() {
            val filled = fields.filter { it.isNotBlank() }
            if (filled.isEmpty()) return false
            val parsed = filled.map { it.toDoubleOrNull() }
            if (parsed.any { it == null }) return false
            return parsed.any { it!! > 0.0 }
        }
}

data class HoldfastUiState(
    /** Loading the holdfast list. Must not blank out a detail screen already on show. */
    val listLoading: Boolean = true,
    /** Loading the selected holdfast's status. */
    val detailLoading: Boolean = false,
    val saving: Boolean = false,
    val holdfasts: List<Holdfast> = emptyList(),
    val selectedGroupName: String? = null,
    val selectedStatus: HoldfastStatus? = null,
    val activeTab: HoldfastTab = HoldfastTab.STATUS,
    val events: List<HoldfastEvent> = emptyList(),
    val logLoading: Boolean = false,
    val lastActionEvents: List<HoldfastEventEntry> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
    /**
     * Bumped whenever an action succeeds and the entry fields should be cleared. The
     * composable watches this rather than the ViewModel owning text-field state.
     */
    val formResetToken: Int = 0,
    /** Permission names from Lexicon. Only decide what is shown; Alchemy enforces them. */
    val permissions: List<String> = emptyList(),
    /** Art for each key ("building.granary", "resource.wood", …), from alchemyServer's asset manifest. */
    val assets: Map<String, HoldfastAsset> = emptyMap(),
) {
    val canAdvanceTime: Boolean get() = Permissions.HOLDFAST_ADVANCE_TIME in permissions
    val canDelete: Boolean get() = Permissions.HOLDFAST_DELETE in permissions
}

/** Only advance and delete are checked by alchemyServer; anything else answering 401/403 is unexpected. */
private const val HOLDFAST_REJECTED = "The holdfast server rejected that request."
private const val SIGN_IN_AGAIN = "Your session has expired. Please sign in again."

@HiltViewModel
class HoldfastViewModel @Inject constructor(
    private val api: AlchemyApi,
    private val json: Json,
    private val identity: LexiconIdentity,
) : ViewModel() {
    private val _state = MutableStateFlow(HoldfastUiState())
    val state = _state.asStateFlow()

    init {
        refreshHoldfasts()
        loadPermissions()
        loadAssets()
    }

    /** Fetched once per screen. On failure the screens simply show no icons. */
    private fun loadAssets() = viewModelScope.launch {
        val assets = runCatching { api.holdfastAssets().assets }.getOrDefault(emptyMap())
        _state.value = _state.value.copy(assets = assets)
    }

    /** Asks Lexicon what this player may do. On failure nothing admin-only is shown. */
    private fun loadPermissions() = viewModelScope.launch {
        val permissions = runCatching { identity.permissions() }.getOrDefault(emptyList())
        _state.value = _state.value.copy(permissions = permissions)
    }

    fun closeSelection() {
        _state.value = _state.value.copy(
            selectedGroupName = null,
            selectedStatus = null,
            activeTab = HoldfastTab.STATUS,
            events = emptyList(),
            lastActionEvents = emptyList(),
            notice = null,
            error = null,
        )
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun refreshHoldfasts() = viewModelScope.launch { loadHoldfasts() }

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
        loadSelectedStatus(holdfast.groupName)
    }

    /** Re-runs the status fetch after a failure, from the retry button. */
    fun retrySelectedStatus() = viewModelScope.launch {
        val groupName = _state.value.selectedGroupName ?: return@launch
        loadSelectedStatus(groupName)
    }

    fun setTab(tab: HoldfastTab) {
        _state.value = _state.value.copy(activeTab = tab, notice = null, error = null)
        if (tab == HoldfastTab.LOG && _state.value.events.isEmpty()) {
            val groupName = _state.value.selectedGroupName ?: return
            loadEvents(groupName)
        }
    }

    fun create(groupName: String, holdfastName: String) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true, notice = null, error = null)
        when (val result = holdfastCall { api.createHoldfast(CreateHoldfastRequest(groupName, holdfastName)) }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(saving = false)
                loadHoldfasts()
                selectHoldfast(result.data)
            }
            is ApiResult.Failure -> fail(result.message)
            ApiResult.Unauthorized -> fail(HOLDFAST_REJECTED)
        }
    }

    fun advance(days: Int) = viewModelScope.launch {
        val groupName = _state.value.selectedGroupName ?: return@launch
        _state.value = _state.value.copy(saving = true, notice = null, error = null, lastActionEvents = emptyList())
        val token = mintToken() ?: return@launch
        when (val result = holdfastCall { api.advanceHoldfast(AdvanceHoldfastRequest(groupName, days), token) }) {
            is ApiResult.Success -> {
                // Advancing invalidates the persisted event log; drop it so the Log tab
                // cannot show a pre-advance trail, and refetch if it is already open.
                val logOpen = _state.value.activeTab == HoldfastTab.LOG
                _state.value = _state.value.copy(
                    saving = false,
                    lastActionEvents = result.data.eventEntries.ifEmpty { result.data.events.map { HoldfastEventEntry(it) } },
                    notice = "Time advanced.",
                    events = emptyList(),
                )
                refreshAfterMutation(groupName)
                if (logOpen) loadEvents(groupName)
            }
            is ApiResult.Failure -> fail(result.message)
            ApiResult.Unauthorized -> fail(HOLDFAST_REJECTED)
        }
    }

    fun build(buildingType: String) = viewModelScope.launch {
        val groupName = _state.value.selectedGroupName ?: return@launch
        _state.value = _state.value.copy(saving = true, notice = null, error = null)
        when (val result = holdfastCall { api.buildHoldfast(BuildHoldfastRequest(groupName, buildingType)) }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(
                    saving = false,
                    notice = result.data.message.ifBlank { "Building updated." },
                )
                refreshAfterMutation(groupName)
            }
            is ApiResult.Failure -> fail(result.message)
            ApiResult.Unauthorized -> fail(HOLDFAST_REJECTED)
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
        when (val result = holdfastCall { api.depositHoldfast(DepositHoldfastRequest(groupName, gold)) }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(
                    saving = false,
                    notice = "Deposited ${formatGold(gold)}.",
                    formResetToken = _state.value.formResetToken + 1,
                )
                refreshAfterMutation(groupName)
            }
            is ApiResult.Failure -> fail(result.message)
            ApiResult.Unauthorized -> fail(HOLDFAST_REJECTED)
        }
    }

    fun withdraw(form: WithdrawForm) = viewModelScope.launch {
        val groupName = _state.value.selectedGroupName ?: return@launch
        if (!form.isValid) {
            _state.value = _state.value.copy(error = "Enter at least one positive amount.")
            return@launch
        }
        _state.value = _state.value.copy(saving = true, notice = null, error = null)
        val result = holdfastCall {
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
                _state.value = _state.value.copy(
                    saving = false,
                    notice = result.data.message.ifBlank { "Resources withdrawn." },
                    formResetToken = _state.value.formResetToken + 1,
                )
                refreshAfterMutation(groupName)
            }
            is ApiResult.Failure -> fail(result.message)
            ApiResult.Unauthorized -> fail(HOLDFAST_REJECTED)
        }
    }

    fun replant(fieldType: String) = viewModelScope.launch {
        val groupName = _state.value.selectedGroupName ?: return@launch
        _state.value = _state.value.copy(saving = true, notice = null, error = null)
        when (val result = holdfastCall { api.replantHoldfast(ReplantHoldfastRequest(groupName, fieldType)) }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(
                    saving = false,
                    notice = result.data.message.ifBlank { "Field replanted." },
                )
                refreshAfterMutation(groupName)
            }
            is ApiResult.Failure -> fail(result.message)
            ApiResult.Unauthorized -> fail(HOLDFAST_REJECTED)
        }
    }

    fun toggleFoodMarket() = viewModelScope.launch {
        val groupName = _state.value.selectedGroupName ?: return@launch
        _state.value = _state.value.copy(saving = true, notice = null, error = null)
        when (val result = holdfastCall { api.toggleFoodMarket(ToggleFoodMarketRequest(groupName)) }) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(saving = false, notice = "Food market toggled.")
                refreshAfterMutation(groupName)
            }
            is ApiResult.Failure -> fail(result.message)
            ApiResult.Unauthorized -> fail(HOLDFAST_REJECTED)
        }
    }

    fun deleteSelected() = viewModelScope.launch {
        val groupName = _state.value.selectedGroupName ?: return@launch
        _state.value = _state.value.copy(saving = true, notice = null, error = null)
        val token = mintToken() ?: return@launch
        when (val result = holdfastCall { api.deleteHoldfast(groupName, token) }) {
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
                loadHoldfasts()
            }
            is ApiResult.Failure -> fail(result.message)
            ApiResult.Unauthorized -> fail(HOLDFAST_REJECTED)
        }
    }

    fun loadEvents() {
        val groupName = _state.value.selectedGroupName ?: return
        loadEvents(groupName)
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun fail(message: String) {
        _state.value = _state.value.copy(saving = false, error = message)
    }

    /** A fresh single-use SSO token for an admin-only call, or null (with the error shown). */
    private suspend fun mintToken(): String? = try {
        identity.mintSsoToken()
    } catch (e: IOException) {
        fail("Can't reach Lexicon to confirm who you are. Check your connection.")
        null
    } catch (e: Exception) {
        fail(SIGN_IN_AGAIN)
        null
    }

    /** Every mutation refreshes the same way: real status first, then the list. */
    private suspend fun refreshAfterMutation(groupName: String) {
        loadSelectedStatus(groupName)
        loadHoldfasts()
    }

    private suspend fun loadHoldfasts() {
        _state.value = _state.value.copy(listLoading = true)
        when (val result = holdfastCall { api.holdfasts() }) {
            // Clearing the error on success matters for retry: otherwise a recovered
            // load leaves the previous failure's banner sitting on screen.
            is ApiResult.Success ->
                _state.value = _state.value.copy(listLoading = false, holdfasts = result.data, error = null)
            is ApiResult.Failure ->
                _state.value = _state.value.copy(listLoading = false, error = result.message)
            ApiResult.Unauthorized ->
                _state.value = _state.value.copy(listLoading = false, error = HOLDFAST_REJECTED)
        }
    }

    /**
     * On failure this deliberately leaves [HoldfastUiState.selectedStatus] alone: a
     * mid-session refresh failure should show a banner over live data, not destroy it.
     * When there is no status yet the screen shows a retry instead of a dead spinner.
     */
    private suspend fun loadSelectedStatus(groupName: String) {
        _state.value = _state.value.copy(detailLoading = true)
        when (val result = holdfastCall { api.holdfast(groupName) }) {
            is ApiResult.Success ->
                _state.value = _state.value.copy(detailLoading = false, selectedStatus = result.data, error = null)
            is ApiResult.Failure ->
                _state.value = _state.value.copy(detailLoading = false, error = result.message)
            ApiResult.Unauthorized ->
                _state.value = _state.value.copy(detailLoading = false, error = HOLDFAST_REJECTED)
        }
    }

    private fun loadEvents(groupName: String) = viewModelScope.launch {
        _state.value = _state.value.copy(logLoading = true)
        when (val result = holdfastCall { api.holdfastEvents(groupName) }) {
            is ApiResult.Success -> _state.value = _state.value.copy(logLoading = false, events = result.data)
            is ApiResult.Failure -> _state.value = _state.value.copy(logLoading = false, error = result.message)
            ApiResult.Unauthorized -> _state.value = _state.value.copy(logLoading = false, error = HOLDFAST_REJECTED)
        }
    }

    /**
     * Like [safeApiCall], but keeps the server's own explanation.
     *
     * alchemyServer answers a rejected action with a useful 400 body — plain
     * `"Insufficient resources"` or `{"success":false,"message":"Insufficient funds.
     * Need: 200g, Have: 143.0g..."}`. [safeApiCall] flattens both to
     * "Server error (HTTP 400)." Kept local to Holdfast because changing the shared
     * helper would alter error text on every screen in the app.
     */
    private suspend fun <T> holdfastCall(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (e: HttpException) {
        when (e.code()) {
            // Advance and delete answer 401/403 with a reason ("Only an admin can advance time.").
            401 -> ApiResult.Failure(serverMessage(e) ?: SIGN_IN_AGAIN, 401)
            403 -> serverMessage(e)?.let { ApiResult.Failure(it, 403) } ?: ApiResult.Unauthorized
            else -> ApiResult.Failure(serverMessage(e) ?: "Server error (HTTP ${e.code()}).", e.code())
        }
    } catch (e: IOException) {
        ApiResult.Failure("Can't reach the holdfast server. Check your connection.")
    } catch (e: Exception) {
        ApiResult.Failure(e.message ?: "Something went wrong.")
    }

    private fun serverMessage(e: HttpException): String? {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()?.trim()
        if (body.isNullOrBlank()) return null

        // JSON bodies carry the useful text under "message".
        runCatching { json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // Otherwise accept a short plain-text body, but never surface an HTML error page.
        return body.takeIf { it.length <= 200 && !it.startsWith("<") }
    }
}

/** Persists [WithdrawForm] across rotation as the five strings it wraps. */
internal val WithdrawFormSaver: Saver<WithdrawForm, Any> = listSaver<WithdrawForm, String>(
    save = { listOf(it.gold, it.beer, it.wine, it.grain, it.tools) },
    restore = { WithdrawForm(gold = it[0], beer = it[1], wine = it[2], grain = it[3], tools = it[4]) },
)
