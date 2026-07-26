package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.geo.BoundingBox
import app.shunt.solver.waypoints.WaypointExtractor

/** A chooseable route: BRouter's geometry plus the pins + cameras Shunt needs. */
data class PlannedRoute(
    val choice: RouteChoice,
    val polyline: List<GeoPoint>,
    /** Intermediate pins that hold the vehicle on this path (excludes destination). */
    val waypoints: List<GeoPoint>,
    /** The actual camera records this route passes within standoff of. */
    val passedCameras: List<Camera>,
    val distanceMeters: Int,
    val estimatedSeconds: Int,
    val exposureMeters: Int,
    /** Extra travel time vs. the fastest option (0 for the fastest). */
    val addedSecondsVsFastest: Int,
    /**
     * Every camera was treated as impassable and no route came back, so the
     * cameras this option passes cannot be routed around at any distance. The
     * distinction is worth showing: "unavoidable" is a fact about the roads,
     * not the avoidance quietly failing.
     */
    val noCameraFreeRouteExists: Boolean = false,
) {
    val camerasPassed: Int get() = passedCameras.size
}

/** Outcome of planning a trip with the native BRouter engine. */
sealed interface PlanOutcome {
    /** One to three distinct options, fastest first, for the user to choose. */
    data class Routes(val options: List<PlannedRoute>) : PlanOutcome

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
    private val route: suspend (points: List<GeoPoint>, cameras: List<CameraVision>) -> List<BrouterRoute>,
    private val missingTiles: (BoundingBox) -> List<TileId>,
    private val camerasIn: suspend (BoundingBox) -> List<Camera>,
    private val bboxMarginMeters: Double = ROUTE_BBOX_MARGIN_METERS,
    /** Optional on-disk/engine state summary, appended to a no-route failure. */
    private val diagnostics: () -> String? = { null },
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
    ): PlanOutcome = plan(listOf(origin, destination), onProgress)

    /**
     * Plan through [points]: origin, any intermediate stops in order, then the
     * destination. Stops are honoured by the routing engine directly, and are
     * always pinned for the vehicle — they are places the driver actually wants
     * to be, not shaping hints.
     */
    suspend fun plan(
        points: List<GeoPoint>,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): PlanOutcome {
        require(points.size >= 2) { "a trip needs at least an origin and a destination" }
        val baseBbox = BoundingBox.of(points).expand(bboxMarginMeters)

        val missing = missingTiles(baseBbox)
        if (missing.isNotEmpty()) return PlanOutcome.NeedsDownload(missing)

        var cameraBbox = baseBbox
        onProgress(0.1f, "Finding cameras nearby")
        // Camera data is safety-critical: a failure here must NEVER be treated as
        // "no cameras," which would label every route camera-free.
        var cameras = fetchCameras(cameraBbox) ?: return cameraDataUnavailable()
        onProgress(0.3f, "Planning routes")
        var routes = runRoutes(points, cameras)
            ?: return PlanOutcome.Failed("Routing failed.")
        if (routes.isEmpty()) return noRoute()

        // The avoidance options can detour far outside the origin→destination
        // box, into areas we never fetched cameras for — so a long "fewest
        // cameras" route would drive through (and mislabel as camera-free) any
        // camera along the detour. Widen the camera set to cover the actual
        // routes and re-plan, iterating until the routes no longer escape the
        // area we've looked at (or we hit the cap).
        var passes = 0
        while (passes++ < MAX_REFINEMENT_PASSES) {
            val routeBbox = routeBbox(routes)
            if (cameraBbox.contains(routeBbox)) break
            onProgress(0.3f + 0.25f * passes, "Checking cameras along the detour")
            cameraBbox = cameraBbox.union(routeBbox)
            val widened = fetchCameras(cameraBbox) ?: return cameraDataUnavailable()
            cameras = widened
            val replanned = runRoutes(points, cameras) ?: break
            if (replanned.isEmpty()) break
            routes = replanned
        }

        // The counting set must PROVABLY cover the routes being labeled. The
        // loop above can exit (on the pass cap) having just re-routed, leaving
        // `cameras` narrower than the final routes — counting against it would
        // silently miss cameras and print "camera-free" over a route that drives
        // straight through one. Verify coverage and top up before labeling; if
        // we can't, refuse to label rather than under-report.
        onProgress(0.85f, "Checking the final route for cameras")
        val finalBbox = routeBbox(routes)
        if (!cameraBbox.contains(finalBbox)) {
            cameras = fetchCameras(cameraBbox.union(finalBbox)) ?: return cameraDataUnavailable()
        }

        val fastest = routes.first()
        val options = routes.map { r ->
            PlannedRoute(
                choice = r.choice,
                polyline = r.polyline,
                // Camera-aware: the vehicle routes itself between waypoints, so
                // they must also stop it cutting back through what we avoided.
                waypoints = withStops(
                    stops = points.drop(1).dropLast(1),
                    shaping = WaypointExtractor.extract(
                        chosen = r.polyline,
                        fastest = fastest.polyline,
                        avoid = cameras.map { CameraVision(it.location, it.directionDegrees) },
                    ),
                    polyline = r.polyline,
                ),
                // A camera is "passed" if the route enters its field of view.
                passedCameras = cameras.filter {
                    CameraVision(it.location, it.directionDegrees).seesRoute(r.polyline)
                },
                distanceMeters = r.distanceMeters,
                estimatedSeconds = r.estimatedSeconds,
                exposureMeters = r.exposureMeters,
                addedSecondsVsFastest = r.estimatedSeconds - fastest.estimatedSeconds,
                noCameraFreeRouteExists = r.noCameraFreeRouteExists,
            )
        }
        return PlanOutcome.Routes(options)
    }

    /** The area the given routes actually cover, padded by the standard margin. */
    private fun routeBbox(routes: List<BrouterRoute>): BoundingBox =
        BoundingBox.of(routes.flatMap { it.polyline }).expand(bboxMarginMeters)

    /** Cameras in [bbox], or null if the lookup failed (never an empty stand-in). */
    private suspend fun fetchCameras(bbox: BoundingBox): List<Camera>? =
        runCatching { camerasIn(bbox) }.getOrNull()

    private fun cameraDataUnavailable(): PlanOutcome = PlanOutcome.Failed(
        "Couldn't load camera data for this area, so Shunt can't tell you which " +
            "cameras a route passes. Check your connection and try again.",
    )

    /** Route with the given cameras as field-of-view nogos; null if the engine threw. */
    private suspend fun runRoutes(
        points: List<GeoPoint>,
        cameras: List<Camera>,
    ): List<BrouterRoute>? {
        val visions = cameras.map { CameraVision(it.location, it.directionDegrees) }
        return runCatching { route(points, visions) }.getOrNull()
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
        const val ROUTE_BBOX_MARGIN_METERS = 3_000.0

        /** How many times to widen the camera area to cover a detouring route. */
        private const val MAX_REFINEMENT_PASSES = 2
    }
}
