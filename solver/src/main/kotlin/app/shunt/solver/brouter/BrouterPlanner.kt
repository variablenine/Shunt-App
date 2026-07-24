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
    private val route: suspend (origin: GeoPoint, destination: GeoPoint, cameras: List<CameraVision>) -> List<BrouterRoute>,
    private val missingTiles: (BoundingBox) -> List<TileId>,
    private val camerasIn: suspend (BoundingBox) -> List<Camera>,
    private val bboxMarginMeters: Double = ROUTE_BBOX_MARGIN_METERS,
    /** Optional on-disk/engine state summary, appended to a no-route failure. */
    private val diagnostics: () -> String? = { null },
) {
    suspend fun plan(origin: GeoPoint, destination: GeoPoint): PlanOutcome {
        val baseBbox = BoundingBox.of(listOf(origin, destination)).expand(bboxMarginMeters)

        val missing = missingTiles(baseBbox)
        if (missing.isNotEmpty()) return PlanOutcome.NeedsDownload(missing)

        var cameraBbox = baseBbox
        var cameras = runCatching { camerasIn(cameraBbox) }.getOrDefault(emptyList())
        var routes = runRoutes(origin, destination, cameras)
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
            val routeBbox = BoundingBox.of(routes.flatMap { it.polyline }).expand(bboxMarginMeters)
            if (cameraBbox.contains(routeBbox)) break
            cameraBbox = cameraBbox.union(routeBbox)
            val widened = runCatching { camerasIn(cameraBbox) }.getOrDefault(cameras)
            if (widened.size <= cameras.size) break // nothing new to avoid
            cameras = widened
            val replanned = runRoutes(origin, destination, cameras) ?: break
            if (replanned.isEmpty()) break
            routes = replanned
        }

        val fastest = routes.first()
        val options = routes.map { r ->
            PlannedRoute(
                choice = r.choice,
                polyline = r.polyline,
                waypoints = WaypointExtractor.extract(r.polyline, fastest.polyline),
                // A camera is "passed" if the route enters its field of view.
                passedCameras = cameras.filter {
                    CameraVision(it.location, it.directionDegrees).seesRoute(r.polyline)
                },
                distanceMeters = r.distanceMeters,
                estimatedSeconds = r.estimatedSeconds,
                exposureMeters = r.exposureMeters,
                addedSecondsVsFastest = r.estimatedSeconds - fastest.estimatedSeconds,
            )
        }
        return PlanOutcome.Routes(options)
    }

    /** Route with the given cameras as field-of-view nogos; null if the engine threw. */
    private suspend fun runRoutes(
        origin: GeoPoint,
        destination: GeoPoint,
        cameras: List<Camera>,
    ): List<BrouterRoute>? {
        val visions = cameras.map { CameraVision(it.location, it.directionDegrees) }
        return runCatching { route(origin, destination, visions) }.getOrNull()
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
