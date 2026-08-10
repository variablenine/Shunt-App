package app.shunt.solver.waypoints

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.pointToPolyline
import kotlin.test.Test
import app.shunt.solver.brouter.CameraVision
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaypointExtractorTest {

    private fun line(lat: Double, fromLon: Double = -98.2, toLon: Double = -97.8, n: Int = 40) =
        (0..n).map { GeoPoint(lat, fromLon + (toLon - fromLon) * it / n) }

    @Test
    fun `identical routes need no waypoints`() {
        val route = line(39.0)
        assertTrue(WaypointExtractor.extract(route, route).isEmpty())
    }

    @Test
    fun `single detour gets a single waypoint on the detour`() {
        val fastest = line(39.0)
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
    fun `many detours are capped, longest kept, route order preserved`() {
        val fastest = line(39.0, n = 140)
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
        // An explicit cap is still honoured when a caller asks for one.
        val waypoints = WaypointExtractor.extract(chosen, fastest, maxWaypoints = 5)
        assertTrue(waypoints.size == 5, "expected 5, got ${waypoints.size}")
        // Route order: longitudes strictly increasing (route runs west→east).
        val lons = waypoints.map { it.lon }
        assertTrue(lons == lons.sorted(), "waypoints out of route order: $lons")
    }

    @Test
    fun `an entirely divergent route is pinned from its own points, in order`() {
        // There is no fixed budget any more: what bounds the count is the route
        // itself, since every pin is a point on it.
        val fastest = line(39.0, n = 200)
        val chosen = fastest.map { GeoPoint(it.lat + 0.02, it.lon) } // entirely divergent
        val waypoints = WaypointExtractor.extract(chosen, fastest)

        assertTrue(waypoints.size <= chosen.size, "more pins than the route has points")
        assertTrue(waypoints.all { it in chosen }, "a pin must be a point on the route")
        assertTrue(waypoints.map { it.lon } == waypoints.map { it.lon }.sorted(), "out of route order")
    }

    // ---- Stopping the car cutting the corner ----------------------------
    //
    // The vehicle gets waypoints, not our polyline, and routes itself between
    // them. A detour pinned by one waypoint can still be short-circuited: the
    // car drives straight from pin to pin, back past the very camera the detour
    // existed to avoid — while the app still calls the route camera-free.

    /** A camera sitting on the fast line, watching all directions. */
    private fun cameraAt(p: GeoPoint) = CameraVision(p, directionDegrees = null)

    /**
     * An L-shaped detour: east along the fast line, then a hard turn north, then
     * east again. One pin lands near the far end, so the straight hop from the
     * start to that pin slices right across the bend — and a camera sitting in
     * that cut is passed by the car even though our route stays well north of it.
     */
    private fun lShapedDetour(): Pair<List<GeoPoint>, List<GeoPoint>> {
        val fastest = line(39.0, n = 120)
        val chosen = buildList {
            // East along the fast line to the corner.
            for (i in 0..40) add(GeoPoint(39.0, -98.2 + 0.4 * i / 120))
            // North at the corner.
            for (i in 1..20) add(GeoPoint(39.0 + 0.05 * i / 20, -98.2 + 0.4 * 40 / 120))
            // East again, well north of the fast line.
            for (i in 1..60) add(GeoPoint(39.05, -98.2 + 0.4 * (40 + i) / 120))
        }
        return chosen to fastest
    }

    @Test
    fun `a waypoint is added when the shortcut between pins passes a camera`() {
        val (chosen, fastest) = lShapedDetour()
        val without = WaypointExtractor.extract(chosen, fastest)

        // Place the camera on the straight hop between the start and that pin,
        // where our own route does not go.
        val start = chosen.first()
        val pin = without.first()
        val camera = cameraAt(
            GeoPoint((start.lat + pin.lat) / 2, (start.lon + pin.lon) / 2),
        )
        assertTrue(!camera.seesRoute(chosen), "the route itself must clear the camera")
        assertTrue(
            camera.seesRoute(listOf(start, pin)),
            "the shortcut must be exposed for this test to mean anything",
        )

        val with = WaypointExtractor.extract(chosen, fastest, avoid = listOf(camera))
        assertTrue(
            with.size > without.size,
            "camera-aware extraction must add pins; got ${with.size} vs ${without.size}",
        )
        // Every straight hop the car might take must now clear the camera.
        val chain = listOf(chosen.first()) + with + listOf(chosen.last())
        for (i in 0 until chain.size - 1) {
            assertTrue(
                !camera.seesRoute(listOf(chain[i], chain[i + 1])),
                "the hop from pin $i to ${i + 1} still cuts through the camera",
            )
        }
    }

    @Test
    fun `no extra pins are spent when the shortcut is already clear`() {
        val fastest = line(39.0, n = 120)
        val chosen = fastest.mapIndexed { i, p ->
            if (i in 40..80) GeoPoint(p.lat + 0.02, p.lon) else p
        }
        // A camera far from everything: nothing to pin against.
        val faraway = cameraAt(GeoPoint(39.5, -97.0))
        assertEquals(
            WaypointExtractor.extract(chosen, fastest),
            WaypointExtractor.extract(chosen, fastest, avoid = listOf(faraway)),
        )
    }

    @Test
    fun `a camera the route knowingly passes does not consume the budget`() {
        // The route drives past this one and says so; pinning against it is
        // pointless and would waste waypoints that shape the detour.
        val fastest = line(39.0, n = 120)
        val chosen = fastest.mapIndexed { i, p ->
            if (i in 40..80) GeoPoint(p.lat + 0.02, p.lon) else p
        }
        val onOurRoute = cameraAt(chosen[60])
        assertEquals(
            WaypointExtractor.extract(chosen, fastest),
            WaypointExtractor.extract(chosen, fastest, avoid = listOf(onOurRoute)),
        )
    }

    @Test
    fun `heavy camera pressure terminates and pins as much as it needs`() {
        val fastest = line(39.0, n = 200)
        val chosen = fastest.mapIndexed { i, p ->
            if (i in 40..160) GeoPoint(p.lat + 0.02, p.lon) else p
        }
        // A dense line of cameras along the fast route: every shortcut is exposed.
        val cameras = (40..160 step 5).map { cameraAt(fastest[it]) }
        val waypoints = WaypointExtractor.extract(chosen, fastest, avoid = cameras)

        // The point is that it ends, and that it is free to use as many pins as
        // the shortcuts demand rather than rationing them across the route.
        assertTrue(waypoints.size <= chosen.size, "more pins than the route has points")
        assertTrue(waypoints.isNotEmpty(), "a route this exposed must be pinned")
        assertTrue(waypoints.distinct().size == waypoints.size, "duplicate pins")
    }

    @Test
    fun `closing shortcuts stops on its budget`() {
        // This phase had no bound at all, and it is not cheap: a chord early in
        // the loop spans most of the trip, and every camera walks it. On a real
        // 615 km route it took 349 s inside a 20 s budget, which the planner had
        // no way to notice because the clock was only checked around routing.
        val (chosen, fastest) = lShapedDetour()
        val shapeOnly = WaypointExtractor.extract(chosen, fastest)
        val camera = cameraAt(
            GeoPoint(
                (chosen.first().lat + shapeOnly.first().lat) / 2,
                (chosen.first().lon + shapeOnly.first().lon) / 2,
            ),
        )

        val unhurried = WaypointExtractor.extract(chosen, fastest, avoid = listOf(camera))
        val rushed = WaypointExtractor.extract(
            chosen, fastest, avoid = listOf(camera), outOfTime = { true },
        )

        assertTrue(
            unhurried.size > shapeOnly.size,
            "the shortcut must be worth closing, or this proves nothing",
        )
        assertEquals(
            shapeOnly,
            rushed,
            "out of time must hand back the pins already found, not close shortcuts anyway",
        )
        // Fewer pins is safe; a wrong route is not. What comes back is still the
        // route's own points, in its own order.
        assertTrue(rushed.all { it in chosen }, "pins must still be points on the chosen route")
        val lons = rushed.map { it.lon }
        assertEquals(lons.sorted(), lons, "and still in route order")
    }
}
