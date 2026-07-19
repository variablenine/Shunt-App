package app.shunt.solver.routing

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.geo.BoundingBox
import app.shunt.solver.geo.bearingDegrees
import app.shunt.solver.geo.bearingDifference
import app.shunt.solver.geo.pointToPolyline
import app.shunt.solver.waypoints.WaypointExtractor

data class SolverConfig(
    /** A camera "blocks" a route if the polyline passes within this radius. */
    val bufferRadiusMeters: Double = 40.0,
    /**
     * When true, a direction-tagged camera only blocks if the route's heading
     * at the nearest point falls within [directionArcDegrees] of the camera's
     * facing. Off by default: the tags are crowdsourced, often absent, and
     * many units cover multiple directions.
     */
    val strictDirection: Boolean = false,
    val directionArcDegrees: Double = 120.0,
    /** Rounds of avoid-area exclusion before falling back. */
    val maxExclusionRounds: Int = 8,
    /** Alternative routes requested per backend call, for fallback scoring. */
    val alternatives: Int = 3,
    /** Margin added around a route's bbox when fetching cameras. */
    val cameraFetchMarginMeters: Double = 500.0,
)

/**
 * Implements the routing policy:
 *  1. If a camera-free route exists, return the fastest camera-free route.
 *  2. Otherwise say so explicitly and return the route passing the fewest
 *     distinct cameras, ties broken by travel time.
 *
 * The camera-free search is greedy exclusion: buffer every camera on the
 * current candidate, add the offenders as avoidance areas (capped at the
 * backend's 20-box limit, nearest-to-the-line first), and re-request.
 *
 * Known limitation (see README): greedy exclusion can report infeasibility
 * when a clean route exists, because excluding camera A may push the route
 * onto camera B. The exact alternative — delete camera-adjacent edges from a
 * road graph and run shortest path — is future work.
 */
class RouteSolver(
    private val api: RoutingApi,
    private val cameras: suspend (BoundingBox) -> List<Camera>,
    private val config: SolverConfig = SolverConfig(),
) {
    suspend fun solve(origin: GeoPoint, destination: GeoPoint): SolveResult {
        val initial = runCatching {
            api.routes(origin, destination, alternatives = config.alternatives)
        }.getOrElse { e -> return SolveResult.Failed("routing backend error: ${e.message}") }
        if (initial.isEmpty()) return SolveResult.Failed("no route between origin and destination")

        val fastest = initial.minBy { it.durationSeconds }
        // Every candidate ever seen, with the distinct cameras it passes.
        val evaluated = LinkedHashMap<List<GeoPoint>, Pair<Route, List<Camera>>>()
        suspend fun evaluate(route: Route): List<Camera> =
            evaluated.getOrPut(route.polyline) { route to camerasOn(route) }.second

        initial.forEach { evaluate(it) }

        var current = fastest
        var avoidSet = emptyList<Camera>()
        var exhausted = false
        for (round in 0 until config.maxExclusionRounds) {
            val hits = evaluate(current)
            if (hits.isEmpty()) break

            avoidSet = buildAvoidSet(current, hits, avoidSet)
            val candidates = runCatching {
                api.routes(
                    origin, destination,
                    avoidAreas = avoidSet.map { BoundingBox.around(it.location, config.bufferRadiusMeters) },
                    alternatives = config.alternatives,
                )
            }.getOrElse { emptyList() }
            if (candidates.isEmpty()) { exhausted = true; break }

            candidates.forEach { evaluate(it) }
            current = candidates.minBy { it.durationSeconds }
        }
        // Make sure the final candidate of the last round is scored too.
        evaluate(current)

        val cleanRoutes = evaluated.values.filter { (_, hits) -> hits.isEmpty() }.map { it.first }
        if (cleanRoutes.isNotEmpty()) {
            val best = cleanRoutes.minBy { it.durationSeconds }
            return SolveResult.Clean(
                route = best,
                addedSecondsVsFastest = best.durationSeconds - fastest.durationSeconds,
                waypoints = WaypointExtractor.extract(best.polyline, fastest.polyline),
            )
        }

        // Fallback: minimum exposure across everything we saw — the initial
        // alternatives plus every candidate generated during exclusion.
        val (route, passed) = evaluated.values.minWith(
            compareBy({ it.second.size }, { it.first.durationSeconds })
        )
        return SolveResult.MinimumExposure(
            route = route,
            passedCameras = passed,
            addedSecondsVsFastest = route.durationSeconds - fastest.durationSeconds,
            waypoints = WaypointExtractor.extract(route.polyline, fastest.polyline),
        )
    }

    /**
     * Cameras to exclude next round: offenders on the current line first,
     * nearest to the line first, then carry-overs from previous rounds so the
     * route doesn't bounce back — never more than the backend's 20-box cap,
     * never "the whole dataset".
     */
    private fun buildAvoidSet(current: Route, hits: List<Camera>, previous: List<Camera>): List<Camera> {
        val prioritized = hits.sortedBy { pointToPolyline(it.location, current.polyline).distanceMeters }
        return (prioritized + previous)
            .distinctBy { it.id }
            .take(RoutingApi.MAX_AVOID_AREAS)
    }

    /** Distinct cameras whose buffer the route's polyline clips. */
    private suspend fun camerasOn(route: Route): List<Camera> {
        val bbox = BoundingBox.of(route.polyline).expand(config.cameraFetchMarginMeters)
        return cameras(bbox)
            .distinctBy { it.id }
            .filter { camera -> blocks(camera, route.polyline) }
    }

    private fun blocks(camera: Camera, polyline: List<GeoPoint>): Boolean {
        if (polyline.size < 2) return false
        val projection = pointToPolyline(camera.location, polyline)
        if (projection.distanceMeters > config.bufferRadiusMeters) return false
        if (!config.strictDirection) return true

        val facing = camera.directionDegrees ?: return true // untagged: assume it sees everything
        val heading = bearingDegrees(
            polyline[projection.segmentIndex],
            polyline[projection.segmentIndex + 1],
        )
        return bearingDifference(heading, facing) <= config.directionArcDegrees / 2.0
    }
}
