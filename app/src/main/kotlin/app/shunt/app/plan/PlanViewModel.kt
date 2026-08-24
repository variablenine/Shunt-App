package app.shunt.app.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.PlanOutcome
import app.shunt.solver.brouter.PlannedRoute
import app.shunt.solver.brouter.RouteChoice
import app.shunt.solver.charging.RangeCheck
import app.shunt.solver.charging.RangeEstimate
import app.shunt.solver.geo.haversineMeters
import app.shunt.solver.geo.pointToPolylineProgress
import app.shunt.tesla.PushResult
import app.shunt.tesla.VehicleNavClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * Records what planning decided, for a bug report. Absent, nothing is kept.
     * Takes a lambda rather than the log itself so this stays testable without
     * a file on disk — and so the app module owns where it is written.
     */
    private val log: ((String, List<GeoPoint>) -> Unit)? = null,
    /**
     * Asks for the rest of a long trip to be planned, once its first leg is on
     * the chooser.
     *
     * Owned outside the screen deliberately: the legs have to keep arriving
     * after the driving sheet takes over and the plan screen is gone, and they
     * have to reach the drive monitor as well as the map. See
     * `AppContainer.planRemainingLegs`.
     */
    /**
     * Ask for the rest of a split trip. The third argument is the line of the
     * leg being shown, so the leg after it can be checked for doubling back
     * over its tail — see `LegJoin`.
     */
    private val onLaterLegsNeeded: (
        (List<GeoPoint>, Destination, List<GeoPoint>, List<GeoPoint>, RouteChoice) -> Unit
    )? = null,
    /**
     * Stop planning the rest of a trip and throw away what has been planned.
     *
     * Called from every path back to browsing. Without it a cancelled trip kept
     * planning in the background and kept its legs on the map — reported as
     * *"when I cancel a route it needs to cancel the leg calculations too. In
     * fact, the route needs to disappear, it hasn't done that this whole time"*.
     */
    private val onLaterLegsAbandoned: (() -> Unit)? = null,
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
    private var legsJob: Job? = null

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
            // **Typing is not a network failure.** The next keystroke cancels
            // the search in flight, and `runCatching` catches the resulting
            // CancellationException like any other throwable — so without this,
            // carrying on typing raised "Couldn't reach search — check your
            // connection" against a search that was working fine.
            //
            // It reads as a rate limit because of when it shows up: only once a
            // request is genuinely out, so typing *through* the debounce is
            // safe and pausing mid-word is what triggers it.
            ensureActive()
            // And an answer to a query the box has moved on from is stale
            // whatever it says. Cancellation usually covers this, but it is not
            // guaranteed to win the race, and a stale list is worse than a late
            // one: it is wrong about what the user is looking at.
            if (_state.value.query != query) return@launch
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
    /**
     * Route to a spot pressed on the map.
     *
     * **Planning starts on this call, not after the reverse geocode.** Naming
     * the point is a network round-trip of about a second, and waiting for it
     * meant a long press did nothing visible for that second — which reads as a
     * press that missed. The name is cosmetic: routing needs the coordinate, and
     * the coordinate is already in hand.
     *
     * So the pin and the plan go up immediately under a placeholder name, and
     * the real name is dropped in when it arrives — matched by *location*, so a
     * slow answer that lands after the driver has moved on cannot rename
     * somewhere else.
     */
    fun onMapLongPress(point: GeoPoint) {
        searchJob?.cancel()
        planTo(Destination(DROPPED_PIN, point))
        val namer = placeNamer ?: return
        workScope.launch {
            val name = runCatching { namer.nameFor(point) }.getOrNull() ?: return@launch
            _state.update { state ->
                val phase = state.phase
                val named = Destination(name, point)
                when {
                    phase is Phase.Solving && phase.destination.location == point ->
                        state.copy(phase = phase.copy(destination = named))
                    phase is Phase.Solved && phase.destination.location == point ->
                        state.copy(phase = phase.copy(destination = named))
                    phase is Phase.NeedTile && phase.destination.location == point ->
                        state.copy(phase = phase.copy(destination = named))
                    else -> state
                }
            }
            // Recents were written with the placeholder; rewrite so the name is
            // what shows next time, which is the whole point of pinning a place
            // the map data cannot name.
            recentPlaces?.let { store ->
                runCatching { store.record(Destination(name, point)) }
                _state.update { it.copy(recents = store.load()) }
            }
        }
    }

    /**
     * Route to a place picked from the recents list.
     *
     * Indexes [PlanUiState.recentsShown] — the same list the screen drew — so
     * the row that was tapped is the place that gets planned. Indexing the full
     * history here instead would send the driver somewhere else entirely the
     * moment the list was filtered by what they had typed.
     */
    fun onRecentSelected(index: Int) {
        _state.value.recentsShown.getOrNull(index)?.let { planTo(it) }
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
                // What the chooser was offered, which is the single most useful
                // line in a bug report about a route: it says what Shunt thought
                // the options were, before the driver picked one.
                log?.invoke(
                    "planned " + outcome.options.joinToString(", ") { o ->
                        "${o.choice} ${o.distanceMeters / 1000}km/${o.camerasPassed}cam/${o.waypoints.size}pins"
                    },
                    emptyList(),
                )
                // Start on the rest of the trip immediately, while the driver
                // is still reading the chooser. The phone is idle and the legs
                // are what turn a first-leg route into a whole one — waiting for
                // Go meant the map showed a line stopping in open country for as
                // long as somebody took to decide.
                val start = defaultOption(outcome.options)
                requestLaterLegs(outcome.remaining, destination, outcome.options, selected = start)
                // Opened before the chooser is shown, and therefore before Go
                // can be tapped. Doing it inside checkRange() left a window
                // between the two where the gate did not exist yet, which is the
                // same bug one instruction narrower.
                val gate = rangeReader?.let { CompletableDeferred<Unit>() }
                rangeReady = gate
                _state.update {
                    it.copy(
                        phase = Phase.Solved(
                            destination,
                            outcome.options,
                            selected = start,
                            timings = outcome.timings,
                            remaining = outcome.remaining,
                            wholeTripMeters = outcome.wholeTripMeters,
                            carriedForward = outcome.carriedForward,
                            directAhead = outcome.directAhead,
                            directAheadRoadPoints = outcome.directAheadRoadPoints,
                        ),
                        chargeStopSearchFailed = false,
                        checkingRange = gate != null,
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
            is PlanOutcome.Failed -> {
                log?.invoke("planning failed: ${outcome.reason}", emptyList())
                _state.update { it.copy(phase = Phase.Error(outcome.reason)) }
            }
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
            // And so do the legs after this one. Picking Balanced and then
            // watching every later leg detour miles around a single camera is
            // the chooser meaning nothing past the first stretch — the driver
            // is only ever offered one, so it has to govern the whole trip.
            //
            // This restarts leg planning, which throws away whatever had
            // landed. That is the honest cost of changing the trade-off, and it
            // only happens on a deliberate tap.
            requestLaterLegs(solved.remaining, solved.destination, solved.options, index)
        }
    }

    /**
     * Which option the chooser opens on: the one that passes fewest cameras.
     *
     * **It used to be index 0, which is the plain fastest road**, and that was
     * wrong twice over. The obvious half is that the app exists to avoid
     * cameras, so opening on the road that avoids none makes the driver opt in
     * to the whole point of it. The half that actually did damage is that the
     * selection governs every *later* leg — [requestLaterLegs] plans them to the
     * chosen trade-off — so a driver who never touched the chooser got a first
     * leg they could see was camera-aware and a whole trip after it planned as
     * fastest. Reported twice from real routes: "the last leg still is taking
     * the fastest route".
     *
     * Falls back through fewest-cameras by name, then by count, because a leg
     * through empty country produces one route and nothing to choose between.
     */
    private fun defaultOption(options: List<PlannedRoute>): Int {
        val named = options.indexOfFirst { it.choice == RouteChoice.FEWEST_CAMERAS }
        if (named >= 0) return named
        val best = options.withIndex().minWithOrNull(
            compareBy({ it.value.camerasPassed }, { it.value.distanceMeters }),
        )
        return best?.index ?: 0
    }

    /**
     * Ask for the rest of the trip, planned to the trade-off [selected] names.
     *
     * The lead leg's line and pins go with it so the join between this leg and
     * the next can be repaired — see `LegJoin` — and they have to be *this*
     * option's, not some other one's, or the seam would be computed against a
     * road the driver is not taking.
     */
    private fun requestLaterLegs(
        remaining: List<GeoPoint>,
        destination: Destination,
        options: List<PlannedRoute>,
        selected: Int,
    ) {
        if (remaining.isEmpty()) return
        val option = options.getOrNull(selected) ?: options.firstOrNull() ?: return
        onLaterLegsNeeded?.invoke(remaining, destination, option.polyline, option.waypoints, preferenceOf(option, options))
    }

    /**
     * What the driver's pick *means* for the rest of the trip.
     *
     * **Not `option.choice`, and that cost a real trip its avoidance.** The
     * choice is the name of the pass that produced the geometry, and on a leg
     * where every pass finds the same camera-free road the options collapse to
     * one card — labelled `FASTEST`, because that is the pass that ran first.
     * Passing that name on planned **every later leg as the plain fastest
     * road**, and a leg into a metro then took 62 avoidable cameras.
     *
     * Straight out of a real diagnostic log, and the comparison is stark. Two
     * trips minutes apart:
     *
     * ```
     * planned FASTEST 250km/9cam, FEWEST_CAMERAS 270km/0cam  → legs: 0,0,0,0,0,0 cameras
     * planned FASTEST 260km/0cam                             → legs: 0,1,62 cameras
     * ```
     *
     * The second trip's lead leg was *camera-free*. It was the label that was
     * wrong, not the route.
     *
     * So the question is not which pass produced this option, it is whether the
     * driver settled for more cameras than they were offered. Picking the
     * least-watched option available — which includes the case where there is
     * only one — means fewest cameras, for the whole trip. Only a deliberate tap
     * on something worse carries that trade-off forward.
     */
    private fun preferenceOf(option: PlannedRoute, options: List<PlannedRoute>): RouteChoice {
        val fewest = options.minOfOrNull { it.camerasPassed } ?: return option.choice
        return if (option.camerasPassed <= fewest) RouteChoice.FEWEST_CAMERAS else option.choice
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
    /**
     * Completes when the in-flight range read has finished, or null when none
     * was started. [onGo] waits on it — see [tripHasRangeToSpare].
     */
    private var rangeReady: CompletableDeferred<Unit>? = null

    private suspend fun checkRange() {
        val reader = rangeReader ?: return
        try {
            lastRangeReading = runCatching { reader.read() }.getOrNull()
            _state.update { it.copy(rangeCheck = rangeCheckFor(it.phase)) }
        } finally {
            _state.update { it.copy(checkingRange = false) }
            rangeReady?.complete(Unit)
        }
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
        val plan = drivePlanFor(option, solved.destination, solved.remaining)
        _state.update { it.copy(phase = Phase.Pushing(solved.destination, option)) }
        workScope.launch {
            // Let the range read land before deciding how to drive this trip.
            // Steering pin by pin is only safe when the trip does not need
            // charging, and "not read yet" used to be indistinguishable from
            // "plenty" — so on a long trip Go set off steering, the car never
            // planned a charge for the real route, and the whole charging path
            // was skipped. Bounded, because a vehicle API that never answers
            // must not strand the driver on a spinner.
            rangeReady?.let { withTimeoutOrNull(RANGE_WAIT_MILLIS) { it.await() } }
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
            backToBrowsing()
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
            backToBrowsing()
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
        backToBrowsing()
    }

    /**
     * Back to an empty map, with nothing still running.
     *
     * The single way out of every non-browsing phase, because leaving by any
     * other door is how a cancelled trip came to keep planning its later legs in
     * the background and keep drawing them. Cancelling has to mean cancelling.
     */
    private fun backToBrowsing() {
        legsJob?.cancel()
        onLaterLegsAbandoned?.invoke()
        _state.update {
            it.copy(
                phase = Phase.Browsing,
                query = "",
                suggestions = emptyList(),
                rangeCheck = null,
                chargeStopSearchFailed = false,
                laterLegs = emptyList(),
            ).clearedOfCharging()
        }
    }

    /**
     * The plan handed to the drive monitor. The chain is the chosen route's
     * intermediate pins (which hold the vehicle on the camera-aware path)
     * followed by the destination itself. Cameras are the ones this route
     * passes, to warn about — empty for a camera-free route.
     */
    private fun drivePlanFor(
        option: PlannedRoute,
        destination: Destination,
        remaining: List<GeoPoint> = emptyList(),
    ): DrivePlan =
        DrivePlan(
            destination = destination,
            // **This leg's own end, not the trip's.**
            //
            // On a split trip `option` describes the first leg only, so
            // appending the trip's destination puts a waypoint hundreds of
            // kilometres past where this route stops. Two things go wrong with
            // that, and both were live: the car is aimed at the destination the
            // moment it passes the last pin of leg one, losing avoidance for
            // everything after it; and `DriveMonitor.extend` appends the next
            // leg onto a chain that still ends in the destination, so the
            // trip's end sits in the *middle* and the car is sent there and
            // then back. `remaining.first()` is the boundary, which is where
            // this leg actually stops and where the next one starts.
            //
            // AppContainer fixes exactly this for later legs; the lead leg is
            // built here and kept the bug.
            chain = option.waypoints + (remaining.firstOrNull() ?: destination.location),
            cameras = option.passedCameras,
            polyline = option.polyline,
            // The driver's own stops are in the chain too, but must not be shed
            // on approach the way shaping pins are — they're where they're going.
            stopPoints = _state.value.stops.map { it.location }.toSet(),
            remaining = remaining,
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

        /**
         * How long Go will wait for the car's range before setting off anyway.
         *
         * Long enough for an ordinary vehicle-API round trip, short enough that
         * a service which has silently stopped answering does not leave the
         * driver holding a spinner. Timing out lands on the old behaviour —
         * unknown range is treated as enough — which is the documented choice
         * for an unreadable car, not a new risk.
         */
        private const val RANGE_WAIT_MILLIS = 6_000L

        /** Enough for a long day's drive; a guard against looping, not a policy. */
        private const val MAX_CHARGE_STOPS = 4
    }
}
