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

    /** Whether any camera at all sees [polyline] — stops at the first one. */
    fun anySees(polyline: List<GeoPoint>): Boolean {
        if (cameras.isEmpty() || polyline.size < 2) return false
        return forEachSample(polyline) { p ->
            index.near(p, maxRange).any { it.sees(p) }
        }
    }

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

    private companion object {
        /** Matches [CameraVision]'s own sampling, so the answers agree. */
        const val SAMPLE_METERS = 10.0
    }
}
