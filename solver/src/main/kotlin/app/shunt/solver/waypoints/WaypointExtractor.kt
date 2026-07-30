package app.shunt.solver.waypoints

import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.CameraVision
import app.shunt.solver.geo.haversineMeters
import app.shunt.solver.geo.pointToPolyline
import app.shunt.solver.geo.pointToSegmentMeters

/**
 * Picks the intermediate points to pin the chosen route against the vehicle's
 * own routing.
 *
 * The vehicle is not given our polyline — only a short chain of waypoints — and
 * it routes *itself* between them. So every waypoint we omit is a stretch where
 * the car may take its own line. For a camera-avoiding route that is the whole
 * ballgame: a car that cuts the corner between two sparse waypoints can rejoin
 * the fast road and drive straight past the camera the detour existed to avoid,
 * while the app still shows the route as camera-free.
 *
 * So selection happens in two passes:
 *  1. **Shape** — one waypoint at the most divergent point of each stretch where
 *     the chosen route leaves the fastest one. This is what makes the car take
 *     the detour at all.
 *  2. **Safety** — for each consecutive pair of pins, ask whether a car cutting
 *     straight between them would enter a camera's field of view that our own
 *     route stays out of. Where it would, insert the route point that pulls
 *     hardest away from that shortcut, and re-check. This spends the limited
 *     waypoint budget where it actually prevents exposure.
 *
 * There is no fixed budget: a route gets as many pins as it needs. See
 * [NO_LIMIT] for why a cap did more harm than good, and what bounds the count
 * instead. Callers may still pass an explicit cap.
 */
object WaypointExtractor {
    /**
     * No fixed budget: a route gets the pins it needs and no more.
     *
     * There used to be a cap of 8, on the theory that the vehicle takes a
     * bounded chain. It doesn't work that way — a car that requires signed
     * commands accepts one destination at a time, so pins go out one by one as
     * the drive progresses and the only cost is a rate-limited command each,
     * spread over hours. Meanwhile a cap does real harm: it is spent on
     * whichever stretches happen to be checked first and starves the rest of
     * the trip, so a long route ends up unpinned exactly where it matters.
     *
     * What bounds the count instead is the route itself. A pin is always an
     * actual point on the line, every insertion strictly shortens the stretch
     * it splits, and a stretch too short to hold one is left alone — so the
     * process runs out of places to put pins long before it runs out of route.
     */
    const val NO_LIMIT = Int.MAX_VALUE

    /** Chosen-route points farther than this from the fastest route count as divergent. */
    const val DIVERGENCE_THRESHOLD_METERS = 50.0

    fun extract(
        chosen: List<GeoPoint>,
        fastest: List<GeoPoint>,
        avoid: List<CameraVision> = emptyList(),
        maxWaypoints: Int = NO_LIMIT,
        thresholdMeters: Double = DIVERGENCE_THRESHOLD_METERS,
    ): List<GeoPoint> {
        if (chosen.size < 2 || fastest.size < 2) return emptyList()

        val shape = shapeIndices(chosen, fastest, maxWaypoints, thresholdMeters)
        val pinned = pinAgainstShortcuts(chosen, avoid, shape, maxWaypoints)
        return pinned.map { chosen[it] }
    }

    /** Indices of the most divergent point of each stretch off the fastest route. */
    private fun shapeIndices(
        chosen: List<GeoPoint>,
        fastest: List<GeoPoint>,
        maxWaypoints: Int,
        thresholdMeters: Double,
    ): List<Int> {
        data class Run(val peakIndex: Int, val lengthMeters: Double)
        val runs = mutableListOf<Run>()
        var runStart = -1
        var peakIndex = -1
        var peakDistance = -1.0
        var runLength = 0.0

        fun closeRun() {
            if (runStart >= 0) {
                runs += Run(peakIndex, runLength)
                runStart = -1; peakIndex = -1; peakDistance = -1.0; runLength = 0.0
            }
        }

        for (i in chosen.indices) {
            val d = pointToPolyline(chosen[i], fastest).distanceMeters
            if (d > thresholdMeters) {
                if (runStart < 0) runStart = i
                if (d > peakDistance) { peakDistance = d; peakIndex = i }
                if (i > 0) runLength += haversineMeters(chosen[i - 1], chosen[i])
            } else {
                closeRun()
            }
        }
        closeRun()

        return runs
            .sortedByDescending { it.lengthMeters }
            .let { if (maxWaypoints == NO_LIMIT) it else it.take(maxWaypoints) }
            .map { it.peakIndex }
            .sorted() // restore route order
    }

    /**
     * Insert extra waypoints wherever a car cutting straight from one pin to the
     * next would pass through a camera our route avoids, until no such shortcut
     * remains or the budget runs out.
     */
    private fun pinAgainstShortcuts(
        chosen: List<GeoPoint>,
        avoid: List<CameraVision>,
        shape: List<Int>,
        maxWaypoints: Int,
    ): List<Int> {
        if (avoid.isEmpty()) return shape

        // Only cameras our own route genuinely stays clear of are worth pinning
        // against — one the route knowingly passes is already reported to the
        // user, and no waypoint will change that.
        val avoided = avoid.filterNot { it.seesRoute(chosen) }
        if (avoided.isEmpty()) return shape

        // Work over the full chain including the endpoints, which bound the
        // first and last shortcuts.
        val pins = (listOf(0) + shape + listOf(chosen.lastIndex)).distinct().toMutableList()

        // Unlimited means "as many as the shortcuts demand"; the loop still ends
        // when there is no shortcut left to close.
        var budget = if (maxWaypoints == NO_LIMIT) Int.MAX_VALUE else maxWaypoints - shape.size
        var madeProgress = true
        while (budget > 0 && madeProgress) {
            madeProgress = false
            var i = 0
            while (i < pins.size - 1 && budget > 0) {
                val a = pins[i]
                val b = pins[i + 1]
                if (b - a > 1 && shortcutIsExposed(chosen, a, b, avoided)) {
                    val insert = mostDivergentBetween(chosen, a, b)
                    if (insert != null) {
                        pins.add(i + 1, insert)
                        budget--
                        madeProgress = true
                        continue // re-check the first half of the split span
                    }
                }
                i++
            }
        }

        // Endpoints are the origin and destination, supplied separately.
        return pins.filter { it != 0 && it != chosen.lastIndex }
    }

    /** True if the straight line from `chosen[a]` to `chosen[b]` enters a camera's view. */
    private fun shortcutIsExposed(
        chosen: List<GeoPoint>,
        a: Int,
        b: Int,
        avoided: List<CameraVision>,
    ): Boolean {
        val chord = listOf(chosen[a], chosen[b])
        return avoided.any { it.seesRoute(chord) }
    }

    /**
     * The route point between [a] and [b] that sits farthest from the straight
     * line between them — the one that most forces the car off the shortcut.
     */
    private fun mostDivergentBetween(chosen: List<GeoPoint>, a: Int, b: Int): Int? {
        var best = -1
        var bestDistance = 0.0
        for (i in (a + 1) until b) {
            val d = pointToSegmentMeters(chosen[i], chosen[a], chosen[b])
            if (d > bestDistance) { bestDistance = d; best = i }
        }
        return best.takeIf { it >= 0 }
    }
}
