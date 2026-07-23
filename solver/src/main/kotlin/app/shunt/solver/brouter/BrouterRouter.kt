package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.destinationPoint
import app.shunt.solver.geo.haversineMeters
import btools.router.OsmNodeNamed
import btools.router.OsmNogoPolygon
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import java.io.File

/** Which point on the camera-avoidance spectrum a route represents. */
enum class RouteChoice { FASTEST, BALANCED, FEWEST_CAMERAS }

/**
 * One routing option the user can pick. [distinctCamerasPassed] is the count of
 * cameras whose field of view the route enters; [exposureMeters] is the metres
 * driven within sight of any camera.
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
 * On-device, offline camera-aware routing over BRouter. Each ALPR is a "nogo"
 * shaped by its [CameraVision]: a 180° sector polygon in the direction a camera
 * faces (so routes may pass behind it), or a larger full circle when the facing
 * is unknown. Higher weight = avoid harder; a single shortest-path pass yields
 * the minimum-exposure route with no greedy backtracking.
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
    private val profileName: String = "car-vario",
) {
    /** Why the last [route] found nothing, for diagnostics — null after a success. */
    @Volatile
    var lastFailureDiagnostic: String? = null
        private set

    fun route(origin: GeoPoint, destination: GeoPoint, cameras: List<CameraVision>): List<BrouterRoute> {
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
        cameras: List<CameraVision>,
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
            if (weight > 0.0 && cameras.isNotEmpty()) {
                val nogos = buildNogos(cameras, weight, collector)
                if (nogos.isNotEmpty()) {
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

    /**
     * Nogos matching each camera's field of view: directional cameras get a
     * 180° sector polygon they face; unknown-facing cameras get a full circle.
     */
    private fun buildNogos(
        cameras: List<CameraVision>,
        weight: Double,
        collector: RoutingParamCollector,
    ): List<OsmNodeNamed> {
        val nogos = mutableListOf<OsmNodeNamed>()

        val omni = cameras.filter { it.directionDegrees == null }
        if (omni.isNotEmpty()) {
            val spec = omni.joinToString("|") { c ->
                "${c.location.lon},${c.location.lat},${CameraVision.OMNI_RANGE_M.toInt()},${weight.toInt()}"
            }
            collector.readNogoList(spec)?.let { nogos.addAll(it) }
        }

        for (cam in cameras) {
            val direction = cam.directionDegrees ?: continue
            nogos += sectorPolygon(cam.location, direction, CameraVision.DIRECTIONAL_RANGE_M, weight)
        }
        return nogos
    }

    private fun RawRoute.toResult(choice: RouteChoice, cameras: List<CameraVision>): BrouterRoute =
        BrouterRoute(
            choice = choice,
            polyline = polyline,
            distanceMeters = distanceMeters,
            estimatedSeconds = seconds,
            distinctCamerasPassed = cameras.count { it.seesRoute(polyline) },
            exposureMeters = CameraVision.metersSeen(polyline, cameras).toInt(),
        )

    /** Record the first (fastest-attempt) failure reason and return null. */
    private fun note(reason: String): RawRoute? {
        if (lastFailureDiagnostic == null) lastFailureDiagnostic = reason
        return null
    }

    companion object {
        // Nogo penalty per meter inside a camera's zone. Balanced accepts a
        // camera to save a big detour; fewest avoids hard where a path exists.
        private const val BALANCED_WEIGHT = 500.0
        private const val FEWEST_WEIGHT = 20_000.0

        /** ETA fallback if BRouter timing is unavailable: ~40 km/h town average. */
        private fun estimateSeconds(meters: Int): Int = (meters / (40_000.0 / 3600.0)).toInt()

        /** A closed sector polygon (half-disc) fanning ±90° around [directionDeg]. */
        private fun sectorPolygon(
            apex: GeoPoint,
            directionDeg: Double,
            range: Double,
            weight: Double,
        ): OsmNogoPolygon {
            val poly = OsmNogoPolygon(true)
            poly.addVertex(lonToInt(apex.lon), latToInt(apex.lat))
            val steps = 8
            for (i in 0..steps) {
                val bearing = directionDeg - CameraVision.FOV_HALF_ANGLE +
                    (2 * CameraVision.FOV_HALF_ANGLE) * i / steps
                val edge = destinationPoint(apex, bearing, range)
                poly.addVertex(lonToInt(edge.lon), latToInt(edge.lat))
            }
            poly.nogoWeight = weight
            poly.calcBoundingCircle()
            return poly
        }

        private fun lonToInt(lon: Double): Int = ((lon + 180.0) * 1_000_000.0 + 0.5).toInt()
        private fun latToInt(lat: Double): Int = ((lat + 90.0) * 1_000_000.0 + 0.5).toInt()

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
    }
}
