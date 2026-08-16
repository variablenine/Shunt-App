package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.PointIndex
import app.shunt.solver.geo.haversineMeters

/**
 * Answers "which of these cameras does this route pass?" in one sweep of the
 * route rather than one sweep per camera.
 *
 * [CameraVision.seesRoute] walks the whole polyline, so asking it once per
 * camera is cameras × points. On a trip across a state that is a few hundred
 * cameras against forty thousand points, several times over for the different
 * route options — tens of millions of distance calculations, on the main
 * thread, which is exactly what made long routes hang.
 *
 * Here the route is walked once and each sampled point asks a grid which
 * cameras are close enough to be worth testing properly. Same answers, same
 * [CameraVision.sees] test doing the deciding; only the search order changes.
 */
class CameraIndex(private val cameras: List<CameraVision>) {

    private val index = PointIndex(cameras) { it.location }

    /** The widest range any camera here reaches, so a query never looks too narrowly. */
    private val maxRange = cameras.maxOfOrNull { it.range } ?: 0.0

    /** Nothing to test against — every question below has a trivial answer. */
    val isEmpty: Boolean get() = cameras.isEmpty()

    /** Every camera whose field of view [polyline] enters. */
    fun seeing(polyline: List<GeoPoint>): List<CameraVision> {
        if (cameras.isEmpty() || polyline.size < 2) return emptyList()
        val hits = LinkedHashSet<CameraVision>()
        forEachSample(polyline) { p ->
            for (camera in index.near(p, maxRange)) {
                if (camera !in hits && camera.sees(p)) hits += camera
            }
            // Nothing to short-circuit on: every camera must get its chance.
            false
        }
        return cameras.filter { it in hits }
    }

    /**
     * Every camera that sees this single point.
     *
     * Used for the trip's own endpoints. A camera watching where the driver is
     * going cannot be routed around — arriving is what triggers it — so a hard
     * block including it would be refused outright by BRouter, and these are the
     * ones dropped from that one pass. Counting them is also what lets the
     * result sheet distinguish "one camera watches your destination" from a
     * route that failed to avoid anything. See
     * [BrouterRouter.withoutZonesHolding].
     */
    fun seeing(p: GeoPoint): List<CameraVision> =
        if (cameras.isEmpty()) emptyList() else index.near(p, maxRange).filter { it.sees(p) }

    /** Whether any camera sees this single point. */
    fun anySeeing(p: GeoPoint): Boolean =
        cameras.isNotEmpty() && index.near(p, maxRange).any { it.sees(p) }

    /** Whether any camera at all sees [polyline] — stops at the first one. */
    fun anySees(polyline: List<GeoPoint>): Boolean {
        if (cameras.isEmpty() || polyline.size < 2) return false
        return forEachSample(polyline) { p ->
            index.near(p, maxRange).any { it.sees(p) }
        }
    }

    /**
     * How many cameras sit within [meters] of one point.
     *
     * A density reading, used to decide how tightly a stretch of route wants
     * pinning: cameras cluster where junctions do, and a stretch with a dozen of
     * them within a mile is a city grid where the car has a turn it could take
     * every block. See [app.shunt.solver.waypoints.WaypointExtractor.spaceOut].
     */
    fun countWithin(p: GeoPoint, meters: Double): Int =
        if (cameras.isEmpty()) 0 else index.near(p, meters).count { haversineMeters(p, it.location) <= meters }

    /** Every camera within [meters] of the line, seen or not — the map's context layer. */
    fun within(polyline: List<GeoPoint>, meters: Double): List<CameraVision> {
        if (cameras.isEmpty() || polyline.size < 2) return emptyList()
        val hits = LinkedHashSet<CameraVision>()
        forEachSample(polyline) { p ->
            for (camera in index.near(p, meters)) {
                if (camera !in hits && haversineMeters(p, camera.location) <= meters) hits += camera
            }
            false
        }
        return cameras.filter { it in hits }
    }

    /**
     * For every camera that comes within [meters] of [polyline], how far along
     * the line it is at its closest.
     *
     * The along-distance is the point of it. "Which cameras are near the route"
     * is already answered by [within]; this says *where*, which is what lets a
     * pin be placed either side of the squeeze rather than somewhere on the
     * route that happens to be convenient.
     *
     * Sampled at [stepMeters] rather than the usual ten, because the answer
     * feeds a bracket hundreds of metres wide and paying for decimetre
     * precision on a cross-state route would be waste.
     */
    fun closestApproachAlong(
        polyline: List<GeoPoint>,
        meters: Double,
        stepMeters: Double = SAMPLE_METERS,
    ): Map<CameraVision, Double> {
        if (cameras.isEmpty() || polyline.size < 2) return emptyMap()
        val nearest = HashMap<CameraVision, Double>()
        val alongOfNearest = HashMap<CameraVision, Double>()
        var along = 0.0
        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]
            val span = haversineMeters(a, b)
            val steps = (span / stepMeters).toInt().coerceAtLeast(1)
            for (s in 0 until steps) {
                val t = s.toDouble() / steps
                val p = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                val here = along + span * t
                for (camera in index.near(p, meters)) {
                    val d = haversineMeters(p, camera.location)
                    if (d <= meters && d < (nearest[camera] ?: Double.MAX_VALUE)) {
                        nearest[camera] = d
                        alongOfNearest[camera] = here
                    }
                }
            }
            along += span
        }
        return alongOfNearest
    }

    /**
     * Walk [polyline] at [SAMPLE_METERS], calling [visit] at each sample.
     * Returns true as soon as [visit] does, which is what lets [anySees] stop
     * early on a long route.
     */
    private inline fun forEachSample(polyline: List<GeoPoint>, visit: (GeoPoint) -> Boolean): Boolean {
        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]
            val steps = (haversineMeters(a, b) / SAMPLE_METERS).toInt().coerceAtLeast(1)
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val p = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                if (visit(p)) return true
            }
        }
        return false
    }

    internal companion object {
        /** Matches [CameraVision]'s own sampling, so the answers agree. */
        const val SAMPLE_METERS = 10.0
    }
}
