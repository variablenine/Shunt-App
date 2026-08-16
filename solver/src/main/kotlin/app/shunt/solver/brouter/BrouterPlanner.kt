package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.geo.BoundingBox
import app.shunt.solver.geo.bearingDegrees
import app.shunt.solver.geo.destinationPoint
import app.shunt.solver.geo.haversineMeters
import app.shunt.solver.waypoints.WaypointExtractor
import app.shunt.solver.waypoints.WaypointRefiner

/** A chooseable route: BRouter's geometry plus the pins + cameras Shunt needs. */
data class PlannedRoute(
    val choice: RouteChoice,
    val polyline: List<GeoPoint>,
    /** Intermediate pins that hold the vehicle on this path (excludes destination). */
    val waypoints: List<GeoPoint>,
    /** The actual camera records this route passes within standoff of. */
    val passedCameras: List<Camera>,
    /**
     * Every camera near this route, whether passed or avoided. Without it a
     * camera-free route is indistinguishable on the map from a route through
     * empty country, and there is no way to see what the detour bought.
     */
    val nearbyCameras: List<Camera> = emptyList(),
    val distanceMeters: Int,
    val estimatedSeconds: Int,
    val exposureMeters: Int,
    /** Extra travel time vs. the fastest option (0 for the fastest). */
    val addedSecondsVsFastest: Int,
    /**
     * Every camera was treated as impassable and no route came back. This is
     * diagnostic rather than proof that a camera is unavoidable: an endpoint
     * inside a nogo or an engine failure can produce the same result.
     */
    val hardAvoidanceFailed: Boolean = false,
    /**
     * How many of [passedCameras] watch the trip's own start or destination.
     *
     * These are the cameras no route can dodge — they are pointed at where the
     * driver is going — and telling them apart from the rest is what keeps a
     * clean route from reading as a failure. See
     * [app.shunt.solver.brouter.BrouterRouter.withoutZonesHolding].
     */
    val unavoidableAtEndpoints: Int = 0,
) {
    val camerasPassed: Int get() = passedCameras.size
}

/** Outcome of planning a trip with the native BRouter engine. */
sealed interface PlanOutcome {
    /**
     * One to three distinct options, fastest first, for the user to choose.
     * [timings] is a temporary diagnostic — see [PlanTimings].
     */
    data class Routes(
        val options: List<PlannedRoute>,
        val timings: PlanTimings? = null,
        /**
         * When the trip was too long to plan in one go, what is left of it —
         * starting at the leg boundary these options end at, and ending at the
         * real destination. Empty when these options cover the whole trip.
         *
         * The caller is expected to plan this while the driver is already
         * moving. See [LegSplitter] for why the boundary is where it is, and why
         * it is safe to be held to it.
         */
        val remaining: List<GeoPoint> = emptyList(),
        /**
         * The whole trip's length by the direct road, when these options are
         * only the first leg of it.
         *
         * Free — it comes from the spine, which is planned before anything else
         * — and the result sheet needs it to be honest: showing a leg's distance
         * as if it were the trip's would be a plain lie.
         */
        val wholeTripMeters: Int? = null,
        /**
         * These options come from an earlier, narrower round of the search,
         * because the round that followed a corridor widen ran out of time.
         *
         * They are labelled against the full camera set and every one of them is
         * covered by it, so the counts shown are true. What is no longer claimed
         * is that they are the *best* routes for that wider set. Worth saying
         * plainly to the driver — see CLAUDE.md §7.10.
         */
        val carriedForward: Boolean = false,
        /**
         * The direct road onward from the leg boundary, when these options are
         * only the first leg.
         *
         * Free — it is a slice of the spine that was planned to choose the cut —
         * and it lets the map draw the part not yet planned along **roads that
         * exist** rather than as a straight line across country. Each leg that
         * lands then replaces a piece of it with the camera-avoiding version.
         */
        val directAhead: List<GeoPoint> = emptyList(),
    ) : PlanOutcome {
        /** Whether these options stop short of the destination. */
        val isPartial: Boolean get() = remaining.isNotEmpty()
    }

    /** The offline map tiles for this trip aren't downloaded yet (full-replace). */
    data class NeedsDownload(val tiles: List<TileId>) : PlanOutcome

    /** No route (unroutable pair, engine error). */
    data class Failed(val reason: String) : PlanOutcome
}

/**
 * Turns BRouter's route alternatives into chooseable [PlannedRoute]s: extracts
 * the vehicle waypoints (where each option diverges from the fastest), resolves
 * the real [Camera] records each option passes, and scores added time. Reports
 * [PlanOutcome.NeedsDownload] when the trip's tiles aren't present, so the UI
 * can prompt rather than silently fall back.
 *
 * Collaborators are injected as functions so the whole thing is unit-testable
 * without a real tile on disk.
 */
class BrouterPlanner(
    private val route: suspend (RouteRequest) -> List<BrouterRoute>,
    private val missingTiles: (BoundingBox) -> List<TileId>,
    private val camerasIn: suspend (BoundingBox) -> List<Camera>,
    private val bboxMarginMeters: Double = ROUTE_BBOX_MARGIN_METERS,
    /**
     * Half-width of the corridor along the direct road that cameras are taken
     * from. Separate from [bboxMarginMeters], which only decides which map tiles
     * a trip needs — tiles are cheap and cameras are not.
     */
    private val corridorMeters: Double = CAMERA_CORRIDOR_METERS,
    /** Optional on-disk/engine state summary, appended to a no-route failure. */
    private val diagnostics: () -> String? = { null },
    /**
     * How long the pin-refinement phase may take before it settles for the pins
     * it has. Refinement costs one routing pass per candidate per option, which
     * is unbounded in the length and camera density of the trip; the route
     * itself is already decided by the time it starts. See [WaypointRefiner].
     */
    private val refineBudgetMillis: Long = REFINE_BUDGET_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /**
     * The per-pass breakdown of the routing that just ran. Temporary diagnostic
     * — see [PlanTimings]. Supplied as a function because the planner is given a
     * routing *lambda*, not the engine, and this deliberately doesn't change
     * that: the seam is what makes planning testable without a map tile.
     */
    private val lastPassTimings: () -> List<PlanTimings.Timed> = { emptyList() },
    /**
     * How far a camera is treated as seeing, as a multiple of the built-in
     * estimate. A function rather than a value so a change in settings applies
     * to the next plan without rebuilding the planner.
     *
     * Read once per plan and carried on every [CameraVision] it makes, so the
     * routing, the labelling, the warnings and the map cannot disagree.
     */
    private val cameraRangeScale: () -> Double = { 1.0 },
) {
    /**
     * Plan a trip. [onProgress] reports coarse 0f..1f progress with a label, so
     * a long cross-state plan (several routing passes over a wide camera set)
     * can show real movement instead of an unexplained wait.
     */
    suspend fun plan(
        origin: GeoPoint,
        destination: GeoPoint,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        headingDegrees: Double? = null,
    ): PlanOutcome = plan(listOf(origin, destination), onProgress, headingDegrees)

    /**
     * Plan through [points]: origin, any intermediate stops in order, then the
     * destination. Stops are honoured by the routing engine directly, and are
     * always pinned for the vehicle — they are places the driver actually wants
     * to be, not shaping hints.
     */
    suspend fun plan(
        points: List<GeoPoint>,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        /**
         * The bearing the vehicle is travelling on, when it is moving. Routes
         * then set off the way the car is already pointing rather than
         * doubling back. Null when parked or unknown.
         */
        headingDegrees: Double? = null,
        /**
         * Override the pin-refinement budget for this plan. A re-plan computed
         * at 60 mph is worth much less the longer it takes — the junction it was
         * needed for keeps approaching — so a mid-drive caller should ask for
         * far less than a driver sitting still will happily wait through.
         */
        refineBudgetMillis: Long = this.refineBudgetMillis,
        /**
         * Roads the driver has refused — a closure, a turn the car will not
         * take. Blocked on every option for this plan only; see [RouteRequest].
         */
        blocked: List<GeoPoint> = emptyList(),
        /** Ceiling on each routing call, or null for the router's own default. */
        routeBudgetMillis: Long? = null,
        /**
         * Cut trips longer than this into legs and plan only the first one,
         * returning the rest in [PlanOutcome.Routes.remaining]. Null plans the
         * whole trip however long it is.
         *
         * A mid-drive re-plan passes null: it is already short (what is left of
         * the current leg), and handing a driver a *new* boundary while they are
         * moving is a complication with nothing to buy it.
         *
         * **Defaults to off, and must stay that way until every caller handles
         * [PlanOutcome.Routes.remaining].** A caller that ignores it is handed
         * options ending at a leg boundary and no indication of that, so it
         * would drive someone to a point in open country and call it their
         * destination — a worse failure than the slow plan splitting exists to
         * prevent. Opt in from the app once the drive can be extended.
         */
        maxLegMeters: Double? = null,
    ): PlanOutcome {
        require(points.size >= 2) { "a trip needs at least an origin and a destination" }
        // Deliberately the *whole* trip's box, not the first leg's. The legs
        // after the first are planned while the car is moving and may have no
        // network, so every tile the trip needs has to be on disk before the
        // driver sets off — asking for the rest halfway across a state is how a
        // split trip would strand someone.
        val baseBbox = BoundingBox.of(points).expand(bboxMarginMeters)

        val missing = missingTiles(baseBbox)
        if (missing.isNotEmpty()) return PlanOutcome.NeedsDownload(missing)

        // One deadline for every search in this plan.
        //
        // The budget used to belong to a single routing *call*, and planning
        // makes several — the spine, then one per widen of the camera area — so
        // each got a fresh allowance and the true worst case was a multiple of
        // the number nominally in force. Sharing it means widening the corridor
        // costs time from the same pot rather than minting more.
        val planDeadline = nowMillis() + (routeBudgetMillis ?: BrouterRouter.PASS_BUDGET_MILLIS)
        fun budgetLeft(): Long = (planDeadline - nowMillis()).coerceAtLeast(1L)

        // Temporary instrumentation — see PlanTimings.
        val stages = mutableListOf<PlanTimings.Timed>()
        val routingPasses = mutableListOf<PlanTimings.Timed>()
        var cameraMillis = 0L
        var routingMillis = 0L
        var startedAt: Long

        // Find the direct road first, with no cameras at all.
        //
        // This is the cheapest search there is — nothing to check each link
        // against — and it buys the two things the expensive passes need: the
        // shape the camera area should be drawn around, and a spine to measure
        // detours against. Sizing that area from the straight origin→destination
        // box instead meant the routes bulged out of it *every single time*, so
        // the widen below was not a rare correction, it was guaranteed, and
        // every trip paid for the whole search twice.
        onProgress(0.15f, "Finding the direct route")
        startedAt = nowMillis()
        // **Capped, and that cap is what keeps a very long trip plannable at
        // all.** This pass is cheap relative to an avoidance pass, but it is
        // over the *whole* trip while everything after it works on one leg — so
        // on a cross-country route it is the expensive one. Measured on a real
        // 3,598 km trip: 64 s of a 71 s plan.
        //
        // Given the whole budget it can take the whole budget, and then every
        // pass that follows gets the 1 ms floor and dies. The driver sees "No
        // route found — the offline map for this area may be incomplete", which
        // is not what happened and sends them to re-download tiles they already
        // have. Running out here is *recoverable* — the spine falls back to the
        // straight line below, which only costs a fatter corridor — so this is
        // exactly the pass to take time away from.
        //
        // **And it only routes as far as the cut needs to see.** On a trip that
        // is going to be split, everything below works on the first leg — so a
        // spine running to a destination three thousand kilometres away is
        // computing a road nothing in this call will ever look at. Stopping it
        // just past the leg window makes the cost of this pass a function of
        // MAX_LEG_METERS instead of trip length, which is what stops a
        // cross-country trip failing outright. The rest of the road gets planned
        // leg by leg, which was always the plan.
        val spineTarget = spineProbe(points, maxLegMeters)
        val direct = runRoutes(
            spineTarget, emptyList(), headingDegrees, blocked,
            (budgetLeft() * SPINE_BUDGET_SHARE).toLong().coerceAtLeast(1L),
        )
        routingMillis += nowMillis() - startedAt
        routingPasses += lastPassTimings().map { it.copy(label = "${it.label} (spine)") }
        var spine = direct?.firstOrNull()?.polyline?.takeIf { it.size >= 2 }?.let { sampleSpine(it) }
            // No direct route is not fatal — fall back to the straight line
            // between the trip's own points.
            //
            // **Sampled, and that is not cosmetic.** Handed the bare two points,
            // `LegSplitter` has nothing between `MIN_LEG_METERS` and
            // `MAX_LEG_METERS` to choose from, so it cuts nothing, and a
            // three-thousand-kilometre trip is then planned in one go out of
            // whatever budget is left — which cannot succeed. A trip whose spine
            // failed is exactly the trip that most needs splitting.
            ?: sampleSpine(points)

        // Cut a long trip here, before anything expensive has run.
        //
        // The spine above is the whole trip's direct road, and it cost one cheap
        // search — so the cut is chosen with full knowledge of where the trip
        // goes, and everything below then works on the first leg alone: its own
        // corridor, its own camera set, its own budget. See [LegSplitter] for
        // why the boundary lands where it does.
        startedAt = nowMillis()
        val tripCameras = camerasAlong(spine) ?: return cameraDataUnavailable()
        cameraMillis += nowMillis() - startedAt
        val cut = maxLegMeters?.let { limit ->
            LegSplitter.cut(
                spine = spine,
                cameras = CameraIndex(visionsOf(tripCameras)),
                maxLegMeters = limit,
                // Everything between origin and destination is somewhere the
                // driver asked to be — a charging stop they picked, a stop they
                // added. A leg must not end short of the first of them.
                stops = points.drop(1).dropLast(1),
            )
        }
        val legPoints: List<GeoPoint>
        val remaining: List<GeoPoint>
        // Kept before the spine is truncated at the cut: this is the part the
        // legs after this one will cover, and the map draws it while they do.
        var directAhead: List<GeoPoint> = emptyList()
        if (cut == null) {
            legPoints = points
            remaining = emptyList()
        } else {
            val (first, rest) = LegSplitter.split(points, cut.point)
            legPoints = first
            remaining = rest
            directAhead = spine.subList(cut.index, spine.size)
            spine = spine.subList(0, cut.index + 1)
        }
        // The trip's own length, for the banner. Taken from the spine when it
        // covered the whole trip, and estimated from the straight line when it
        // deliberately stopped short — roads wander, so a bare great-circle
        // distance reads low enough to look wrong next to the legs that follow.
        val wholeTripMeters = when {
            cut == null -> null
            spineTarget.last() == points.last() -> direct?.firstOrNull()?.distanceMeters
            else -> (straightLength(points) * ROAD_WANDER_FACTOR).toInt()
        }

        onProgress(0.25f, "Finding cameras nearby")
        // Plan against a camera set that PROVABLY covers the routes being
        // labelled, by iterating to a fixed point: route, look at where the
        // routes actually went, and if that escapes the area we drew cameras
        // from, widen and route again.
        //
        // Getting this wrong is the "it drove past an avoidable camera" bug.
        // A route planned against a narrow set has never been asked to avoid
        // anything outside it, so counting it afterwards against a wider set
        // reports a camera the avoidance was never given a chance at — and the
        // hard-block pass still "succeeded", so nothing looks wrong. The check
        // below is what keeps the corridor honest: it is allowed to be as tight
        // as we like precisely because a route that leaves it is caught.
        startedAt = nowMillis()
        // Narrowed to this leg's spine rather than fetched again: the trip's set
        // above already covers a wider area, so this is a filter, not a lookup.
        var cameras = if (cut == null) tripCameras else filterAlong(tripCameras, spine)
        cameraMillis += nowMillis() - startedAt
        var routes: List<BrouterRoute> = emptyList()
        var covered = false

        /**
         * The best set of options any round produced.
         *
         * A widen is not a slightly wider camera set — it is **the whole chooser
         * run a second time out of the same budget**, and when that second round
         * runs out the driver is handed the plain fastest road, which is the one
         * thing this app exists not to do. Measured on a real last leg into a
         * dense metro: round one produced a balanced route in 20 s, that route
         * left the corridor, round two ran out of time, and the answer was the
         * fastest road past 126 cameras.
         *
         * So the earlier round is kept. What that gives up is the claim that the
         * route is *optimal* for the wider camera set. What it keeps is the
         * claim that actually matters — that the route is labelled against every
         * camera we know about — because the labelling below is recomputed from
         * the final set regardless of which round a route came from.
         *
         * **And every carried route is genuinely covered by that final set**,
         * which is what makes this sound rather than merely better than nothing:
         * the widen exists precisely to cover the routes that escaped, the spine
         * grows to include their own geometry, and a route that did not escape
         * was inside the narrower corridor to begin with.
         */
        var carried: List<BrouterRoute> = emptyList()

        // Set once a round has *proved* no hard-blocked route exists, so the
        // widen round does not spend a second search reaching the same answer.
        // Only on a proof — a timed-out block has shown nothing.
        var blockProvenImpossible = false

        for (pass in 0 until MAX_REFINEMENT_PASSES) {
            onProgress(0.4f + 0.12f * pass, if (pass == 0) "Planning routes" else "Widening the camera search")
            startedAt = nowMillis()
            val fresh = runRoutes(
                legPoints, cameras, headingDegrees, blocked, budgetLeft(),
                hardBlockProvenImpossible = blockProvenImpossible,
            )
            routingMillis += nowMillis() - startedAt
            // Label by iteration: a second one means the routes escaped the
            // camera area and the whole graph was searched again.
            val roundPasses = lastPassTimings()
            routingPasses += roundPasses.map { timed ->
                if (pass == 0) timed else timed.copy(label = "${timed.label} (widen ${pass + 1})")
            }
            // A block that reported "no route" settles it for every later round
            // of this plan; one that ran out of time settles nothing.
            if (roundPasses.any { it.label.startsWith("blocked") && "(no route)" in it.label }) {
                blockProvenImpossible = true
            }
            routes = fresh ?: return PlanOutcome.Failed("Routing failed.")
            if (routes.isEmpty()) return noRoute(anyPassRanOut(routingPasses))
            if (avoidanceCount(routes) > avoidanceCount(carried)) carried = routes

            val escaping = routes.filterNot { withinCorridor(it.polyline, spine) }
            if (escaping.isEmpty()) {
                covered = true
                break
            }
            // Those routes went somewhere we hadn't drawn cameras from. Add what
            // they actually did to the spine and go again, so the next pass
            // plans with the cameras out there in hand.
            spine = spine + escaping.flatMap { sampleSpine(it.polyline) }
            startedAt = nowMillis()
            cameras = camerasAlong(spine) ?: return cameraDataUnavailable()
            cameraMillis += nowMillis() - startedAt
        }

        if (!covered) {
            // Never label a route against cameras it was not planned to avoid.
            // Refusing is the honest outcome: the alternative is printing a
            // camera count the routing never had the chance to act on.
            return PlanOutcome.Failed(
                "Couldn't settle on the camera set for this trip — the routes kept " +
                    "detouring outside the area checked. Try a shorter trip or plan again.",
            )
        }

        // A later round that came back holding fewer avoidance options than an
        // earlier one lost them to the clock, not to the roads. Take the earlier
        // ones; they are labelled against the final camera set below. See
        // `carried` above for why that is sound.
        val carriedForward = avoidanceCount(routes) < avoidanceCount(carried)
        if (carriedForward) routes = carried

        val fastest = routes.first()
        val visions = cameras.map { CameraVision(it.location, it.directionDegrees, cameraRangeScale()) }
        // One grid, reused for every option and every waypoint check below.
        // Asking each camera to walk the whole route in turn is cameras ×
        // points, which on a cross-state trip is tens of millions of distance
        // calculations per option.
        val index = CameraIndex(visions)
        val byLocation = cameras.associateBy { it.location }
        onProgress(0.9f, "Checking the car will follow the detour")
        // One budget and one cache for the whole refinement phase. The options
        // share an origin, a destination and usually their first pins, so the
        // same leg was being routed once per option — three full passes over the
        // road graph for one answer.
        val deadline = nowMillis() + refineBudgetMillis
        val pinsStartedAt = nowMillis()
        val carPaths = HashMap<Pair<GeoPoint, GeoPoint>, List<GeoPoint>?>()
        val options = routes.map { r ->
            PlannedRoute(
                choice = r.choice,
                polyline = r.polyline,
                // Camera-aware: the vehicle routes itself between waypoints, so
                // they must also stop it cutting back through what we avoided.
                waypoints = withStops(
                    stops = points.drop(1).dropLast(1),
                    // The fastest option needs no shaping at all: it *is* the
                    // road the car picks when left alone, so there is nothing to
                    // hold it onto and every pass spent checking says so.
                    shaping = if (r === fastest) {
                        emptyList()
                    } else {
                        pinsTheCarWillFollow(
                            r.polyline, fastest.polyline, visions, index, deadline, carPaths,
                        )
                    },
                    polyline = r.polyline,
                ),
                // A camera is "passed" if the route enters its field of view.
                passedCameras = index.seeing(r.polyline).mapNotNull { byLocation[it.location] },
                nearbyCameras = index.within(r.polyline, NEARBY_CAMERA_METERS)
                    .mapNotNull { byLocation[it.location] },
                distanceMeters = r.distanceMeters,
                estimatedSeconds = r.estimatedSeconds,
                // Recomputed here rather than taken from the router, because a
                // route carried over from an earlier round was measured against
                // that round's narrower camera set. Every number the driver
                // reads has to describe the set we finished with.
                exposureMeters = CameraVision.metersSeen(
                    r.polyline,
                    index.within(r.polyline, CameraVision.OMNI_RANGE_M * cameraRangeScale() + NEARBY_CAMERA_METERS),
                ).toInt(),
                addedSecondsVsFastest = r.estimatedSeconds - fastest.estimatedSeconds,
                hardAvoidanceFailed = r.hardAvoidanceFailed,
                unavoidableAtEndpoints = r.unavoidableAtEndpoints,
            )
        }
        stages += PlanTimings.Timed(PlanTimings.STAGE_CAMERAS, cameraMillis)
        stages += PlanTimings.Timed(PlanTimings.STAGE_ROUTING, routingMillis)
        stages += PlanTimings.Timed(PlanTimings.STAGE_PINS, nowMillis() - pinsStartedAt)
        return PlanOutcome.Routes(
            options = options,
            timings = PlanTimings(stages, routingPasses),
            remaining = remaining,
            wholeTripMeters = wholeTripMeters,
            carriedForward = carriedForward,
            directAhead = directAhead,
        )
    }

    /**
     * Shaping pins for [chosen] that the car will actually honour.
     *
     * [WaypointExtractor] picks candidates from the shape of the route; this
     * then checks each one the only way that settles it — by routing the leg
     * the way the *car* will, with no avoidance, and seeing whether that path
     * enters a camera the route was built to dodge. Pins are added at the fork
     * until it doesn't, because the car takes the quickest road to a pin and
     * will not make an extra turn to reach one placed further along a detour.
     */
    private suspend fun pinsTheCarWillFollow(
        chosen: List<GeoPoint>,
        fastest: List<GeoPoint>,
        visions: List<CameraVision>,
        index: CameraIndex,
        deadline: Long,
        carPaths: MutableMap<Pair<GeoPoint, GeoPoint>, List<GeoPoint>?>,
    ): List<GeoPoint> {
        // One sweep of the line against the camera grid, shared by everything
        // below that needs to know which cameras this route dodges.
        val avoided = WaypointExtractor.avoidedIndex(chosen, visions, index)
        val candidates = WaypointExtractor.extract(
            chosen = chosen,
            fastest = fastest,
            avoid = visions,
            index = index,
            outOfTime = { nowMillis() >= deadline },
            avoided = avoided,
        )
        // Pins that exist because of the route's geometry — its turns, and the
        // cameras it squeezes past — which pruning must not second-guess:
        // dropping one means trusting BRouter's model of the car at exactly the
        // points where being wrong costs a wrong road or an exposure.
        val instructed = WaypointExtractor.protectedPins(chosen, fastest, index, avoided)
        return runCatching {
            WaypointRefiner.refine(
                chosen = chosen,
                pins = candidates,
                avoid = visions,
                index = index,
                protectedPins = instructed,
                outOfTime = { nowMillis() >= deadline },
                carRoute = { from, to -> carPathBetween(from, to, carPaths, deadline) },
            )
        }.getOrDefault(candidates)
    }

    /**
     * How the car would drive [from] to [to]: fastest, no camera avoidance.
     *
     * Memoised across every option in one plan. A null answer is cached too — a
     * leg the engine can't route stays unroutable, and re-asking costs as much
     * as asking did.
     *
     * **Bounded by what is left of the refinement budget**, and that matters far
     * more than it looks. The refiner checks the clock between legs, which
     * bounds nothing when a single leg is what runs long: a search is a tight
     * CPU loop with no suspension point, and this call carried no ceiling at
     * all, so it fell back to the router's own default — a whole pass budget,
     * *per leg*. Measured on a 615 km trip, a 20 s refinement phase took 349 s.
     * Same shape of bug as passing zero to BRouter's own timeout, one level up.
     */
    private suspend fun carPathBetween(
        from: GeoPoint,
        to: GeoPoint,
        carPaths: MutableMap<Pair<GeoPoint, GeoPoint>, List<GeoPoint>?>,
        deadline: Long,
    ): List<GeoPoint>? {
        val key = from to to
        if (carPaths.containsKey(key)) return carPaths[key]
        val left = (deadline - nowMillis()).coerceAtLeast(1L)
        val path = runCatching { route(RouteRequest(points = listOf(from, to), budgetMillis = left)) }
            .getOrNull()
            ?.firstOrNull()
            ?.polyline
            ?.takeIf { it.size >= 2 }
        carPaths[key] = path
        return path
    }

    /**
     * Every camera close enough to the roads under consideration to matter.
     *
     * The set handed to the router *is* the cost of routing: each link the
     * search expands is checked against every nogo, and on a long trip the
     * camera-carrying passes ran about thirty times slower than the same search
     * with none. So the difference between "cameras in the box around this trip"
     * and "cameras near the roads this trip could plausibly use" is the
     * difference between usable and not — a long diagonal trip's bounding box is
     * mostly country no route would ever touch, and every camera in it was being
     * paid for on every link.
     *
     * Still fetched by bounding box, because that is how camera data is tiled;
     * the corridor is a filter on the result.
     */
    private suspend fun camerasAlong(spine: List<GeoPoint>): List<Camera>? {
        val bbox = BoundingBox.of(spine).expand(corridorMeters)
        val all = runCatching { camerasIn(bbox) }.getOrNull() ?: return null
        return filterAlong(all, spine)
    }

    /**
     * [cameras] narrowed to the corridor around [spine].
     *
     * Split out from [camerasAlong] so a leg can reuse the trip's set instead of
     * asking for cameras again: the trip's spine covers a strictly wider area, so
     * narrowing it gives exactly the answer a fresh fetch would.
     */
    private fun filterAlong(cameras: List<Camera>, spine: List<GeoPoint>): List<Camera> =
        cameras.filter { camera -> nearSpine(camera.location, spine, corridorMeters + SPINE_SAMPLE_METERS) }

    private fun visionsOf(cameras: List<Camera>): List<CameraVision> =
        cameras.map { CameraVision(it.location, it.directionDegrees, cameraRangeScale()) }

    /**
     * Whether every point of [line] is inside the corridor drawn around [spine].
     *
     * This is the guarantee that lets the corridor be tight. A camera can only
     * affect a route it can see, which is a few hundred metres at most; so if
     * the route stays [CORRIDOR_SAFETY_METERS] inside the corridor the cameras
     * were drawn from, every camera that could see this route was in the set the
     * router was given. A route that fails this has been planned against an
     * incomplete set and must not be labelled — it goes back round the loop.
     */
    private fun withinCorridor(line: List<GeoPoint>, spine: List<GeoPoint>): Boolean {
        // Cameras were taken within (margin + sample spacing) of the spine, so a
        // route staying that far in — less the reach of a camera — cannot have
        // one near it that the router was not given. Derived from the filter in
        // camerasAlong rather than guessed at; the two have to move together.
        val limit = corridorMeters + SPINE_SAMPLE_METERS - CORRIDOR_SAFETY_METERS
        if (limit <= 0) return false
        return line.all { nearSpine(it, spine, limit) }
    }

    private fun nearSpine(p: GeoPoint, spine: List<GeoPoint>, meters: Double): Boolean =
        spine.any { haversineMeters(it, p) <= meters }

    /**
     * The line thinned to roughly one point per [SPINE_SAMPLE_METERS].
     *
     * A route is tens of thousands of points and the corridor tests compare
     * against all of them; at this spacing a few hundred stand in for the whole
     * line, and the sample spacing is added back as slack wherever the result is
     * used so thinning can only ever widen the corridor, never narrow it.
     */
    private fun sampleSpine(line: List<GeoPoint>): List<GeoPoint> {
        if (line.size < 2) return line
        val out = mutableListOf(line.first())
        var since = 0.0
        for (i in 1 until line.size) {
            val a = line[i - 1]
            val b = line[i]
            val length = haversineMeters(a, b)
            if (length <= 0.0) continue
            // Walk the segment, not just its ends. Keeping only vertices thins a
            // dense line correctly but leaves a sparse one — a re-planned leg, a
            // straight hop between two far-apart points — with gaps far wider
            // than the spacing, and everything downstream measures distance to
            // these points. A gap there quietly drops the cameras in it, which
            // is the "drove past an avoidable camera" bug wearing a new hat.
            var travelled = 0.0
            while (since + (length - travelled) >= SPINE_SAMPLE_METERS) {
                travelled += SPINE_SAMPLE_METERS - since
                val t = travelled / length
                out += GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                since = 0.0
            }
            since += length - travelled
        }
        out += line.last()
        return out
    }

    private fun cameraDataUnavailable(): PlanOutcome = PlanOutcome.Failed(
        "Couldn't load camera data for this area, so Shunt can't tell you which " +
            "cameras a route passes. Check your connection and try again.",
    )

    /** Route with the given cameras as field-of-view nogos; null if the engine threw. */
    private suspend fun runRoutes(
        points: List<GeoPoint>,
        cameras: List<Camera>,
        headingDegrees: Double? = null,
        blocked: List<GeoPoint> = emptyList(),
        budgetMillis: Long? = null,
        hardBlockProvenImpossible: Boolean = false,
    ): List<BrouterRoute>? {
        val visions = cameras.map { CameraVision(it.location, it.directionDegrees, cameraRangeScale()) }
        val request = RouteRequest(
            points, visions, headingDegrees, blocked, budgetMillis,
            hardBlockProvenImpossible = hardBlockProvenImpossible,
        )
        val found = runCatching { route(request) }.getOrNull()
        if (!found.isNullOrEmpty() || blocked.isEmpty()) return found
        // Blocking the road just abandoned is a heuristic, and in a town it can
        // take a parallel street with it and leave nothing at all. No route is a
        // worse answer than a route back onto a road the driver refused, so the
        // block is dropped rather than the trip.
        return runCatching { route(request.copy(blocked = emptyList())) }.getOrNull()
    }

    /**
     * Merge the user's stops into the shaping pins, in the order the route
     * reaches them. Stops are never dropped to make room: a shaping pin only
     * steers the car, while missing a stop means not going where the driver
     * asked.
     */
    private fun withStops(
        stops: List<GeoPoint>,
        shaping: List<GeoPoint>,
        polyline: List<GeoPoint>,
    ): List<GeoPoint> {
        if (stops.isEmpty()) return shaping
        fun progressAlong(p: GeoPoint): Int =
            polyline.indices.minByOrNull {
                app.shunt.solver.geo.haversineMeters(polyline[it], p)
            } ?: 0
        return (stops + shaping).sortedBy { progressAlong(it) }
    }

    /**
     * How many real avoidance options a round produced — everything that is not
     * the plain fastest road.
     *
     * The measure of whether a round is worth keeping. One that comes back with
     * only `fastest` has not decided there is no camera-free route; it has run
     * out of time to look.
     */
    private fun avoidanceCount(routes: List<BrouterRoute>): Int =
        routes.count { it.choice != RouteChoice.FASTEST }

    /**
     * No route came back.
     *
     * [ranOutOfTime] separates the two reasons, and getting that wrong is worse
     * than unhelpful: a driver told "the offline map for this area may be
     * incomplete" goes and re-downloads tiles they already have, for a trip that
     * simply needed longer to think about. The passes themselves say which it
     * was — a search that hit its ceiling is labelled, so this reads the labels
     * rather than guessing.
     */
    private fun noRoute(ranOutOfTime: Boolean = false): PlanOutcome {
        val detail = diagnostics()?.takeIf { it.isNotBlank() }?.let { "\n\n[$it]" } ?: ""
        return PlanOutcome.Failed(
            if (ranOutOfTime) {
                "Planning ran out of time on this trip — it's long enough that the " +
                    "search couldn't finish. Try again, or pick a nearer destination " +
                    "and extend it once you're moving."
            } else {
                "No route found — the offline map for this area may be incomplete.$detail"
            },
        )
    }

    /**
     * How far the direct-road pass should actually route.
     *
     * The destination itself for a trip short enough to plan whole. For anything
     * longer, a point on the great circle just past the leg window: the spine
     * exists to choose a cut and draw this leg's camera corridor, and both of
     * those questions are answered inside the first [maxLegMeters] of road.
     * Routing the remaining thousands of kilometres to answer them is what made
     * a cross-country trip cost more than its whole budget before anything that
     * decides a route had run.
     *
     * The probe reaches past the window by [SPINE_PROBE_MARGIN] so the cut has
     * candidates either side of it and the tail check has something to measure.
     * Intermediate stops are kept: they are places the driver asked to be, and
     * dropping one to shorten a search would silently change the trip.
     */
    private fun spineProbe(points: List<GeoPoint>, maxLegMeters: Double?): List<GeoPoint> {
        val limit = maxLegMeters ?: return points
        val straight = straightLength(points)
        // Only where the full spine is the thing that fails.
        //
        // Measured: routing the whole direct road costs about 4 s at 1,000 km
        // and 64 s at 3,600 km, so on anything short of continental it is
        // cheap and *better* — the probe aims along the great circle, and the
        // road out of a town rarely leaves along it, so a probe spine picks its
        // cut from a road the trip would not have taken. On the same 1,165 km
        // benchmark that cost 88 km of extra driving for identical exposure.
        //
        // So the trade is only worth making where the alternative is the plan
        // failing outright, and this threshold is where that starts.
        if (straight <= SPINE_FULL_LIMIT_METERS) return points
        if (straight <= limit * SPINE_PROBE_MARGIN) return points
        val origin = points.first()
        // Aimed along the *remaining* straight line rather than at the final
        // destination, so a trip with early stops probes past those stops rather
        // than off at a tangent to them.
        val toward = points.drop(1).firstOrNull { haversineMeters(origin, it) > limit } ?: points.last()
        val probe = destinationPoint(origin, bearingDegrees(origin, toward), limit * SPINE_PROBE_MARGIN)
        // Any stop inside the probe distance still has to be on the way.
        val kept = points.drop(1).dropLast(1).filter { haversineMeters(origin, it) < limit * SPINE_PROBE_MARGIN }
        return listOf(origin) + kept + probe
    }

    /** Length of the straight chain through [points] — no roads involved. */
    private fun straightLength(points: List<GeoPoint>): Double =
        (1 until points.size).sumOf { haversineMeters(points[it - 1], points[it]) }

    /** Whether any search in this plan hit its ceiling rather than finishing. */
    private fun anyPassRanOut(passes: List<PlanTimings.Timed>): Boolean =
        passes.any { "out of time" in it.label || "over budget" in it.label }

    companion object {
        /** Pad the origin→destination box so an avoidance detour stays covered. */
        /**
         * How far outside the straight origin→destination box to look.
         *
         * This governs which cameras exist as far as the planner is concerned,
         * and it was 3 km — barely wider than the line itself. A camera just
         * off the end of a trip, or beside the alternative road a detour would
         * take, simply was not in the set, so the avoidance was never asked to
         * dodge it and the route came back "planned" but exposed. The whole
         * point of this app is taking a road that is not the direct one, so the
         * area considered has to be much wider than the direct one.
         */
        const val ROUTE_BBOX_MARGIN_METERS = 60_000.0

        /**
         * Half-width of the corridor cameras are taken from.
         *
         * This was 15 km, and narrowing it to that was right at the time: a
         * link was checked against every nogo in turn, so the size of the camera
         * set *was* the cost of routing, and trimming the set was the only lever
         * that moved. `NogoIndex` removed that relationship — the scan is now
         * over the nogos near a link rather than all of them — and with it the
         * reason to be tight. Measured on the same 490 km trip: 15 km draws
         * 2,349 cameras and the passes take 36.1 s, 30 km draws 3,580 and takes
         * 37.5 s, 60 km draws 5,395 and takes 40.7 s. Four seconds for four
         * times the cameras.
         *
         * A widen costs far more than that. It is not a slightly wider set — it
         * is the whole chooser run a second time, out of the same plan budget,
         * which in practice meant the second round timing out and the driver
         * being shown the fastest route alone. Against ~4 s, a widen avoided is
         * worth paying for on every trip, so this now matches
         * [ROUTE_BBOX_MARGIN_METERS]: cameras are considered anywhere a detour
         * could plausibly go.
         *
         * Either way the answer is safe, which is what makes this a pure
         * cost/benefit choice: the fixed-point loop verifies the corridor, so a
         * route that leaves it is never labelled — the spine grows to cover
         * where the routes actually went and everything is planned again.
         */
        const val CAMERA_CORRIDOR_METERS = 60_000.0

        /** How close to the line a camera has to be to be worth drawing as context. */
        const val NEARBY_CAMERA_METERS = 2_500.0

        /** How many times to widen the camera area to cover a detouring route. */
        private const val MAX_REFINEMENT_PASSES = 4

        /**
         * The share of a plan's budget the direct-road pass may spend.
         *
         * It runs over the *whole* trip while every pass after it works on one
         * leg, so on a cross-country route it is the expensive search rather
         * than the cheap one — 64 s of a 71 s plan, measured on a real 3,598 km
         * trip. Left uncapped it can spend everything and leave the passes that
         * actually decide the route with the 1 ms floor, which comes back to the
         * driver as "no route found".
         *
         * A third is deliberately generous to what follows, because running out
         * *here* costs little: the spine falls back to the straight line between
         * the trip's points, and the only consequence is a corridor drawn around
         * a cruder shape. Every other pass failing has no fallback at all.
         */
        private const val SPINE_BUDGET_SHARE = 0.33

        /**
         * How far past the leg window the spine probe reaches, as a multiple of
         * `maxLegMeters`.
         *
         * Enough that the cut has candidates on both sides of the window and the
         * minimum-tail check has road to measure, without paying for road no
         * decision in this call will look at.
         */
        private const val SPINE_PROBE_MARGIN = 1.35

        /**
         * Above this straight-line distance the spine stops short of the
         * destination rather than routing the whole way. See [spineProbe] for
         * why this is a threshold rather than always-on: below it the full
         * spine is both affordable and a better guide to where the cut should
         * go, and above it the full spine is what makes the plan fail.
         */
        private const val SPINE_FULL_LIMIT_METERS = 1_500_000.0

        /**
         * Straight-line distance times this, as a stand-in for road distance
         * when the spine deliberately stopped short of the destination.
         *
         * Roads wander; a bare great-circle figure reads visibly low beside the
         * legs that follow it, and the banner it feeds is the driver's only
         * sense of scale for the trip. A rough overestimate is the honest
         * direction — it can only make the trip look longer than it is.
         */
        private const val ROAD_WANDER_FACTOR = 1.2

        /** One spine point per this much road. See `sampleSpine`. */
        private const val SPINE_SAMPLE_METERS = 5_000.0

        /**
         * How far inside the corridor a route has to stay to count as covered.
         *
         * Comfortably more than the furthest a camera is ever treated as seeing,
         * so "the route stayed this far inside" implies "every camera that could
         * see it was in the set". Erring large costs an occasional extra widen;
         * erring small silently prints a camera count the router never had the
         * chance to act on.
         */
        private const val CORRIDOR_SAFETY_METERS = 3_000.0

        /**
         * Ceiling on the pin-refinement phase.
         *
         * Planning is bounded by the routing passes it takes to *decide* the
         * route, which is a handful. Refinement is not: it costs a pass per
         * candidate pin per option, and a long trip through camera-dense country
         * wants a lot of them — which is how a five-hour route came to take
         * about five minutes to plan.
         *
         * **Raised from 20 s once pins had to guarantee the route**, not merely
         * dodge cameras. Measured on the 615 km benchmark, the phase converges —
         * inserts every pin it wants and prunes the idle ones back out — in
         * about 34 s. At 20 s it was cut off part-way, which showed up in the
         * worst possible way: *more* pins than the converged answer, because the
         * pruning that removes the redundant ones never ran. A truncated phase
         * does not give you a smaller version of the right answer.
         *
         * This only binds on long trips. A 200 km trip settles in about 3 s, so
         * the ceiling costs nothing where it is not needed, and where it is
         * needed is exactly where a car has the most chances to go its own way.
         */
        const val REFINE_BUDGET_MILLIS = 45_000L

        /**
         * The same ceiling for a plan computed while the car is moving.
         *
         * A driver waiting at the kerb will sit through twenty seconds; a car
         * doing 60 mph covers half a mile in that time, and the re-plan is
         * wanted *now* — its whole purpose is to say what to do at a junction
         * that is getting closer while we think. Fewer pins on a route that
         * arrives in time beats a perfectly pinned one that doesn't.
         */
        const val REPLAN_REFINE_BUDGET_MILLIS = 4_000L
    }
}
