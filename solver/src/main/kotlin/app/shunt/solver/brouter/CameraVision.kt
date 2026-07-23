package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.bearingDegrees
import app.shunt.solver.geo.bearingDifference
import app.shunt.solver.geo.haversineMeters

/**
 * A camera's detection footprint, used for both counting and avoidance.
 *
 * A camera with a **known facing** watches a 180° field of view (±90° from its
 * direction) out to [DIRECTIONAL_RANGE_M] — a route passing *behind* it is not
 * seen. A camera with **unknown facing** is treated as watching every direction
 * ([OMNI_RANGE_M], deliberately larger, to keep more distance since we can't
 * tell where it points).
 */
data class CameraVision(val location: GeoPoint, val directionDegrees: Double?) {

    val range: Double get() = if (directionDegrees == null) OMNI_RANGE_M else DIRECTIONAL_RANGE_M

    /** True if this camera can see point [p]. */
    fun sees(p: GeoPoint): Boolean {
        val distance = haversineMeters(location, p)
        if (distance > range) return false
        val dir = directionDegrees ?: return true // omnidirectional
        if (distance < 2.0) return true // essentially on top of the camera
        return bearingDifference(bearingDegrees(location, p), dir) <= FOV_HALF_ANGLE
    }

    /** True if this camera sees any point along [polyline] (sampled every ~10 m). */
    fun seesRoute(polyline: List<GeoPoint>): Boolean {
        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]
            val steps = (haversineMeters(a, b) / SAMPLE_METERS).toInt().coerceAtLeast(1)
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                if (sees(GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))) return true
            }
        }
        return false
    }

    companion object {
        /** Half of the assumed field of view — 90° each side = 180° total. */
        const val FOV_HALF_ANGLE = 90.0

        /** How far a camera with a known facing reads plates, in meters. */
        const val DIRECTIONAL_RANGE_M = 120.0

        /** Range for unknown-facing cameras: larger, and all-around. */
        const val OMNI_RANGE_M = 150.0

        private const val SAMPLE_METERS = 10.0

        /** Meters of [polyline] within sight of any camera in [visions]. */
        fun metersSeen(polyline: List<GeoPoint>, visions: List<CameraVision>): Double {
            if (visions.isEmpty() || polyline.size < 2) return 0.0
            var seen = 0.0
            for (i in 0 until polyline.size - 1) {
                val a = polyline[i]
                val b = polyline[i + 1]
                val mid = GeoPoint((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
                if (visions.any { it.sees(a) || it.sees(mid) || it.sees(b) }) seen += haversineMeters(a, b)
            }
            return seen
        }
    }
}
