package app.shunt.app.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.PlanOutcome
import app.shunt.solver.brouter.PlannedRoute
import app.shunt.tesla.PushResult
import app.shunt.tesla.VehicleNavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Orchestrates the planning flow: enter destination → route on-device → choose
 * among options → Go. Pure of Android UI; every dependency is a small port so
 * the whole flow is unit-testable with fakes. [scope] is injectable so tests
 * can drive it with virtual time; production uses viewModelScope.
 */
class PlanViewModel(
    private val search: SuggestionSearch,
    private val planner: RoutePlanner,
    private val tileDownloader: TileDownloader,
    private val location: LocationProvider,
    private val cameras: CameraGateway,
    private val favoritesStore: FavoritesStore,
    private val vehicle: VehicleNavClient,
    private val scope: CoroutineScope? = null,
    private val searchDebounceMillis: Long = 250,
) : ViewModel() {

    private val workScope: CoroutineScope get() = scope ?: viewModelScope

    private val _state = MutableStateFlow(PlanUiState(favorites = favoritesStore.load()))
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** Call once when the planning screen opens: warm camera data, no background work. */
    fun onOpen() {
        workScope.launch {
            val origin = location.currentOrigin() ?: return@launch
            val freshness = runCatching { cameras.refresh(origin) }.getOrNull()
            _state.update { it.copy(cameraDataFreshness = freshness) }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(suggestions = emptyList(), searchFailed = false) }
            return
        }
        searchJob = workScope.launch {
            delay(searchDebounceMillis)
            val at = location.currentOrigin() ?: DEFAULT_BIAS
            val outcome = runCatching { search.suggest(query, at) }
            _state.update { state ->
                outcome.fold(
                    onSuccess = { results -> state.copy(suggestions = results, searchFailed = false) },
                    // Don't silently blank: tell the user search couldn't be reached.
                    onFailure = { state.copy(suggestions = emptyList(), searchFailed = true) },
                )
            }
        }
    }

    fun onSuggestionSelected(index: Int) {
        val suggestion = _state.value.suggestions.getOrNull(index) ?: return
        planTo(Destination.of(suggestion))
    }

    fun onFavoriteSelected(slot: FavoriteSlot) {
        val favorite = when (slot) {
            FavoriteSlot.HOME -> _state.value.favorites.home
            FavoriteSlot.WORK -> _state.value.favorites.work
        } ?: return
        planTo(favorite)
    }

    private fun planTo(destination: Destination) {
        searchJob?.cancel()
        _state.update { it.copy(phase = Phase.Solving(destination), suggestions = emptyList()) }
        workScope.launch { runPlan(destination) }
    }

    /**
     * Route for [destination] and land on the chooser or an error. A missing
     * offline tile is downloaded automatically and then re-planned — no prompt.
     * [canDownload] guards against looping if a download reports success but the
     * tile still isn't usable.
     */
    private suspend fun runPlan(destination: Destination, canDownload: Boolean = true) {
        val origin = location.currentOrigin()
        if (origin == null) {
            _state.update { it.copy(phase = Phase.Error("No starting location. Enable location or set Home.")) }
            return
        }
        val outcome = runCatching { planner.plan(origin, destination.location) }
            .getOrElse { e -> PlanOutcome.Failed("routing failed: ${e.message}") }
        when (outcome) {
            is PlanOutcome.Routes ->
                _state.update { it.copy(phase = Phase.Solved(destination, outcome.options)) }
            is PlanOutcome.NeedsDownload ->
                if (canDownload) {
                    downloadThenPlan(destination, origin)
                } else {
                    _state.update {
                        it.copy(phase = Phase.Error("Couldn't prepare the offline map for this area."))
                    }
                }
            is PlanOutcome.Failed ->
                _state.update { it.copy(phase = Phase.Error(outcome.reason)) }
        }
    }

    /** Auto-download this trip's offline tile (showing progress), then re-plan. */
    private suspend fun downloadThenPlan(destination: Destination, origin: GeoPoint) {
        _state.update { it.copy(phase = Phase.NeedTile(destination, downloading = true, progress = 0f)) }
        val ok = runCatching {
            tileDownloader.download(origin, destination.location) { p ->
                _state.update { s ->
                    (s.phase as? Phase.NeedTile)?.let { s.copy(phase = it.copy(progress = p)) } ?: s
                }
            }
        }.getOrDefault(false)
        if (ok) {
            runPlan(destination, canDownload = false)
        } else {
            _state.update { s ->
                (s.phase as? Phase.NeedTile)?.let { s.copy(phase = it.copy(downloading = false, failed = true)) } ?: s
            }
        }
    }

    /** Pick a different route option from the chooser. */
    fun onSelectRoute(index: Int) {
        val solved = _state.value.phase as? Phase.Solved ?: return
        if (index in solved.options.indices) {
            _state.update { it.copy(phase = solved.copy(selected = index)) }
        }
    }

    /** Retry the offline-map download after a failure (the only NeedTile button). */
    fun onDownloadTile() {
        val need = _state.value.phase as? Phase.NeedTile ?: return
        if (need.downloading) return
        workScope.launch {
            val origin = location.currentOrigin()
            if (origin == null) {
                _state.update { it.copy(phase = Phase.Error("No starting location. Enable location or set Home.")) }
                return@launch
            }
            downloadThenPlan(need.destination, origin)
        }
    }

    /**
     * Go: upload the chosen route to the vehicle, then enter the driving phase.
     * The activity starts the foreground drive-monitor service on this
     * transition (it must be started from the foreground, so not here).
     */
    fun onGo() {
        val solved = _state.value.phase as? Phase.Solved ?: return
        val option = solved.chosen
        val plan = drivePlanFor(option, solved.destination)
        _state.update { it.copy(phase = Phase.Pushing(solved.destination, option)) }
        workScope.launch {
            val result = runCatching { vehicle.pushRoute(plan.chain) }
                .getOrElse { e -> PushResult.Failed("push threw: ${e.message}", retryable = true) }
            _state.update {
                when (result) {
                    is PushResult.Success -> it.copy(phase = Phase.Driving(solved.destination, plan))
                    is PushResult.Failed -> it.copy(
                        phase = Phase.PushFailed(solved.destination, option, result.reason, result.retryable),
                    )
                }
            }
        }
    }

    /** Cancel the drive (user tapped cancel). The activity stops the service. */
    fun onStopDrive() {
        if (_state.value.phase is Phase.Driving) {
            _state.update { it.copy(phase = Phase.Browsing, query = "", suggestions = emptyList()) }
        }
    }

    /** The monitor reported arrival; leave the driving phase. */
    fun onArrived() {
        if (_state.value.phase is Phase.Driving) {
            _state.update { it.copy(phase = Phase.Browsing, query = "", suggestions = emptyList()) }
        }
    }

    /** Retry a failed push from the PushFailed state. */
    fun onRetryPush() {
        val failed = _state.value.phase as? Phase.PushFailed ?: return
        _state.update { it.copy(phase = Phase.Solved(failed.destination, listOf(failed.option), 0)) }
        onGo()
    }

    fun onSaveFavorite(slot: FavoriteSlot, destination: Destination) {
        val current = _state.value.favorites
        val updated = when (slot) {
            FavoriteSlot.HOME -> current.copy(home = destination)
            FavoriteSlot.WORK -> current.copy(work = destination)
        }
        favoritesStore.save(updated)
        _state.update { it.copy(favorites = updated) }
    }

    /** Back to browsing (dismiss the chooser / clear an error). */
    fun onDismissResult() {
        _state.update { it.copy(phase = Phase.Browsing, query = "", suggestions = emptyList()) }
    }

    /**
     * The plan handed to the drive monitor. The chain is the chosen route's
     * intermediate pins (which hold the vehicle on the camera-aware path)
     * followed by the destination itself. Cameras are the ones this route
     * passes, to warn about — empty for a camera-free route.
     */
    private fun drivePlanFor(option: PlannedRoute, destination: Destination): DrivePlan =
        DrivePlan(
            destination = destination,
            chain = option.waypoints + destination.location,
            cameras = option.passedCameras,
            polyline = option.polyline,
        )

    companion object {
        /** Fallback autosuggest bias when no location is known (NE Wisconsin). */
        val DEFAULT_BIAS = GeoPoint(44.5133, -88.0133)
    }
}
