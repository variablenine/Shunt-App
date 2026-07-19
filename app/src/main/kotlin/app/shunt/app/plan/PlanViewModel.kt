package app.shunt.app.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shunt.core.GeoPoint
import app.shunt.solver.routing.SolveResult
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
 * Orchestrates the planning flow: enter destination → solver runs → result
 * card → Go. Pure of Android UI; every dependency is a small port so the
 * whole flow is unit-testable with fakes. [scope] is injectable so tests can
 * drive it with virtual time; production uses viewModelScope.
 */
class PlanViewModel(
    private val search: SuggestionSearch,
    private val planner: RoutePlanner,
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
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        searchJob = workScope.launch {
            delay(searchDebounceMillis)
            val at = location.currentOrigin() ?: DEFAULT_BIAS
            val results = runCatching { search.suggest(query, at) }.getOrDefault(emptyList())
            _state.update { it.copy(suggestions = results) }
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
        workScope.launch {
            val origin = location.currentOrigin()
            if (origin == null) {
                _state.update {
                    it.copy(phase = Phase.Error("No starting location. Enable location or set Home."))
                }
                return@launch
            }
            val result = runCatching { planner.solve(origin, destination.location) }
                .getOrElse { e -> SolveResult.Failed("routing failed: ${e.message}") }
            _state.update {
                when (result) {
                    is SolveResult.Failed -> it.copy(phase = Phase.Error(result.reason))
                    else -> it.copy(phase = Phase.Solved(destination, result))
                }
            }
        }
    }

    /** Go: push the route to the vehicle. (M4 will also start the drive monitor here.) */
    fun onGo() {
        val solved = _state.value.phase as? Phase.Solved ?: return
        val chain = pushChainFor(solved.result, solved.destination)
        _state.update { it.copy(phase = Phase.Pushing(solved.destination, solved.result)) }
        workScope.launch {
            val result = runCatching { vehicle.pushRoute(chain) }
                .getOrElse { e -> PushResult.Failed("push threw: ${e.message}", retryable = true) }
            _state.update {
                when (result) {
                    is PushResult.Success -> it.copy(phase = Phase.Pushed(solved.destination))
                    is PushResult.Failed -> it.copy(
                        phase = Phase.PushFailed(
                            solved.destination, solved.result, result.reason, result.retryable,
                        ),
                    )
                }
            }
        }
    }

    /** Retry a failed push from the PushFailed state. */
    fun onRetryPush() {
        val failed = _state.value.phase as? Phase.PushFailed ?: return
        _state.update { it.copy(phase = Phase.Solved(failed.destination, failed.result)) }
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

    /** Back to browsing (dismiss the result card / clear an error). */
    fun onDismissResult() {
        _state.update { it.copy(phase = Phase.Browsing, query = "", suggestions = emptyList()) }
    }

    /**
     * The waypoint chain to push: the solver's intermediate pins followed by
     * the destination itself. The pins are what hold the vehicle on the
     * chosen (camera-aware) path; the destination is the final stop.
     */
    private fun pushChainFor(result: SolveResult, destination: Destination): List<GeoPoint> {
        val waypoints = when (result) {
            is SolveResult.Clean -> result.waypoints
            is SolveResult.MinimumExposure -> result.waypoints
            is SolveResult.Failed -> emptyList()
        }
        return waypoints + destination.location
    }

    companion object {
        /** Fallback autosuggest bias when no location is known (NE Wisconsin). */
        val DEFAULT_BIAS = GeoPoint(44.5133, -88.0133)
    }
}
