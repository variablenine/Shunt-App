package app.shunt.solver.waypoints

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.pointToPolyline
import kotlin.test.Test
import kotlin.test.assertTrue

class WaypointExtractorTest {

    private fun line(lat: Double, fromLon: Double = -88.2, toLon: Double = -87.8, n: Int = 40) =
        (0..n).map { GeoPoint(lat, fromLon + (toLon - fromLon) * it / n) }

    @Test
    fun `identical routes need no waypoints`() {
        val route = line(45.0)
        assertTrue(WaypointExtractor.extract(route, route).isEmpty())
    }

    @Test
    fun `single detour gets a single waypoint on the detour`() {
        val fastest = line(45.0)
        // Chosen route bulges 2 km north over the middle third.
        val chosen = fastest.mapIndexed { i, p ->
            if (i in 14..26) GeoPoint(p.lat + 0.018, p.lon) else p
        }
        val waypoints = WaypointExtractor.extract(chosen, fastest)
        assertTrue(waypoints.size == 1, "expected 1 waypoint, got ${waypoints.size}")
        val d = pointToPolyline(waypoints[0], fastest).distanceMeters
        assertTrue(d > 1000, "waypoint must sit on the divergent stretch, was ${d}m off")
    }

    @Test
    fun `many detours capped at five, longest kept, route order preserved`() {
        val fastest = line(45.0, n = 140)
        // 7 separate bulges of increasing length.
        val chosen = fastest.toMutableList()
        var start = 5
        val runLengths = listOf(3, 4, 5, 6, 7, 8, 9)
        for (len in runLengths) {
            for (i in start until start + len) {
                chosen[i] = GeoPoint(chosen[i].lat + 0.01, chosen[i].lon)
            }
            start += len + 8
        }
        val waypoints = WaypointExtractor.extract(chosen, fastest)
        assertTrue(waypoints.size == 5, "expected 5, got ${waypoints.size}")
        // Route order: longitudes strictly increasing (route runs west→east).
        val lons = waypoints.map { it.lon }
        assertTrue(lons == lons.sorted(), "waypoints out of route order: $lons")
    }

    @Test
    fun `waypoints always within cap`() {
        val fastest = line(45.0, n = 200)
        val chosen = fastest.map { GeoPoint(it.lat + 0.02, it.lon) } // entirely divergent
        assertTrue(WaypointExtractor.extract(chosen, fastest).size <= WaypointExtractor.MAX_WAYPOINTS)
    }
}
