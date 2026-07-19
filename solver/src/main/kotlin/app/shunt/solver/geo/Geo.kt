package app.shunt.solver.geo

import app.shunt.core.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Degrees latitude per meter is effectively constant; longitude shrinks by cos(lat). */
const val METERS_PER_DEGREE_LAT: Double = 111_320.0

/** An axis-aligned WGS84 bounding box. Does not handle antimeridian wrapping. */
data class BoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    init {
        require(minLat <= maxLat) { "minLat $minLat > maxLat $maxLat" }
        require(minLon <= maxLon) { "minLon $minLon > maxLon $maxLon" }
    }

    fun contains(p: GeoPoint): Boolean =
        p.lat in minLat..maxLat && p.lon in minLon..maxLon

    fun intersects(other: BoundingBox): Boolean =
        minLat <= other.maxLat && maxLat >= other.minLat &&
            minLon <= other.maxLon && maxLon >= other.minLon

    /** Expand every edge outward by [meters]. */
    fun expand(meters: Double): BoundingBox {
        val dLat = meters / METERS_PER_DEGREE_LAT
        val midLat = (minLat + maxLat) / 2
        val dLon = meters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(midLat)).coerceAtLeast(0.01))
        return BoundingBox(
            (minLat - dLat).coerceAtLeast(-90.0),
            (minLon - dLon).coerceAtLeast(-180.0),
            (maxLat + dLat).coerceAtMost(90.0),
            (maxLon + dLon).coerceAtMost(180.0),
        )
    }

    companion object {
        fun of(points: List<GeoPoint>): BoundingBox {
            require(points.isNotEmpty()) { "cannot bound an empty point list" }
            var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
            for (p in points) {
                minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
                minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
            }
            return BoundingBox(minLat, minLon, maxLat, maxLon)
        }

        /** Square box of half-width [meters] centered on [center]. */
        fun around(center: GeoPoint, meters: Double): BoundingBox =
            BoundingBox(center.lat, center.lon, center.lat, center.lon).expand(meters)
    }
}

/** Great-circle distance in meters. */
fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val s = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(s), sqrt(1 - s))
}

/** Initial bearing from [a] to [b] in degrees [0, 360). */
fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.lat); val lat2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/** Smallest absolute difference between two bearings, in [0, 180]. */
fun bearingDifference(a: Double, b: Double): Double {
    val d = abs(a - b) % 360.0
    return if (d > 180.0) 360.0 - d else d
}

/**
 * Distance in meters from point [p] to segment [a]-[b], using a local
 * equirectangular projection. Accurate to well under a meter at the tens-of-
 * meters scale the camera buffers operate on.
 */
fun pointToSegmentMeters(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
    val cosLat = cos(Math.toRadians(p.lat)).coerceAtLeast(0.01)
    val ax = (a.lon - p.lon) * METERS_PER_DEGREE_LAT * cosLat
    val ay = (a.lat - p.lat) * METERS_PER_DEGREE_LAT
    val bx = (b.lon - p.lon) * METERS_PER_DEGREE_LAT * cosLat
    val by = (b.lat - p.lat) * METERS_PER_DEGREE_LAT
    val dx = bx - ax; val dy = by - ay
    val lenSq = dx * dx + dy * dy
    if (lenSq == 0.0) return sqrt(ax * ax + ay * ay)
    val t = ((-ax) * dx + (-ay) * dy / 1.0).let { ((-ax) * dx + (-ay) * dy) / lenSq }.coerceIn(0.0, 1.0)
    val cx = ax + t * dx; val cy = ay + t * dy
    return sqrt(cx * cx + cy * cy)
}

/** Result of projecting a point onto a polyline. */
data class PolylineProjection(val distanceMeters: Double, val segmentIndex: Int)

/** Distance from [p] to the nearest segment of [polyline], plus which segment. */
fun pointToPolyline(p: GeoPoint, polyline: List<GeoPoint>): PolylineProjection {
    require(polyline.size >= 2) { "polyline needs at least 2 points" }
    var best = Double.MAX_VALUE
    var bestIdx = 0
    for (i in 0 until polyline.size - 1) {
        val d = pointToSegmentMeters(p, polyline[i], polyline[i + 1])
        if (d < best) { best = d; bestIdx = i }
    }
    return PolylineProjection(best, bestIdx)
}

/** Floor [value] to the nearest multiple of [step] (handles negatives correctly). */
fun floorTo(value: Double, step: Int): Int = (floor(value / step) * step).toInt()
