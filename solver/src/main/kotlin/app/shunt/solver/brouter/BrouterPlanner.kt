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
    private val route: suspend (origin: GeoPoint, destination: GeoPoint, cameras: List<GeoPoint>) -> List<BrouterRoute>,
    private val missingTiles: (BoundingBox) -> List<TileId>,
    private val camerasIn: suspend (BoundingBox) -> List<Camera>,
    private val standoffMeters: Double = BrouterRouter.DEFAULT_STANDOFF_METERS,
    private val bboxMarginMeters: Double = ROUTE_BBOX_MARGIN_METERS,
) {
    suspend fun plan(origin: GeoPoint, destination: GeoPoint): PlanOutcome {
        val bbox = BoundingBox.of(listOf(origin, destination)).expand(bboxMarginMeters)

        val missing = missingTiles(bbox)
        if (missing.isNotEmpty()) return PlanOutcome.NeedsDownload(missing)

        val cameras = runCatching { camerasIn(bbox) }.getOrDefault(emptyList())
        val routes = runCatching { route(origin, destination, cameras.map { it.location }) }
            .getOrElse { e -> return PlanOutcome.Failed("Routing failed: ${e.message}") }
        if (routes.isEmpty()) {
            return PlanOutcome.Failed("No route found — the offline map for this area may be incomplete.")
        }

        val fastest = routes.first()
        val options = routes.map { r ->
            PlannedRoute(
                choice = r.choice,
                polyline = r.polyline,
                waypoints = WaypointExtractor.extract(r.polyline, fastest.polyline),
                passedCameras = cameras.filter {
                    BrouterRouter.minDistanceToLine(it.location, r.polyline) < standoffMeters
                },
                distanceMeters = r.distanceMeters,
                estimatedSeconds = r.estimatedSeconds,
                exposureMeters = r.exposureMeters,
                addedSecondsVsFastest = r.estimatedSeconds - fastest.estimatedSeconds,
            )
        }
        return PlanOutcome.Routes(options)
    }

    companion object {
        /** Pad the origin→destination box so an avoidance detour stays covered. */
        const val ROUTE_BBOX_MARGIN_METERS = 3_000.0
    }
}
