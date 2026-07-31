package app.shunt.app.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.PlanOutcome
import app.shunt.solver.brouter.PlannedRoute
import app.shunt.solver.charging.RangeCheck
import app.shunt.solver.charging.RangeEstimate
import app.shunt.solver.geo.haversineMeters
import app.shunt.solver.geo.pointToPolylineProgress
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
    /** Places routed to before; offered when the search box is empty. */
    private val recentPlaces: RecentPlacesStore? = null,
    /** Names a long-pressed map point; absent, such points get coordinates. */
    private val placeNamer: PlaceNamer? = null,
    /** Reads the car's remaining range; absent, no range warning is shown. */
    private val rangeReader: VehicleRangeReader? = null,
    /** Finds a charging stop on the way; absent, the offer isn't made. */
    private val chargeStopFinder: ChargeStopFinder? = null,
    /** Lists chargers near the route so one can be picked off the map. */
    private val chargerListing: ChargerListing? = null,
    private val scope: CoroutineScope? = null,
    private val searchDebounceMillis: Long = 350,
) : ViewModel() {

    private val workScope: CoroutineScope get() = scope ?: viewModelScope

    private val _state = MutableStateFlow(
        PlanUiState(
            favorites = favoritesStore.load(),
            recents = recentPlaces?.load().orEmpty(),
        ),
    )
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /**
     * The last push found a car that takes only a single destination. Remembered
     * so the next trip can be aimed at its first pin directly, without the car
     * first being sent to the destination and redirected a moment later.
     */
    private var carTakesDestinationOnly = false

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
            _state.update { it.copy(suggestions = emptyList(), searching = false, searchFailed = false) }
            return
        }
        // Mark searching immediately so the UI shows progress, not a stale blank,
        // during the debounce + the geocoder round-trip (~1s on the public host).
        _state.update { it.copy(searching = true, searchFailed = false) }
        searchJob = workScope.launch {
            delay(searchDebounceMillis)
            val at = location.currentOrigin() ?: DEFAULT_BIAS
            val outcome = runCatching { search.suggest(query, at) }
            _state.update { state ->
                outcome.fold(
                    onSuccess = { results ->
                        state.copy(suggestions = results, searching = false, searchFailed = false)
                    },
                    // Don't silently blank: tell the user search couldn't be reached.
                    onFailure = { state.copy(suggestions = emptyList(), searching = false, searchFailed = true) },
                )
            }
        }
    }

    fun onSuggestionSelected(index: Int) {
        val suggestion = _state.value.suggestions.getOrNull(index) ?: return
        planTo(Destination.of(suggestion))
    }

    /**
     * Add the selected search result as a stop on the way rather than the
     * destination. The trip isn't re-planned until the user picks a final
     * destination, so several stops can be queued up first.
     */
    fun onSuggestionAddedAsStop(index: Int) {
        val suggestion = _state.value.suggestions.getOrNull(index) ?: return
        _state.update {
            it.copy(
                stops = it.stops + Destination.of(suggestion),
                query = "",
                suggestions = emptyList(),
                searching = false,
                searchFailed = false,
            )
        }
    }

    fun onRemoveStop(index: Int) {
        _state.update {
            if (index !in it.stops.indices) it
            else it.copy(stops = it.stops.filterIndexed { i, _ -> i != index })
        }
    }

    fun onFavoriteSelected(slot: FavoriteSlot) {
        val favorite = when (slot) {
            FavoriteSlot.HOME -> _state.value.favorites.home
            FavoriteSlot.WORK -> _state.value.favorites.work
        } ?: return
        planTo(favorite)
    }

    /**
     * Long-press on the map: route to that spot. The label is resolved
     * best-effort — a name makes the result card readable, but never blocks or
     * fails the routing, which is the part the user actually asked for.
     */
    fun onMapLongPress(point: GeoPoint) {
        searchJob?.cancel()
        workScope.launch {
            val name = placeNamer?.let { namer -> runCatching { namer.nameFor(point) }.getOrNull() }
            planTo(Destination(name ?: DROPPED_PIN, point))
        }
    }

    /** Route to a place picked from the recents list. */
    fun onRecentSelected(index: Int) {
        _state.value.recents.getOrNull(index)?.let { planTo(it) }
    }

    private fun planTo(destination: Destination) {
        searchJob?.cancel()
        // Charging stops belong to the trip they were chosen for: they sit on
        // that route, at distances from that origin. Carrying them into a new
        // trip would put the car through somewhere arbitrary. The driver's own
        // stops stay — those are places they asked for, not range arithmetic.
        _state.update { it.clearedOfCharging() }
        // Recorded on the attempt, not on arrival: what you tried to go to is
        // what you are likely to want offered again, even if the plan failed.
        recentPlaces?.record(destination)
        _state.update { it.copy(recents = recentPlaces?.load().orEmpty()) }
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
        val stops = _state.value.stops.map { it.location }
        // Planning while already rolling: the route has to leave the way the car
        // is pointing. Null when parked, which leaves every direction open.
        val heading = runCatching { location.currentHeading() }.getOrNull()
        val outcome = runCatching {
            planner.plan(
                points = listOf(origin) + stops + destination.location,
                onProgress = { progress, step ->
                    // Only advance the bar while this plan is still the live phase.
                    _state.update { state ->
                        val solving = state.phase as? Phase.Solving
                        if (solving?.destination == destination) {
                            state.copy(phase = solving.copy(progress = progress, step = step))
                        } else {
                            state
                        }
                    }
                },
                headingDegrees = heading,
            )
        }.getOrElse { e -> PlanOutcome.Failed("routing failed: ${e.message}") }
        when (outcome) {
            is PlanOutcome.Routes -> {
                _state.update {
                    it.copy(
                        phase = Phase.Solved(destination, outcome.options),
                        chargeStopSearchFailed = false,
                    )
                }
                checkRange()
                listChargers()
            }
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
            // The whole point of the warning is that options differ in length,
            // so it has to follow the option actually selected.
            _state.update { it.copy(rangeCheck = rangeCheckFor(it.phase)) }
        }
    }

    /**
     * The car's remaining range, read once per plan and kept so switching
     * between route options doesn't re-query the vehicle.
     */
    private var lastRangeReading: RangeReading? = null

    /**
     * Compare the route on the chooser with what the battery can actually
     * cover. A read failure leaves [PlanUiState.rangeCheck] null: saying
     * nothing is correct here, and an optimistic guess is the one thing that
     * could actually strand someone.
     */
    private suspend fun checkRange() {
        val reader = rangeReader ?: return
        lastRangeReading = runCatching { reader.read() }.getOrNull()
        _state.update { it.copy(rangeCheck = rangeCheckFor(it.phase)) }
    }

    private fun rangeCheckFor(phase: Phase): RangeCheck? {
        val solved = phase as? Phase.Solved ?: return null
        val reading = lastRangeReading ?: return null
        return RangeEstimate.of(
            routeMeters = solved.chosen.distanceMeters,
            shortestOptionMeters = solved.options.minOf { it.distanceMeters },
            estimatedRangeMiles = reading.estimatedRangeMiles,
            batteryPercent = reading.batteryPercent,
            legMeters = legsBetweenCharges(solved.chosen.polyline),
        )
    }

    /**
     * The route split at its charging stops. Only charging stops split it —
     * pausing for coffee doesn't put anything back in the battery, so a leg
     * runs from one charge to the next however many other stops are on it.
     */
    private fun legsBetweenCharges(polyline: List<GeoPoint>): List<Int> {
        val chargers = _state.value.stops.filter { it.location in _state.value.chargeStops }
        if (chargers.isEmpty() || polyline.size < 2) return emptyList()

        val alongOf = { p: GeoPoint -> pointToPolylineProgress(p, polyline).alongMeters }
        val total = polyline.indices.drop(1)
            .sumOf { haversineMeters(polyline[it - 1], polyline[it]) }
        val cuts = chargers.map(Destination::location).map(alongOf).sorted()

        val boundaries = listOf(0.0) + cuts + listOf(total)
        return boundaries.zipWithNext { from, to -> (to - from).toInt().coerceAtLeast(0) }
    }

    /**
     * Load the charging sites near this route so they can be picked off the
     * map. Best-effort and silent: it is a convenience layered on top of a plan
     * that already succeeded, so a failure must not disturb it.
     */
    private suspend fun listChargers() {
        val listing = chargerListing ?: return
        val solved = _state.value.phase as? Phase.Solved ?: return
        val found = runCatching { listing.alongRoute(solved.chosen.polyline) }.getOrNull().orEmpty()
        _state.update { if (it.phase is Phase.Solved) it.copy(chargersOnRoute = found) else it }
    }

    /** Put the charging site the user tapped on the map into the trip. */
    fun onChargerPicked(location: GeoPoint) {
        val destination = (_state.value.phase as? Phase.Solved)?.destination ?: return
        val charger = _state.value.chargersOnRoute.firstOrNull { it.location == location } ?: return
        if (charger.location in _state.value.chargeStops) return
        val polyline = (_state.value.phase as? Phase.Solved)?.chosen?.polyline.orEmpty()

        _state.update {
            it.copy(
                stops = (it.stops + charger).sortedBy { stop ->
                    pointToPolylineProgress(stop.location, polyline).alongMeters
                },
                chargeStops = it.chargeStops + charger.location,
                phase = Phase.Solving(destination),
            )
        }
        workScope.launch { runPlan(destination) }
    }

    /**
     * Add charging stops until every leg of the trip fits, from the range
     * warning's button.
     *
     * Stops go in as ordinary stops and the trip is re-planned around them, so
     * the leg to each charger gets the same camera avoidance as the rest — that
     * is the whole point, and it is why this re-plans rather than just drawing a
     * line to the charger.
     *
     * It repeats because one stop often isn't enough: a trip twice the car's
     * range needs two, and the second can only be chosen once the first has
     * changed the route it sits on.
     */
    fun onChargeFirst() {
        val finder = chargeStopFinder ?: return
        if (_state.value.findingChargeStop) return
        val destination = (_state.value.phase as? Phase.Solved)?.destination ?: return

        _state.update { it.copy(findingChargeStop = true, chargeStopSearchFailed = false) }
        workScope.launch {
            var added = 0
            var foundNone = false
            while (added < MAX_CHARGE_STOPS) {
                val solved = _state.value.phase as? Phase.Solved ?: break
                val check = _state.value.rangeCheck ?: break
                if (check.level != RangeCheck.Level.SHORT) break

                val chargers = runCatching {
                    finder.onRoute(
                        solved.chosen.polyline,
                        // usableMeters is already derated and has an arrival
                        // reserve removed; taking another percentage off here
                        // rejected chargers inside the range shown right above
                        // this button.
                        reachFor(check),
                    )
                }.getOrNull().orEmpty()
                    .filterNot { it.location in _state.value.chargeStops }

                val charger = chargers.firstOrNull()
                if (charger == null) {
                    foundNone = added == 0
                    break
                }

                _state.update {
                    it.copy(
                        stops = (it.stops + charger).sortedBy { stop ->
                            pointToPolylineProgress(stop.location, solved.chosen.polyline).alongMeters
                        },
                        chargeStops = it.chargeStops + charger.location,
                        chargeStopAlternatives = chargers.drop(1).take(MAX_CHARGE_ALTERNATIVES),
                        phase = Phase.Solving(destination),
                    )
                }
                runPlan(destination)
                added++
            }
            _state.update { it.copy(findingChargeStop = false, chargeStopSearchFailed = foundNone) }
        }
    }

    /**
     * How far the car can go before this stop. The first leg runs on what is in
     * the battery; once a charging stop is already in the trip, the next one is
     * reached from a charge.
     */
    private fun reachFor(check: RangeCheck): Double =
        if (check.hasChargingStops) check.chargedUsableMeters else check.usableMeters

    /** Use an explicitly selected alternative returned by the last charger lookup. */
    fun onChargeAlternative(index: Int) {
        val charger = _state.value.chargeStopAlternatives.getOrNull(index) ?: return
        val solved = _state.value.phase as? Phase.Solved ?: return
        _state.update {
            // The alternatives belong to the charging stop added last, so
            // choosing one replaces that rather than making a two-charger trip.
            val replaced = it.chargeStops.lastOrNull()
            it.copy(
                stops = it.stops.filterNot { stop -> stop.location == replaced } + charger,
                chargeStops = it.chargeStops - setOfNotNull(replaced) + charger.location,
                chargeStopAlternatives = emptyList(),
                phase = Phase.Solving(solved.destination),
            )
        }
        workScope.launch { runPlan(solved.destination) }
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
            val (result, steering) = pushForDriving(plan)

            _state.update {
                when (result) {
                    is PushResult.Success -> it.copy(phase = Phase.Driving(solved.destination, plan))
                    is PushResult.DestinationOnly -> it.copy(
                        phase = Phase.Driving(
                            solved.destination,
                            plan.copy(destinationOnly = true, steerByWaypoints = steering),
                            destinationOnly = true,
                        ),
                    )
                    is PushResult.Failed -> it.copy(
                        phase = Phase.PushFailed(solved.destination, option, result.reason, result.retryable),
                    )
                }
            }
        }
    }

    /**
     * Get [plan] to the car, and say whether it ended up being steered pin by
     * pin rather than sent to the destination.
     *
     * A car that takes only one destination can still be made to follow the
     * route — by being pointed at the next pin instead, and having that pin
     * moved along as the drive goes (the monitor takes over from there). Not on
     * a trip that needs charging, though: a car aiming a few miles up the road
     * plans no charging for the real trip, and being told where the charger is
     * matters more than the shape of the road to it.
     */
    private suspend fun pushForDriving(plan: DrivePlan): Pair<PushResult, Boolean> {
        val canSteer = plan.chain.size > 1 && tripHasRangeToSpare()

        // Known single-destination car: aim it at the first pin straight away.
        // Sending the whole chain first would collapse to the destination, and
        // the car would start navigating the unshaped route — and plan charging
        // for it — in the seconds before being redirected.
        if (canSteer && carTakesDestinationOnly) {
            val aimed = pushOrFailure(listOf(plan.chain.first()))
            if (aimed is PushResult.DestinationOnly) return aimed to true
            // It took a chain command after all (or wouldn't take anything):
            // forget what we thought and let the full push settle it.
            carTakesDestinationOnly = false
        }

        val result = pushOrFailure(plan.chain)
        if (result is PushResult.DestinationOnly) carTakesDestinationOnly = true
        val steering = result is PushResult.DestinationOnly && canSteer && aimAtFirstWaypoint(plan)
        return result to steering
    }

    private suspend fun pushOrFailure(chain: List<GeoPoint>): PushResult =
        runCatching { vehicle.pushRoute(chain) }
            .getOrElse { e -> PushResult.Failed("push threw: ${e.message}", retryable = true) }

    /**
     * True when the trip clearly doesn't need a charging stop, so the car can
     * be steered pin by pin. Unknown range counts as "to spare": with no car
     * connected there is no charging to plan around, and steering the route is
     * the whole point of the app.
     */
    private fun tripHasRangeToSpare(): Boolean =
        _state.value.rangeCheck?.level != RangeCheck.Level.SHORT

    /**
     * Point the car at the first shaping pin. Returns whether it landed — if it
     * didn't, the car still holds the destination and must keep being treated
     * that way, rather than the monitor assuming a steer that never happened.
     */
    private suspend fun aimAtFirstWaypoint(plan: DrivePlan): Boolean =
        pushOrFailure(listOf(plan.chain.first())) !is PushResult.Failed

    /** Cancel the drive (user tapped cancel). The activity stops the service. */
    fun onStopDrive() {
        if (_state.value.phase is Phase.Driving) {
            _state.update { it.copy(phase = Phase.Browsing, query = "", suggestions = emptyList(), rangeCheck = null, chargeStopSearchFailed = false).clearedOfCharging() }
        }
    }

    /**
     * The monitor put a different route in force — off-route recovery, or a
     * charging leg. Swap it into the driving phase so the map and the sheet
     * describe the road actually being driven.
     */
    fun onRouteReplanned(plan: DrivePlan) {
        _state.update { state ->
            val driving = state.phase as? Phase.Driving ?: return@update state
            state.copy(phase = driving.copy(plan = plan))
        }
    }

    /** The monitor reported arrival; leave the driving phase. */
    fun onArrived() {
        if (_state.value.phase is Phase.Driving) {
            _state.update { it.copy(phase = Phase.Browsing, query = "", suggestions = emptyList(), rangeCheck = null, chargeStopSearchFailed = false).clearedOfCharging() }
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
        _state.update { it.copy(phase = Phase.Browsing, query = "", suggestions = emptyList(), rangeCheck = null, chargeStopSearchFailed = false).clearedOfCharging() }
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
            // The driver's own stops are in the chain too, but must not be shed
            // on approach the way shaping pins are — they're where they're going.
            stopPoints = _state.value.stops.map { it.location }.toSet(),
        )

    /**
     * The same state without any charging stop: they are a consequence of one
     * route's arithmetic and mean nothing on another. Stops the driver added
     * themselves are untouched.
     */
    private fun PlanUiState.clearedOfCharging(): PlanUiState = copy(
        stops = stops.filterNot { it.location in chargeStops },
        chargeStops = emptySet(),
        chargeStopAlternatives = emptyList(),
        chargersOnRoute = emptyList(),
    )

    companion object {
        /** Label for a map point we couldn't name. */
        const val DROPPED_PIN = "Dropped pin"

        /** Fallback search bias when no location is known (US geographic center). */
        val DEFAULT_BIAS = GeoPoint(39.8283, -98.5795)
        private const val MAX_CHARGE_ALTERNATIVES = 3

        /** Enough for a long day's drive; a guard against looping, not a policy. */
        private const val MAX_CHARGE_STOPS = 4
    }
}
