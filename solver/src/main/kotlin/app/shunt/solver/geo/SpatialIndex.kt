package app.shunt.solver.geo

import app.shunt.core.GeoPoint
import kotlin.math.cos
import kotlin.math.floor

/**
 * Cheap uniform grid for "what is near this point" questions.
 *
 * The naive versions of these questions are quadratic, and this app asks them
 * over routes that are tens of thousands of points long against camera sets in
 * the hundreds. Comparing every point to every other is fine for a trip across
 * town and takes minutes for a trip across a state — which is what it did.
 *
 * Cells are sized in metres and keyed on integer lat/lon, so a lookup touches
 * only the handful of cells that could possibly hold an answer.
 */
internal class Grid(cellMeters: Double, atLat: Double) {
    private val latStep = cellMeters / METERS_PER_DEGREE_LAT
    private val lonStep = cellMeters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(atLat)).coerceAtLeast(0.01))

    fun cellOf(p: GeoPoint): Long = key(floor(p.lat / latStep).toInt(), floor(p.lon / lonStep).toInt())

    /** Every cell within [meters] of [p], as a flat sequence of keys. */
    fun cellsAround(p: GeoPoint, meters: Double): List<Long> {
        val latCells = kotlin.math.ceil(meters / (latStep * METERS_PER_DEGREE_LAT)).toInt()
        val lonCells = kotlin.math.ceil(meters / (lonStep * METERS_PER_DEGREE_LAT)).toInt()
        val baseLat = floor(p.lat / latStep).toInt()
        val baseLon = floor(p.lon / lonStep).toInt()
        val out = ArrayList<Long>((2 * latCells + 1) * (2 * lonCells + 1))
        for (dLat in -latCells..latCells) {
            for (dLon in -lonCells..lonCells) {
                out += key(baseLat + dLat, baseLon + dLon)
            }
        }
        return out
    }

    private fun key(lat: Int, lon: Int): Long = (lat.toLong() shl 32) xor (lon.toLong() and 0xffffffffL)
}

/**
 * Distance from an arbitrary point to a polyline, without walking the whole
 * line every time.
 *
 * The answer is **capped**: anything further than [capMeters] comes back as
 * exactly [capMeters], meaning "at least this far". That is what makes it fast
 * in the case that used to be slowest — a point way off the other route — and
 * it costs nothing real, because every caller is comparing against a threshold
 * far below the cap: 50 m to call a stretch divergent, 60 m to call it a fork,
 * 2.5 km to call a camera nearby. Below the cap the answer is exact.
 */
class PolylineIndex(
    private val line: List<GeoPoint>,
    private val capMeters: Double = CAP_METERS,
    cellMeters: Double = CELL_METERS,
) {
    private val grid = Grid(cellMeters, line.firstOrNull()?.lat ?: 0.0)
    private val buckets = HashMap<Long, MutableList<Int>>()
    private val reach = cellMeters

    /** The line's own extent, for an O(1) "nowhere near this" answer. */
    private val bounds = if (line.size >= 2) BoundingBox.of(line) else null

    init {
        for (i in 0 until line.size - 1) {
            // Bucket both ends and the midpoint, so a segment longer than a cell
            // is still found from somewhere along its length.
            val a = line[i]
            val b = line[i + 1]
            val mid = GeoPoint((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
            for (p in listOf(a, b, mid)) {
                buckets.getOrPut(grid.cellOf(p)) { mutableListOf() }.add(i)
            }
        }
    }

    /**
     * Metres from [p] to the nearest point of the line, saturating at
     * [capMeters]. Rings widen until the nearest thing found sits inside the
     * area searched — only then can nothing closer be hiding in a cell that was
     * not looked at.
     */
    fun distanceMeters(p: GeoPoint): Double {
        if (line.size < 2 || bounds == null) return capMeters
        // Nowhere near the line at all: no need to walk rings over empty space.
        if (!bounds.expand(capMeters).contains(p)) return capMeters
        var radius = reach
        while (true) {
            var best = Double.MAX_VALUE
            for (cell in grid.cellsAround(p, radius)) {
                val segments = buckets[cell] ?: continue
                for (i in segments) {
                    val d = pointToSegmentMeters(p, line[i], line[i + 1])
                    if (d < best) best = d
                }
            }
            if (best <= radius) return minOf(best, capMeters)
            if (radius >= capMeters) return capMeters
            radius = minOf(radius * 4, capMeters)
        }
    }

    private companion object {
        /** Big enough that most queries hit one ring, small enough to stay sparse. */
        const val CELL_METERS = 1_000.0

        /**
         * Past this, "how far exactly" stops mattering to anyone who asks.
         * Comfortably above the largest threshold any caller compares against.
         */
        const val CAP_METERS = 5_000.0
    }
}

/**
 * Points bucketed by location, for "which of these are near the route" sweeps.
 * [locate] pulls the coordinate out of whatever is being indexed.
 */
class PointIndex<T>(items: List<T>, cellMeters: Double = 500.0, private val locate: (T) -> GeoPoint) {
    private val grid = Grid(cellMeters, items.firstOrNull()?.let(locate)?.lat ?: 0.0)
    private val buckets = HashMap<Long, MutableList<T>>()

    init {
        for (item in items) buckets.getOrPut(grid.cellOf(locate(item))) { mutableListOf() }.add(item)
    }

    /** Everything within [meters] of [p]. May include a few extras; never misses one. */
    fun near(p: GeoPoint, meters: Double): List<T> {
        val out = mutableListOf<T>()
        for (cell in grid.cellsAround(p, meters)) {
            buckets[cell]?.let { out.addAll(it) }
        }
        return out
    }
}
