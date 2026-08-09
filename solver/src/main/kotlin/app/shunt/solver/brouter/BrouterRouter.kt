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
import kotlin.math.cos

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
    /**
     * The engine was asked to treat every camera as impassable and came back
     * with nothing. This deliberately does not claim the cameras are proven
     * unavoidable: an endpoint inside a nogo or an engine failure has the same
     * raw outcome. Only set on the fewest-cameras fallback option.
     */
    val hardAvoidanceFailed: Boolean = false,
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

    /**
     * How long each search over the road graph took, in the most recent [route]
     * call. Temporary diagnostic — see [PlanTimings].
     */
    @Volatile
    var lastPassTimings: List<PlanTimings.Timed> = emptyList()
        private set

    /**
     * Route through [points] — origin, any intermediate stops in order, then the
     * destination — returning up to three options.
     *
     * [headingDegrees] is the compass bearing the vehicle is actually travelling
     * on, when it is moving. Given it, the route has to set off the way the car
     * is already pointing instead of doubling back — a re-plan that answers with
     * a U-turn is worse than useless at 60 mph. Pass null when parked or
     * unknown: a stationary fix's bearing is noise, and pinning the route to it
     * would rule out the road behind for no reason.
     */
    fun route(
        points: List<GeoPoint>,
        cameras: List<CameraVision>,
        headingDegrees: Double? = null,
    ): List<BrouterRoute> {
        require(points.size >= 2) { "a route needs at least an origin and a destination" }
        lastFailureDiagnostic = null
        val timings = mutableListOf<PlanTimings.Timed>()
        // Each of these is a full search over the road graph, which on a
        // cross-state trip is the whole cost of planning. Which one is expensive
        // decides what to do about it, so they are timed apart.
        fun <T> timed(label: String, block: () -> T): T {
            val startedAt = System.nanoTime()
            val result = block()
            timings += PlanTimings.Timed(label, (System.nanoTime() - startedAt) / 1_000_000)
            return result
        }

        val fastest = timed("fastest") { runRoute(points, cameras, Avoidance.None, headingDegrees) }
            ?.toResult(RouteChoice.FASTEST, cameras)
        // With no cameras nearby there is only one sensible route.
        if (cameras.isEmpty()) {
            lastPassTimings = timings
            return listOfNotNull(fastest)
        }

        val balanced = timed("balanced") {
            runRoute(points, cameras, Avoidance.Weighted(BALANCED_WEIGHT), headingDegrees)
        }
            ?.toResult(RouteChoice.BALANCED, cameras)

        // "Fewest cameras" must mean *none* whenever a camera-free path exists at
        // any distance. A weighted nogo can't promise that: BRouter charges
        // (metres inside the zone × weight), so a road clipping the edge of a
        // cone costs little and gets chosen over a long back-road detour — the
        // route then passes a camera that was in fact avoidable. Blocking the
        // zones outright makes the engine find the camera-free path or none.
        val blocked = timed("blocked") { runRoute(points, cameras, Avoidance.Blocked, headingDegrees) }
            ?.toResult(RouteChoice.FEWEST_CAMERAS, cameras)
        val fewest = blocked
            // No camera-free path exists (or an endpoint sits inside a zone,
            // which a hard block rejects outright) — fall back to avoiding as
            // hard as possible so the user still gets the best available, and
            // record that hard avoidance failed without claiming why it failed.
            ?: timed("fewest (fallback)") {
                runRoute(points, cameras, Avoidance.Weighted(FEWEST_WEIGHT), headingDegrees)
            }
                ?.toResult(RouteChoice.FEWEST_CAMERAS, cameras)
                ?.copy(hardAvoidanceFailed = true)
        lastPassTimings = timings

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

    /** How hard this pass should avoid camera zones. */
    internal sealed interface Avoidance {
        /** Ignore cameras entirely — the plain fastest route. */
        data object None : Avoidance

        /** Penalise metres driven inside a zone; a camera can still be accepted. */
        data class Weighted(val weight: Double) : Avoidance

        /**
         * Treat every zone as impassable (BRouter's NaN-weight nogo). Either the
         * route is camera-free or there is no route at all.
         */
        data object Blocked : Avoidance
    }

    private fun runRoute(
        points: List<GeoPoint>,
        cameras: List<CameraVision>,
        avoidance: Avoidance,
        headingDegrees: Double? = null,
    ): RawRoute? {
        return try {
            val rc = RoutingContext()
            // BRouter applies this by placing an imaginary previous position
            // 1 km back along the bearing, so its ordinary turn costs make
            // setting off backwards expensive. forceUseStartDirection is what
            // makes it apply to a full route and not only a partial recalc.
            headingDegrees?.let {
                rc.startDirection = normalizedBearing(it)
                rc.forceUseStartDirection = true
            }
            // Absolute .brf path => BRouter's null-profileBaseDir branch: no global
            // system property, and lookups.dat is read from the same directory.
            rc.localFunction = File(profileDir, "$profileName.brf").absolutePath
            val collector = RoutingParamCollector()
            // BRouter routes through the whole chain in one pass, so
            // intermediate stops are honoured natively.
            val waypoints = collector.getWayPointList(
                points.joinToString("|") { "${it.lon},${it.lat}" },
            )
            if (avoidance != Avoidance.None && cameras.isNotEmpty()) {
                // NaN is BRouter's "impassable"; a finite value is a per-metre penalty.
                val weight = (avoidance as? Avoidance.Weighted)?.weight ?: Double.NaN
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
    internal fun buildNogos(
        cameras: List<CameraVision>,
        weight: Double,
        collector: RoutingParamCollector,
    ): List<OsmNodeNamed> {
        val nogos = mutableListOf<OsmNodeNamed>()

        // NaN must reach BRouter verbatim — it means "impassable". Formatting it
        // as an Int would silently become 0, i.e. a nogo with no effect at all.
        val weightSpec = if (weight.isNaN()) "NaN" else weight.toInt().toString()

        val omni = cameras.filter { it.directionDegrees == null }
        if (omni.isNotEmpty()) {
            val radius = (CameraVision.OMNI_RANGE_M + NOGO_MARGIN_METERS).toInt()
            val spec = omni.joinToString("|") { c ->
                "${c.location.lon},${c.location.lat},$radius,$weightSpec"
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

        /**
         * Blocked zones are grown by this much so they strictly contain the
         * footprint [CameraVision] counts. Erring outward costs a marginally
         * longer detour; erring inward silently prints "camera-free" over a
         * route that drives past a camera, so the direction is not a toss-up.
         */
        internal const val NOGO_MARGIN_METERS = 15.0

        /** Widen the blocked fan past the field of view, for the same reason. */
        private const val NOGO_ANGLE_MARGIN_DEGREES = 5.0

        /** Chords per fan; more means a tighter fit around the true arc. */
        private const val SECTOR_STEPS = 12

        /** ETA fallback if BRouter timing is unavailable: ~40 km/h town average. */
        private fun estimateSeconds(meters: Int): Int = (meters / (40_000.0 / 3600.0)).toInt()

        /**
         * A closed sector polygon covering a camera's field of view, built to
         * **contain** it rather than approximate it.
         *
         * This is load-bearing. `Avoidance.Blocked` promises that a route it
         * returns is camera-free, and that promise is only as good as the
         * agreement between the shape BRouter blocks and the shape
         * [CameraVision.sees] counts. An inscribed polygon is *smaller* than the
         * true sector, so a road clipping the arc is neither blocked nor
         * unseen — it comes back labelled "fewest cameras" while passing one.
         *
         * So every approximation errs outward: the arc radius is scaled to
         * circumscribe rather than inscribe, the fan is widened past the field
         * of view (which also covers the small all-round disc [CameraVision]
         * sees right at the lens), and a flat margin is added on top.
         */
        internal fun sectorPolygon(
            apex: GeoPoint,
            directionDeg: Double,
            range: Double,
            weight: Double,
        ): OsmNogoPolygon {
            val poly = OsmNogoPolygon(true)
            // Start the fan a little *behind* the lens rather than at it. A
            // camera sees all round at point-blank range (CameraVision.sees
            // ignores bearing within a couple of metres), which a strictly
            // forward fan cannot contain; backing the apex off covers that
            // pocket and only ever grows the zone.
            val back = destinationPoint(apex, directionDeg + 180.0, NOGO_MARGIN_METERS)
            poly.addVertex(lonToInt(back.lon), latToInt(back.lat))
            val halfAngle = CameraVision.FOV_HALF_ANGLE + NOGO_ANGLE_MARGIN_DEGREES
            // Push the vertices out so the chords sit outside the true arc.
            val outer = (range + NOGO_MARGIN_METERS) /
                cos(Math.toRadians(halfAngle / SECTOR_STEPS))
            for (i in 0..SECTOR_STEPS) {
                val bearing = directionDeg - halfAngle + (2 * halfAngle) * i / SECTOR_STEPS
                val edge = destinationPoint(apex, bearing, outer)
                poly.addVertex(lonToInt(edge.lon), latToInt(edge.lat))
            }
            poly.nogoWeight = weight
            poly.calcBoundingCircle()
            return poly
        }

        /** A compass bearing in 0..359, whatever the caller's sign convention. */
        internal fun normalizedBearing(degrees: Double): Int =
            (((degrees % 360.0) + 360.0) % 360.0).toInt()

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
