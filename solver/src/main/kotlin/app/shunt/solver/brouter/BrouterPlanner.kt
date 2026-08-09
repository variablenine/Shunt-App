package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.geo.BoundingBox
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
    ) : PlanOutcome

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
    private val route: suspend (
        points: List<GeoPoint>,
        cameras: List<CameraVision>,
        headingDegrees: Double?,
    ) -> List<BrouterRoute>,
    private val missingTiles: (BoundingBox) -> List<TileId>,
    private val camerasIn: suspend (BoundingBox) -> List<Camera>,
    private val bboxMarginMeters: Double = ROUTE_BBOX_MARGIN_METERS,
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
    ): PlanOutcome {
        require(points.size >= 2) { "a trip needs at least an origin and a destination" }
        val baseBbox = BoundingBox.of(points).expand(bboxMarginMeters)

        val missing = missingTiles(baseBbox)
        if (missing.isNotEmpty()) return PlanOutcome.NeedsDownload(missing)

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
        val direct = runRoutes(points, emptyList(), headingDegrees)
        routingMillis += nowMillis() - startedAt
        routingPasses += lastPassTimings().map { it.copy(label = "${it.label} (spine)") }
        var spine = direct?.firstOrNull()?.polyline?.takeIf { it.size >= 2 }?.let { sampleSpine(it) }
            // No direct route is not fatal here — fall back to the straight line
            // between the trip's own points, which is what this used before.
            ?: points

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
        var cameras = camerasAlong(spine) ?: return cameraDataUnavailable()
        cameraMillis += nowMillis() - startedAt
        var routes: List<BrouterRoute> = emptyList()
        var covered = false

        for (pass in 0 until MAX_REFINEMENT_PASSES) {
            onProgress(0.4f + 0.12f * pass, if (pass == 0) "Planning routes" else "Widening the camera search")
            startedAt = nowMillis()
            val fresh = runRoutes(points, cameras, headingDegrees)
            routingMillis += nowMillis() - startedAt
            // Label by iteration: a second one means the routes escaped the
            // camera area and the whole graph was searched again.
            routingPasses += lastPassTimings().map { timed ->
                if (pass == 0) timed else timed.copy(label = "${timed.label} (widen ${pass + 1})")
            }
            routes = fresh ?: return PlanOutcome.Failed("Routing failed.")
            if (routes.isEmpty()) return noRoute()

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

        val fastest = routes.first()
        val visions = cameras.map { CameraVision(it.location, it.directionDegrees) }
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
                        pinsTheCarWillFollow(r.polyline, fastest.polyline, visions, index, deadline, carPaths)
                    },
                    polyline = r.polyline,
                ),
                // A camera is "passed" if the route enters its field of view.
                passedCameras = index.seeing(r.polyline).mapNotNull { byLocation[it.location] },
                nearbyCameras = index.within(r.polyline, NEARBY_CAMERA_METERS)
                    .mapNotNull { byLocation[it.location] },
                distanceMeters = r.distanceMeters,
                estimatedSeconds = r.estimatedSeconds,
                exposureMeters = r.exposureMeters,
                addedSecondsVsFastest = r.estimatedSeconds - fastest.estimatedSeconds,
                hardAvoidanceFailed = r.hardAvoidanceFailed,
            )
        }
        stages += PlanTimings.Timed(PlanTimings.STAGE_CAMERAS, cameraMillis)
        stages += PlanTimings.Timed(PlanTimings.STAGE_ROUTING, routingMillis)
        stages += PlanTimings.Timed(PlanTimings.STAGE_PINS, nowMillis() - pinsStartedAt)
        return PlanOutcome.Routes(options, PlanTimings(stages, routingPasses))
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
        val candidates = WaypointExtractor.extract(
            chosen = chosen,
            fastest = fastest,
            avoid = visions,
        )
        return runCatching {
            WaypointRefiner.refine(
                chosen = chosen,
                pins = candidates,
                avoid = visions,
                index = index,
                outOfTime = { nowMillis() >= deadline },
                carRoute = { from, to -> carPathBetween(from, to, carPaths) },
            )
        }.getOrDefault(candidates)
    }

    /**
     * How the car would drive [from] to [to]: fastest, no camera avoidance.
     *
     * Memoised across every option in one plan. A null answer is cached too — a
     * leg the engine can't route stays unroutable, and re-asking costs as much
     * as asking did.
     */
    private suspend fun carPathBetween(
        from: GeoPoint,
        to: GeoPoint,
        carPaths: MutableMap<Pair<GeoPoint, GeoPoint>, List<GeoPoint>?>,
    ): List<GeoPoint>? {
        val key = from to to
        if (carPaths.containsKey(key)) return carPaths[key]
        val path = runCatching { route(listOf(from, to), emptyList(), null) }
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
        val bbox = BoundingBox.of(spine).expand(bboxMarginMeters)
        val all = runCatching { camerasIn(bbox) }.getOrNull() ?: return null
        return all.filter { camera -> nearSpine(camera.location, spine, bboxMarginMeters + SPINE_SAMPLE_METERS) }
    }

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
        val limit = bboxMarginMeters + SPINE_SAMPLE_METERS - CORRIDOR_SAFETY_METERS
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
    ): List<BrouterRoute>? {
        val visions = cameras.map { CameraVision(it.location, it.directionDegrees) }
        return runCatching { route(points, visions, headingDegrees) }.getOrNull()
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

    private fun noRoute(): PlanOutcome {
        val detail = diagnostics()?.takeIf { it.isNotBlank() }?.let { "\n\n[$it]" } ?: ""
        return PlanOutcome.Failed("No route found — the offline map for this area may be incomplete.$detail")
    }

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

        /** How close to the line a camera has to be to be worth drawing as context. */
        const val NEARBY_CAMERA_METERS = 2_500.0

        /** How many times to widen the camera area to cover a detouring route. */
        private const val MAX_REFINEMENT_PASSES = 4

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
         * about five minutes to plan. Twenty seconds is far more than a normal
         * trip needs and far less than a driver will sit through.
         */
        const val REFINE_BUDGET_MILLIS = 20_000L

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
