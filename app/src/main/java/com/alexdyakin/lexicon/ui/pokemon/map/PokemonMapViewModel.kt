package com.alexdyakin.lexicon.ui.pokemon.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.data.AddGymRequest
import com.alexdyakin.lexicon.data.AddStopRequest
import com.alexdyakin.lexicon.data.CatchRequest
import com.alexdyakin.lexicon.data.CatchResult
import com.alexdyakin.lexicon.data.Gym
import com.alexdyakin.lexicon.data.LureStopRequest
import com.alexdyakin.lexicon.data.PlayerItem
import com.alexdyakin.lexicon.data.Pokestop
import com.alexdyakin.lexicon.data.PokemonSpawn
import com.alexdyakin.lexicon.data.SetTeamRequest
import com.alexdyakin.lexicon.data.SpinGymRequest
import com.alexdyakin.lexicon.data.SpinStopRequest
import com.alexdyakin.lexicon.data.WalkRequest
import com.alexdyakin.lexicon.data.api.PokemonApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the player is currently being asked to place by tapping the map. */
enum class PlacingMode { NONE, STOP, GYM }

/** A thing on the map the player has tapped, driving the detail sheet. */
sealed interface MapSelection {
    data class SpawnTarget(val spawn: PokemonSpawn) : MapSelection
    data class StopTarget(val stop: Pokestop) : MapSelection
    data class GymTarget(val gym: Gym) : MapSelection
}

data class PokemonMapUiState(
    val fix: PlayerFix? = null,
    val permissionGranted: Boolean = false,
    val locationEnabled: Boolean = true,
    val loading: Boolean = false,
    val spawns: List<PokemonSpawn> = emptyList(),
    val stops: List<Pokestop> = emptyList(),
    val gyms: List<Gym> = emptyList(),
    val items: List<PlayerItem> = emptyList(),
    val team: String = "",
    val totalKm: Double = 0.0,
    val placing: PlacingMode = PlacingMode.NONE,
    /** Where the player tapped while placing, awaiting a name. */
    val pendingPlacement: Pair<Double, Double>? = null,
    val selection: MapSelection? = null,
    val message: String = "",
    val error: String = "",
) {
    /** How many of a given item the player holds. */
    fun itemCount(type: String): Int =
        items.firstOrNull { it.itemType.equals(type, ignoreCase = true) }?.quantity ?: 0

    fun inRange(lat: Double, lng: Double): Boolean {
        val f = fix ?: return false
        return MapWorld.distanceMeters(f.lat, f.lng, lat, lng) <= MapWorld.INTERACTION_RANGE_M
    }

    fun metresTo(lat: Double, lng: Double): Int? {
        val f = fix ?: return null
        return MapWorld.distanceMeters(f.lat, f.lng, lat, lng).toInt()
    }
}

/**
 * The live map: what is around the player, and the actions they can take on it.
 *
 * Polling is driven from the screen with `LaunchedWhileResumed` rather than started
 * here, so the map stops asking the server for spawns the moment it is backgrounded.
 * That matters more than it looks: `GET /nearby` *creates* spawns when an area is
 * sparse, so a poll that ran with the screen off would keep seeding the world — and
 * keep the radio awake — for a map nobody is looking at.
 */
@HiltViewModel
class PokemonMapViewModel @Inject constructor(
    private val api: PokemonApi,
    private val location: PlayerLocationSource,
) : ViewModel() {

    private val _state = MutableStateFlow(PokemonMapUiState())
    val state: StateFlow<PokemonMapUiState> = _state.asStateFlow()

    /** Where the last /walk ping was sent from, so small GPS jitter does not spam it. */
    private var lastWalkFrom: PlayerFix? = null

    init {
        _state.value = _state.value.copy(
            permissionGranted = location.hasPermission(),
            locationEnabled = location.locationEnabled(),
        )
    }

    /** Re-read permission state after the system dialog closes. */
    fun refreshPermission() {
        _state.value = _state.value.copy(
            permissionGranted = location.hasPermission(),
            locationEnabled = location.locationEnabled(),
        )
    }

    /** Collects GPS fixes for as long as the caller's scope lives. */
    suspend fun observeLocation() {
        location.updates().collect { fix ->
            val previous = _state.value.fix
            _state.value = _state.value.copy(fix = fix)
            // First fix: fill the map immediately rather than waiting for the poll tick.
            if (previous == null) refresh()
            maybeReportWalk(fix)
        }
    }

    /**
     * Tells the server the player moved. The server decides what counts — it ignores
     * steps under its own minimum as noise and over its maximum as a teleport — but
     * there is no reason to post every 5-metre jitter, so this gates on 10 metres.
     */
    private suspend fun maybeReportWalk(fix: PlayerFix) {
        val from = lastWalkFrom
        if (from != null &&
            MapWorld.distanceMeters(from.lat, from.lng, fix.lat, fix.lng) < 10.0
        ) {
            return
        }
        lastWalkFrom = fix
        runCatching { api.walk(WalkRequest(fix.lat, fix.lng)) }
            .onSuccess { result ->
                val hatched = result.events.filter { it.type == "hatch" }
                _state.value = _state.value.copy(
                    totalKm = result.totalKm,
                    message = when {
                        hatched.isEmpty() -> _state.value.message
                        else -> hatched.joinToString(" ") {
                            "An egg hatched into ${it.speciesName ?: "something"}!"
                        }
                    },
                )
            }
    }

    /**
     * Pulls the world around the player.
     *
     * The three lists are fetched together rather than in sequence: they are
     * independent calls to the same host and doing them one after another made the map
     * take three round trips to draw a single frame.
     */
    fun refresh() {
        val fix = _state.value.fix ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val result = runCatching {
                val spawns = async { api.nearbySpawns(fix.lat, fix.lng, MapWorld.FETCH_RADIUS_M) }
                val stops = async { api.nearbyStops(fix.lat, fix.lng, MapWorld.FETCH_RADIUS_M) }
                val gyms = async { api.nearbyGyms(fix.lat, fix.lng, MapWorld.FETCH_RADIUS_M) }
                val items = async { api.items() }
                val team = async { runCatching { api.team().team }.getOrDefault("") }
                awaitAll(spawns, stops, gyms, items, team)
                Triple(spawns.await(), stops.await(), gyms.await()) to
                    (items.await() to team.await())
            }
            result.onSuccess { (world, extras) ->
                val (spawns, stops, gyms) = world
                _state.value = _state.value.copy(
                    loading = false,
                    // Something already taken is still returned by the server until it
                    // expires; showing it would offer a catch that always fails.
                    spawns = spawns.filter { it.caughtByPlayer == null },
                    stops = stops,
                    gyms = gyms,
                    items = extras.first,
                    team = extras.second,
                    error = "",
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not load the map.",
                )
            }
        }
    }

    // ── Selection and placement ──────────────────────────────────────────────

    fun select(selection: MapSelection?) {
        _state.value = _state.value.copy(selection = selection)
    }

    fun startPlacing(mode: PlacingMode) {
        _state.value = _state.value.copy(placing = mode, pendingPlacement = null, selection = null)
    }

    fun cancelPlacing() {
        _state.value = _state.value.copy(placing = PlacingMode.NONE, pendingPlacement = null)
    }

    /** A map tap while placing. Holds the point until the player confirms a name. */
    fun placeAt(lat: Double, lng: Double) {
        if (_state.value.placing == PlacingMode.NONE) return
        _state.value = _state.value.copy(pendingPlacement = lat to lng)
    }

    fun confirmPlacement(name: String) {
        val mode = _state.value.placing
        val point = _state.value.pendingPlacement ?: return
        if (mode == PlacingMode.NONE) return
        viewModelScope.launch {
            val label = name.trim().ifEmpty { if (mode == PlacingMode.STOP) "Pokestop" else "Gym" }
            val outcome = runCatching {
                when (mode) {
                    PlacingMode.STOP -> {
                        // Answers a plain string, so it is closed rather than decoded.
                        api.addStop(AddStopRequest(label, point.first, point.second)).close()
                        "Added the Pokéstop \"$label\"."
                    }
                    PlacingMode.GYM -> {
                        val gym = api.addGym(AddGymRequest(label, point.first, point.second))
                        "Added the gym \"${gym.name.ifEmpty { label }}\"."
                    }
                    PlacingMode.NONE -> ""
                }
            }
            _state.value = _state.value.copy(
                placing = PlacingMode.NONE,
                pendingPlacement = null,
                message = outcome.getOrElse { it.message ?: "Could not place that." },
            )
            refresh()
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    fun spinStop(stop: Pokestop) {
        val fix = _state.value.fix ?: return
        viewModelScope.launch {
            val outcome = runCatching { api.spinStop(SpinStopRequest(stop.id, fix.lat, fix.lng)) }
            _state.value = _state.value.copy(
                selection = null,
                message = outcome.map { it.message }
                    .getOrElse { it.message ?: "Could not spin that stop." },
            )
            refresh()
        }
    }

    fun spinGym(gym: Gym) {
        val fix = _state.value.fix ?: return
        viewModelScope.launch {
            val outcome = runCatching { api.spinGym(SpinGymRequest(gym.id, fix.lat, fix.lng)) }
            _state.value = _state.value.copy(
                selection = null,
                message = outcome.map { it.message }
                    .getOrElse { it.message ?: "Could not spin that gym." },
            )
            refresh()
        }
    }

    fun lureStop(stop: Pokestop) {
        viewModelScope.launch {
            val outcome = runCatching { api.lureStop(LureStopRequest(stop.id)) }
            _state.value = _state.value.copy(
                selection = null,
                message = outcome.map { it.message }
                    .getOrElse { it.message ?: "Could not activate a lure." },
            )
            refresh()
        }
    }

    fun setTeam(team: String) {
        viewModelScope.launch {
            val outcome = runCatching { api.setTeam(SetTeamRequest(team)) }
            _state.value = _state.value.copy(
                team = outcome.map { it.team }.getOrDefault(_state.value.team),
                message = outcome.map { "You joined Team ${it.team.lowercase().replaceFirstChar(Char::titlecase)}." }
                    .getOrElse { it.message ?: "Could not set your team." },
            )
        }
    }

    /**
     * A throw. The screen owns the animation; this owns the outcome, so a miss shown
     * by the animation and a miss decided by the server can never disagree.
     */
    suspend fun throwBall(
        spawn: PokemonSpawn,
        ball: Ball,
        berry: Berry?,
        battleId: Long? = null,
    ): Result<CatchResult> {
        val fix = _state.value.fix ?: return Result.failure(IllegalStateException("No GPS fix yet."))
        val outcome = runCatching {
            api.attemptCatch(
                CatchRequest(
                    spawnId = spawn.id, lat = fix.lat, lng = fix.lng,
                    ballType = ball.type, berry = berry?.type, battleId = battleId,
                ),
            )
        }
        // Balls and berries were spent whether or not it was caught.
        runCatching { api.items() }.onSuccess { _state.value = _state.value.copy(items = it) }
        outcome.onSuccess { result ->
            if (result.success) {
                _state.value = _state.value.copy(
                    spawns = _state.value.spawns.filterNot { it.id == spawn.id },
                )
            }
        }
        return outcome
    }

    fun consumeMessage() {
        if (_state.value.message.isNotEmpty()) _state.value = _state.value.copy(message = "")
    }
}
