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
    fun `a detour is pinned onto its divergent stretch`() {
        val fastest = line(39.0)
        // Chosen route bulges 2 km north over the middle third — a hard corner
        // onto the detour and another back off it.
        val chosen = fastest.mapIndexed { i, p ->
            if (i in 14..26) GeoPoint(p.lat + 0.018, p.lon) else p
        }
        val waypoints = WaypointExtractor.extract(chosen, fastest)

        // This used to insist on exactly one. It now gets one per corner, which
        // is the point of turn pins: the corner onto the detour is precisely
        // where the car decides whether to take it.
        assertTrue(waypoints.isNotEmpty(), "the detour has to be held by something")
        assertTrue(
            waypoints.any { pointToPolyline(it, fastest).distanceMeters > 1000 },
            "at least one pin must sit out on the divergent stretch, not on the fast line",
        )
        assertTrue(waypoints.all { it in chosen }, "every pin is a point on the chosen route")
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
     * A route that leaves the fast line and rejoins it **without ever turning**:
     * a long, shallow arc, sampled finely enough that no 40 m span of it bends
     * anywhere near [WaypointExtractor.TURN_DEGREES].
     *
     * Needed because turn pins would otherwise do the shortcut-closer's job for
     * it. A hard-cornered detour gets pinned at its corners whatever the cameras
     * say, which makes it useless for testing the thing that closes *chords*.
     * Here there is no corner to pin, so any pin that appears is one the
     * shortcut pass put there.
     */
    private fun gentleArc(): Pair<List<GeoPoint>, List<GeoPoint>> {
        val n = 400
        val fastest = (0..n).map { GeoPoint(39.0, -98.2 + 0.4 * it / n) }
        val chosen = (0..n).map { i ->
            val t = i.toDouble() / n
            // ~1.1 km of bulge spread over 35 km: divergent, never a turn.
            GeoPoint(39.0 + 0.030 * kotlin.math.sin(Math.PI * t), -98.2 + 0.4 * t)
        }
        return chosen to fastest
    }

    /** The northernmost point of the arc, which the shape pass pins. */
    private fun peakOf(chosen: List<GeoPoint>) = chosen.maxBy { it.lat }

    /** Halfway along the straight hop from the route's start to that peak. */
    private fun midpointOfFirstHop(chosen: List<GeoPoint>): GeoPoint {
        val a = chosen.first()
        val b = peakOf(chosen)
        return GeoPoint((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
    }

    @Test
    fun `every turn on the route gets a pin, camera or not`() {
        // The car is given one point at a time and routes itself there. Between
        // two pins it is free, and a turn is the only place that freedom can
        // cost anything — carrying straight on is never a wrong answer to a
        // route that goes straight on. The refiner would only pin a turn where
        // *BRouter* predicts the car strays, which is exactly the prediction
        // worth not relying on at a junction.
        //
        // A zigzag that stays north of the fast line the whole way, so the
        // shape pass sees **one** continuous divergent run and pins one point,
        // while the route itself turns a dozen times. Anything beyond that one
        // pin is here because of the turns.
        val fastest = line(39.0, n = 200)
        val zigzag = buildList {
            var lon = -98.2
            var high = true
            repeat(12) {
                val lat = if (high) 39.020 else 39.010
                for (i in 0..20) add(GeoPoint(lat, lon + i * 0.0008))
                lon += 20 * 0.0008
                high = !high
            }
        }

        val pins = WaypointExtractor.extract(zigzag, fastest)
        val turns = app.shunt.solver.geo.turnsAlong(
            zigzag, WaypointExtractor.TURN_DEGREES, WaypointExtractor.TURN_SPAN_METERS,
        )
        val shapeOnlyCount = 1 // one divergent run, so one pin from shape alone

        assertTrue(turns.size >= 8, "the fixture must actually turn; found ${turns.size}")
        assertTrue(
            pins.size > shapeOnlyCount * 3,
            "a route with ${turns.size} turns came back with only ${pins.size} pins",
        )
        assertTrue(pins.all { it in zigzag }, "every pin is a point on the route")
    }

    @Test
    fun `the gentle arc really has no turns to pin`() {
        // Guards the two tests below: if this fixture ever grows a corner, they
        // would silently stop testing shortcut closing at all.
        val (chosen, _) = gentleArc()
        assertEquals(
            emptyList(),
            app.shunt.solver.geo.turnsAlong(
                chosen, WaypointExtractor.TURN_DEGREES, WaypointExtractor.TURN_SPAN_METERS,
            ),
        )
    }

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
        val (chosen, fastest) = gentleArc()
        val without = WaypointExtractor.extract(chosen, fastest)

        // The shape pass already pins the arc's peak, so the whole-route chord
        // is not the exposed one — the hop from the start *to* that peak is.
        // A sine arc is concave, so it bows away from that chord, leaving room
        // for a camera the chord passes and the route does not.
        val camera = cameraAt(midpointOfFirstHop(chosen))
        assertTrue(!camera.seesRoute(chosen), "the route itself must clear the camera")
        assertTrue(
            camera.seesRoute(listOf(chosen.first(), peakOf(chosen))),
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

    // ---- Holding the route past a camera it squeezes by -------------------
    //
    // Reported from a real plan: a detour around a camera with no pin anywhere
    // near the squeeze — "a route that the car could easily still route through
    // a camera, I don't trust that". Everything else that guards this stretch
    // asks BRouter what the car would do, and that is the one place where being
    // wrong costs the exposure the whole route exists to prevent.

    private val metresNorth = 1.0 / 111_320.0

    private val metresEast = 1.0 / (111_320.0 * kotlin.math.cos(Math.toRadians(39.0)))

    /**
     * A detour running 4 km east, a bit over 2 km north of the fast line, with a
     * 50 m bulge in the middle so the shape pass has one unambiguous peak to pin.
     *
     * Deliberately straight enough to have no turn in it: with a corner, turn
     * pins would be placing waypoints here whatever the cameras said, and these
     * tests would pass without the thing they are testing existing.
     */
    private fun offsetDetour(): Pair<List<GeoPoint>, List<GeoPoint>> {
        val n = 200
        val lengthMeters = 4_000.0
        val fastest = (0..n).map { GeoPoint(39.0, -98.0 + lengthMeters * metresEast * it / n) }
        val chosen = (0..n).map {
            val t = it.toDouble() / n
            GeoPoint(
                39.0 + (2_000.0 + 50.0 * kotlin.math.sin(Math.PI * t)) * metresNorth,
                -98.0 + lengthMeters * metresEast * t,
            )
        }
        return chosen to fastest
    }

    private fun emptyIndex() = app.shunt.solver.brouter.CameraIndex(emptyList())

    private fun indexOf(vararg cameras: CameraVision) =
        app.shunt.solver.brouter.CameraIndex(cameras.toList())

    private fun lengthOf(line: List<GeoPoint>): Double =
        (1 until line.size).sumOf { app.shunt.solver.geo.haversineMeters(line[it - 1], line[it]) }

    @Test
    fun `a camera the route squeezes past is bracketed by two pins`() {
        val (chosen, fastest) = offsetDetour()
        val middle = chosen[chosen.size / 2]
        // 300 m off the line: well outside the camera's 150 m reach, well
        // inside the guard radius. The car only has to wander a block.
        val camera = cameraAt(GeoPoint(middle.lat + 300 * metresNorth, middle.lon))
        assertTrue(!camera.seesRoute(chosen), "the route itself must clear the camera")

        val pins = WaypointExtractor.cameraGuardPins(
            chosen = chosen,
            avoided = indexOf(camera),
            density = emptyIndex(),
            fastest = app.shunt.solver.geo.PolylineIndex(fastest),
        )

        val at = lengthOf(chosen) / 2
        val guard = WaypointRefiner.PAST_FORK_METERS
        assertEquals(2, pins.size, "a squeeze wants a pin on each side of it, got $pins")
        assertTrue(pins.min() < at && pins.max() > at, "the pins must straddle the camera: $pins")
        // Within a sample step of the bracket, which is all the precision the
        // closest-approach sweep claims.
        assertTrue(
            kotlin.math.abs(pins.min() - (at - guard)) < WaypointExtractor.GUARD_SAMPLE_METERS * 2,
            "near pin ${pins.min()} is not a fork distance before $at",
        )
        assertTrue(
            kotlin.math.abs(pins.max() - (at + guard)) < WaypointExtractor.GUARD_SAMPLE_METERS * 2,
            "far pin ${pins.max()} is not a fork distance after $at",
        )
    }

    @Test
    fun `a camera beside the road the car would have taken anyway is not guarded`() {
        // The whole cost control. A straight run through a metro passes hundreds
        // of cameras a street over that it never goes near; bracketing each one
        // would put a pin every couple of hundred metres on a road with no
        // decision on it. Where our route *is* the fastest route, the car has no
        // reason to leave it.
        val (_, fastest) = offsetDetour()
        val middle = fastest[fastest.size / 2]
        val camera = cameraAt(GeoPoint(middle.lat + 300 * metresNorth, middle.lon))

        assertEquals(
            emptyList(),
            WaypointExtractor.cameraGuardPins(
                chosen = fastest,
                avoided = indexOf(camera),
                density = emptyIndex(),
                fastest = app.shunt.solver.geo.PolylineIndex(fastest),
            ),
        )
    }

    @Test
    fun `a camera the route is nowhere near is not guarded`() {
        val (chosen, fastest) = offsetDetour()
        val middle = chosen[chosen.size / 2]
        val faraway = cameraAt(
            GeoPoint(middle.lat + (WaypointExtractor.CAMERA_GUARD_RADIUS_METERS + 500) * metresNorth, middle.lon),
        )

        assertEquals(
            emptyList(),
            WaypointExtractor.cameraGuardPins(
                chosen = chosen,
                avoided = indexOf(faraway),
                density = emptyIndex(),
                fastest = app.shunt.solver.geo.PolylineIndex(fastest),
            ),
        )
    }

    @Test
    fun `extraction adds the guard pins, and nothing else would have`() {
        val (chosen, fastest) = offsetDetour()
        // A quarter of the way along, so the bracket lands clear of the pin the
        // shape pass puts at the bulge's peak and spacing has nothing to collapse.
        val squeeze = chosen[chosen.size / 4]
        val camera = cameraAt(GeoPoint(squeeze.lat + 300 * metresNorth, squeeze.lon))

        // The fixture is straight and parallel, so nothing else can react to
        // this camera: no turn to pin, and the only chord the car could cut is
        // the route itself, which the camera does not see.
        assertEquals(
            emptyList(),
            app.shunt.solver.geo.turnsAlong(
                chosen, WaypointExtractor.TURN_DEGREES, WaypointExtractor.TURN_SPAN_METERS,
            ),
            "the fixture must not turn, or turn pins would be doing this work",
        )
        assertTrue(
            !camera.seesRoute(listOf(chosen.first(), chosen.last())),
            "the whole-route chord must be clear, or the shortcut pass would close it",
        )

        val without = WaypointExtractor.extract(chosen, fastest)
        val with = WaypointExtractor.extract(chosen, fastest, avoid = listOf(camera))

        assertTrue(
            with.size > without.size,
            "the squeeze went unpinned: ${with.size} pins with the camera, ${without.size} without",
        )
        assertTrue(
            with.any { it.lon < squeeze.lon } && with.any { it.lon > squeeze.lon },
            "the new pins must sit either side of the camera, not just anywhere: $with",
        )
        assertTrue(with.all { it in chosen }, "every pin is a point on the chosen route")
    }

    @Test
    fun `guard pins are protected from pruning`() {
        // They exist because pruning's own test — "BRouter says the car would
        // stay on our line" — is the prediction they are there to stop relying
        // on. Letting it run on them would undo them one at a time.
        val (chosen, fastest) = offsetDetour()
        val middle = chosen[chosen.size / 2]
        val camera = cameraAt(GeoPoint(middle.lat + 300 * metresNorth, middle.lon))

        val protectedPins = WaypointExtractor.protectedPins(
            chosen = chosen,
            fastest = fastest,
            index = indexOf(camera),
            avoided = indexOf(camera),
        )
        assertEquals(2, protectedPins.size, "both sides of the squeeze must be protected")
        assertTrue(protectedPins.all { it in chosen }, "a protected pin must be a point on the route")
    }

    // ---- Pinning tightly where the roads are dense -----------------------
    //
    // One spacing everywhere was justified by "the car cannot meaningfully
    // deviate inside a few hundred metres". On a highway that is true. In a city
    // grid there is a turn every block, and the rule was throwing away exactly
    // the pins the refiner had worked hardest to place.

    /** [count] cameras scattered within a few hundred metres of [around]. */
    private fun cluster(around: GeoPoint, count: Int): app.shunt.solver.brouter.CameraIndex =
        app.shunt.solver.brouter.CameraIndex(
            (0 until count).map { CameraVision(GeoPoint(around.lat + it * 0.0005, around.lon), null) },
        )

    @Test
    fun `pins survive closer together where cameras are dense`() {
        val first = GeoPoint(39.0, -98.0)
        // 400 m apart: too close for open road, comfortably apart in a city.
        val second = GeoPoint(39.0, -98.0 + 400.0 / (111_320.0 * kotlin.math.cos(Math.toRadians(39.0))))
        val pins = listOf(first, second)

        assertEquals(
            listOf(first),
            WaypointExtractor.spaceOut(pins),
            "on open road the second pin is inside the monitor's lead distance and buys nothing",
        )
        assertEquals(
            pins,
            WaypointExtractor.spaceOut(pins, density = cluster(first, WaypointExtractor.DENSE_CAMERA_COUNT)),
            "in a grid that dense the car has turns between them, so both pins are real constraints",
        )
    }

    @Test
    fun `spacing never leaves the range its two ends define`() {
        val here = GeoPoint(39.0, -98.0)
        val empty = app.shunt.solver.brouter.CameraIndex(emptyList())
        assertEquals(
            WaypointExtractor.MIN_PIN_SPACING_METERS,
            WaypointExtractor.spacingAt(here, empty),
            "no cameras at all is open road",
        )
        // Well past the threshold: it must clamp, not keep tightening toward zero
        // — below the drive monitor's lead distance a pin is advanced past
        // before the car ever aims at it.
        val spacing = WaypointExtractor.spacingAt(here, cluster(here, WaypointExtractor.DENSE_CAMERA_COUNT * 5))
        assertEquals(WaypointExtractor.DENSE_PIN_SPACING_METERS, spacing, "clamped at the dense end")
    }

    @Test
    fun `spacing never discards a pin the refiner deliberately placed`() {
        // The refiner puts its pins exactly PAST_FORK past a fork. If spacing
        // is wider than that, it throws them away — which is the bug that made
        // dense areas worst, since those pins are the ones that matter there.
        // The two have to be paired at each end of the density scale.
        assertTrue(
            WaypointExtractor.MIN_PIN_SPACING_METERS <= WaypointRefiner.PAST_FORK_METERS,
            "open road: spacing ${WaypointExtractor.MIN_PIN_SPACING_METERS} would drop a pin " +
                "placed ${WaypointRefiner.PAST_FORK_METERS} past a fork",
        )
        assertTrue(
            WaypointExtractor.DENSE_PIN_SPACING_METERS <= WaypointRefiner.DENSE_PAST_FORK_METERS,
            "dense: spacing ${WaypointExtractor.DENSE_PIN_SPACING_METERS} would drop a pin " +
                "placed ${WaypointRefiner.DENSE_PAST_FORK_METERS} past a fork",
        )
    }

    @Test
    fun `closing shortcuts stops on its budget`() {
        // This phase had no bound at all, and it is not cheap: a chord early in
        // the loop spans most of the trip, and every camera walks it. On a real
        // 615 km route it took 349 s inside a 20 s budget, which the planner had
        // no way to notice because the clock was only checked around routing.
        //
        // The turn-free arc again: with a corner in it, turn pins would be doing
        // the work and running out of time would look like it changed nothing.
        val (chosen, fastest) = gentleArc()
        val camera = cameraAt(midpointOfFirstHop(chosen))

        val unhurried = WaypointExtractor.extract(chosen, fastest, avoid = listOf(camera))
        val rushed = WaypointExtractor.extract(
            chosen, fastest, avoid = listOf(camera), outOfTime = { true },
        )
        val shapeOnly = WaypointExtractor.extract(chosen, fastest)

        assertTrue(
            unhurried.size > shapeOnly.size,
            "the shortcut must be worth closing, or this proves nothing",
        )
        assertEquals(
            shapeOnly,
            rushed,
            "out of time must hand back the pins already found, not close shortcuts anyway",
        )
        assertTrue(rushed.all { it in chosen }, "pins must still be points on the chosen route")
        val lons = rushed.map { it.lon }
        assertEquals(lons.sorted(), lons, "and still in route order")
    }
}
