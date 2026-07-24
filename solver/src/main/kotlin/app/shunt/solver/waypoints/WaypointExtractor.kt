package app.shunt.solver.waypoints

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.haversineMeters
import app.shunt.solver.geo.pointToPolyline

/**
 * Emits the smallest set of intermediate points that pins the chosen route
 * against the unconstrained fastest route: find where the chosen polyline
 * diverges from the fastest one, and drop one waypoint at the most divergent
 * point of each stretch. Targets [MAX_WAYPOINTS] or fewer; when the routes
 * diverge in more places than that, the longest stretches win.
 */
object WaypointExtractor {
    const val MAX_WAYPOINTS = 5

    /** Chosen-route points farther than this from the fastest route count as divergent. */
    const val DIVERGENCE_THRESHOLD_METERS = 50.0

    fun extract(
        chosen: List<GeoPoint>,
        fastest: List<GeoPoint>,
        maxWaypoints: Int = MAX_WAYPOINTS,
        thresholdMeters: Double = DIVERGENCE_THRESHOLD_METERS,
    ): List<GeoPoint> {
        if (chosen.size < 2 || fastest.size < 2) return emptyList()

        // Contiguous runs of chosen-route indices that are off the fastest route.
        data class Run(val start: Int, val end: Int, val peakIndex: Int, val lengthMeters: Double)
        val runs = mutableListOf<Run>()
        var runStart = -1
        var peakIndex = -1
        var peakDistance = -1.0
        var runLength = 0.0

        fun closeRun(endExclusive: Int) {
            if (runStart >= 0) {
                runs += Run(runStart, endExclusive - 1, peakIndex, runLength)
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
                closeRun(i)
            }
        }
        closeRun(chosen.size)

        return runs
            .sortedByDescending { it.lengthMeters }
            .take(maxWaypoints)
            .sortedBy { it.peakIndex } // restore route order
            .map { chosen[it.peakIndex] }
    }
}
