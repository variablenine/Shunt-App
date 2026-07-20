package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.haversineMeters
import app.shunt.solver.geo.pointToSegmentMeters
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import java.io.File

/** Which point on the camera-avoidance spectrum a route represents. */
enum class RouteChoice { FASTEST, BALANCED, FEWEST_CAMERAS }

/**
 * One routing option the user can pick. [exposureMeters] is the distance driven
 * within [BrouterRouter.standoffMeters] of any camera — the metric BRouter
 * actually minimises; [distinctCamerasPassed] is the human-facing count.
 */
data class BrouterRoute(
    val choice: RouteChoice,
    val polyline: List<GeoPoint>,
    val distanceMeters: Int,
    val estimatedSeconds: Int,
    val distinctCamerasPassed: Int,
    val exposureMeters: Int,
)

/**
 * On-device, offline camera-aware routing over BRouter. Each ALPR is a weighted
 * "nogo" circle of radius [standoffMeters] so routes keep real distance from
 * cameras — not just clip the edge of a tiny box the way the HERE greedy avoider
 * did. Higher weight = avoid harder; a single shortest-path pass yields the
 * minimum-exposure route with no greedy backtracking.
 *
 * [route] returns up to three distinct options — fastest, balanced, and
 * fewest-cameras — for the user to choose between.
 *
 * [segmentDir] holds the `.rd5` tiles; [profileDir] holds `car-vario.brf` and
 * `lookups.dat` (see [BrouterAssets]). Routing is blocking and CPU-bound; call
 * it off the main thread.
 */
class BrouterRouter(
    private val segmentDir: File,
    private val profileDir: File,
    val standoffMeters: Double = DEFAULT_STANDOFF_METERS,
    private val profileName: String = "car-vario",
) {
    /** Why the last [route] found nothing, for diagnostics — null after a success. */
    @Volatile
    var lastFailureDiagnostic: String? = null
        private set

    fun route(origin: GeoPoint, destination: GeoPoint, cameras: List<GeoPoint>): List<BrouterRoute> {
        lastFailureDiagnostic = null
        val fastest = runRoute(origin, destination, cameras, weight = 0.0)
            ?.toResult(RouteChoice.FASTEST, cameras)
        // With no cameras nearby there is only one sensible route.
        if (cameras.isEmpty()) return listOfNotNull(fastest)

        val balanced = runRoute(origin, destination, cameras, weight = BALANCED_WEIGHT)
            ?.toResult(RouteChoice.BALANCED, cameras)
        val fewest = runRoute(origin, destination, cameras, weight = FEWEST_WEIGHT)
            ?.toResult(RouteChoice.FEWEST_CAMERAS, cameras)

        // Fastest first, then the avoidance options — but only ones that are
        // genuinely a different road, each kept under its own truthful label
        // (so a 0-camera detour reads "fewest cameras", not "balanced").
        val result = mutableListOf<BrouterRoute>()
        fastest?.let { result += it }
        if (balanced != null &&
            result.none { sameRoute(it.polyline, balanced.polyline) } &&
            (fewest == null || !sameRoute(balanced.polyline, fewest.polyline))
        ) {
            result += balanced
        }
        if (fewest != null && result.none { sameRoute(it.polyline, fewest.polyline) }) {
            result += fewest
        }
        return result.ifEmpty { listOfNotNull(fastest) }
    }

    private data class RawRoute(val polyline: List<GeoPoint>, val distanceMeters: Int, val seconds: Int)

    private fun runRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        cameras: List<GeoPoint>,
        weight: Double,
    ): RawRoute? {
        return try {
            val rc = RoutingContext()
            // Absolute .brf path => BRouter's null-profileBaseDir branch: no global
            // system property, and lookups.dat is read from the same directory.
            rc.localFunction = File(profileDir, "$profileName.brf").absolutePath
            val collector = RoutingParamCollector()
            val waypoints = collector.getWayPointList(
                "${origin.lon},${origin.lat}|${destination.lon},${destination.lat}",
            )
            if (weight > 0.0) {
                val spec = cameras.joinToString("|") { c ->
                    "${c.lon},${c.lat},${standoffMeters.toInt()},${weight.toInt()}"
                }
                collector.readNogoList(spec)?.let { nogos ->
                    RoutingContext.prepareNogoPoints(nogos)
                    rc.nogopoints = nogos
                }
            }
            val engine = RoutingEngine(null, null, segmentDir, waypoints, rc, 0)
            engine.quite = true // suppress BRouter's GPX-to-stdout dump
            engine.doRun(0)
            if (engine.errorMessage != null) return note("brouter: ${engine.errorMessage}")
            val track = engine.foundTrack ?: return note("brouter: no track returned")
            val line = track.nodes.map { node ->
                GeoPoint(
                    lat = (node.getILat() - 90_000_000) / 1_000_000.0,
                    lon = (node.getILon() - 180_000_000) / 1_000_000.0,
                )
            }
            if (line.size < 2) return note("brouter: track < 2 points")
            val seconds = track.getTotalSeconds().takeIf { it > 0 } ?: estimateSeconds(track.distance)
            RawRoute(line, track.distance, seconds)
        } catch (e: Throwable) {
            note("exception: ${e.message ?: e.toString()}")
        }
    }

    /** Record the first (fastest-attempt) failure reason and return null. */
    private fun note(reason: String): RawRoute? {
        if (lastFailureDiagnostic == null) lastFailureDiagnostic = reason
        return null
    }

    private fun RawRoute.toResult(choice: RouteChoice, cameras: List<GeoPoint>): BrouterRoute =
        BrouterRoute(
            choice = choice,
            polyline = polyline,
            distanceMeters = distanceMeters,
            estimatedSeconds = seconds,
            distinctCamerasPassed = cameras.count { minDistanceToLine(it, polyline) < standoffMeters },
            exposureMeters = exposureMeters(polyline, cameras, standoffMeters).toInt(),
        )

    companion object {
        /** ALPRs read plates from ~75 m; keep real distance, not a 40 m box. */
        const val DEFAULT_STANDOFF_METERS = 75.0

        // Nogo penalty per meter inside a camera circle. Balanced accepts a
        // camera to save a big detour; fewest avoids hard where a path exists.
        private const val BALANCED_WEIGHT = 500.0
        private const val FEWEST_WEIGHT = 20_000.0

        /** ETA fallback if BRouter timing is unavailable: ~40 km/h town average. */
        private fun estimateSeconds(meters: Int): Int = (meters / (40_000.0 / 3600.0)).toInt()

        /** Two routes are "the same" if their endpoints and length line up closely. */
        private fun sameRoute(a: List<GeoPoint>, b: List<GeoPoint>): Boolean {
            if (a.isEmpty() || b.isEmpty()) return a.size == b.size
            fun len(p: List<GeoPoint>): Double {
                var d = 0.0; for (i in 0 until p.size - 1) d += haversineMeters(p[i], p[i + 1]); return d
            }
            return haversineMeters(a.first(), b.first()) < 20 &&
                haversineMeters(a.last(), b.last()) < 20 &&
                kotlin.math.abs(len(a) - len(b)) < 50
        }

        internal fun minDistanceToLine(p: GeoPoint, line: List<GeoPoint>): Double {
            if (line.size < 2) return Double.MAX_VALUE
            var best = Double.MAX_VALUE
            for (i in 0 until line.size - 1) {
                best = minOf(best, pointToSegmentMeters(p, line[i], line[i + 1]))
            }
            return best
        }

        /** Total meters of [line] that lie within [radius] of any camera. */
        internal fun exposureMeters(line: List<GeoPoint>, cameras: List<GeoPoint>, radius: Double): Double {
            if (cameras.isEmpty() || line.size < 2) return 0.0
            var exposed = 0.0
            for (i in 0 until line.size - 1) {
                val a = line[i]; val b = line[i + 1]
                if (pointToSegmentNearCamera(a, b, cameras, radius)) exposed += haversineMeters(a, b)
            }
            return exposed
        }

        private fun pointToSegmentNearCamera(
            a: GeoPoint,
            b: GeoPoint,
            cameras: List<GeoPoint>,
            radius: Double,
        ): Boolean = cameras.any { pointToSegmentMeters(it, a, b) < radius }
    }
}
