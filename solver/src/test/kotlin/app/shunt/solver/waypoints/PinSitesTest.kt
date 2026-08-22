package app.shunt.solver.waypoints

import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.CameraIndex
import app.shunt.solver.geo.PolylineIndex
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A pin is a coordinate the car snaps to its own road graph, so it has to be a
 * sane place to send one. Both cases here were reported from a real drive:
 * pulling into a driveway at a pin that landed on a junction, and navigating to
 * a road running parallel to the planned route.
 */
class PinSitesTest {

    private val noCameras = CameraIndex(emptyList())

    @Test
    fun `a pin may not sit on a turn`() {
        val sites = PinSites(corner(runInMeters = 2_000.0, limbMeters = 800.0))
        val turn = sites.turns.first()
        assertFalse(sites.usable(turn), "a pin on the junction itself")
        assertFalse(sites.usable(turn + PinSites.CLEARANCE_METERS / 2), "a pin just past it")
        assertTrue(sites.usable(turn + PinSites.CLEARANCE_METERS * 3), "clear of it")
    }

    @Test
    fun `a turn directly after a turn gets no pin of its own`() {
        // The reported shape: two junctions closer together than a pin can fit
        // between. The second turn's pin holds both — a point only reachable by
        // making both turns instructs both.
        val route = zigzag(runInMeters = 2_000.0, limbMeters = 40.0, runOutMeters = 2_000.0)
        val sites = PinSites(route)
        val turns = sites.turns
        assertEquals(2, turns.size, "fixture must have two turns, close together")
        assertTrue(sites.blockAfter(turns[0]).isEmpty(), "no room between them")

        val pins = WaypointExtractor.turnPins(route, noCameras, sites)
        assertEquals(1, pins.size, "one pin, past the second turn: $pins")
        assertTrue(pins[0] > turns[1] + PinSites.CLEARANCE_METERS, "at ${pins[0]}, turn at ${turns[1]}")
    }

    @Test
    fun `a turn pin never lands past the next turn`() {
        // The fork distance is 600 m on open road, and this fixture's turns are
        // 300 m apart — so the pin used to be dropped 300 m *beyond* the second
        // junction, on a road it was never meant to describe. That is what put
        // a waypoint on a turn, and the car pulls in when it arrives at one.
        val route = zigzag(runInMeters = 2_000.0, limbMeters = 300.0, runOutMeters = 2_000.0)
        val sites = PinSites(route)
        val turns = sites.turns
        assertEquals(2, turns.size)
        assertTrue(
            turns[1] - turns[0] < WaypointRefiner.PAST_FORK_METERS,
            "fixture must put the next turn inside the fork distance",
        )

        val pins = WaypointExtractor.turnPins(route, noCameras, sites)
        val first = pins.firstOrNull { it > turns[0] && it < turns[1] }
        assertNotNull(first, "the first turn should still be pinned, in the block after it: $pins")
        for (pin in pins) {
            for (turn in turns) {
                assertTrue(
                    kotlin.math.abs(pin - turn) >= PinSites.CLEARANCE_METERS,
                    "pin at $pin sits on the turn at $turn",
                )
            }
        }
    }

    @Test
    fun `a pin is not placed beside a road running parallel to the route`() {
        // A frontage road twenty metres away. Our coordinate is on our line;
        // the car's map decides for itself which of the two is nearest.
        val route = straight(2_000.0)
        val parallel = PolylineIndex(straight(200.0, eastOffset = 900.0, northOffset = 20.0))
        val sites = PinSites(route, elsewhere = listOf(parallel))

        assertFalse(sites.usable(1_000.0), "alongside the parallel road")
        assertTrue(sites.usable(300.0), "well clear of it")

        val settled = sites.settleNear(1_000.0)
        assertNotNull(settled, "there is clear road within reach")
        assertTrue(settled < 900.0 || settled > 1_100.0, "settled at $settled, still alongside")
    }

    @Test
    fun `a route that comes back near itself is ambiguous there`() {
        // A switchback, a cloverleaf, or our own route using both carriageways:
        // two stretches of the same line thirty metres apart and kilometres
        // apart along it.
        val out = straight(2_000.0)
        val back = straight(2_000.0, northOffset = 30.0).reversed()
        val route = out + back
        val sites = PinSites(route)

        assertFalse(sites.usable(1_000.0), "the return limb runs thirty metres away")
    }

    @Test
    fun `the same road drawn by two routes is not a second road`() {
        // Two routes over the same OSM way share its nodes, so the distance is
        // zero. Treating that as an ambiguity would delete every pin on every
        // stretch where the route and the fastest route agree — which is most
        // of most trips.
        val route = straight(2_000.0)
        val sites = PinSites(route, elsewhere = listOf(PolylineIndex(straight(2_000.0))))

        assertTrue(sites.usable(1_000.0))
    }

    @Test
    fun `no room at all means no pin rather than a pin in the wrong place`() {
        val route = straight(2_000.0)
        val parallel = PolylineIndex(straight(2_000.0, northOffset = 20.0))
        val sites = PinSites(route, elsewhere = listOf(parallel))

        assertNull(sites.settleNear(1_000.0), "the whole route is alongside another road")
    }

    // --- fixtures, in neutral central-US placeholder coordinates ---

    private val base = GeoPoint(39.0, -98.0)
    private val metersPerDegLat = 111_320.0
    private val metersPerDegLon = 111_320.0 * cos(Math.toRadians(39.0))

    private fun at(eastMeters: Double, northMeters: Double) = GeoPoint(
        base.lat + northMeters / metersPerDegLat,
        base.lon + eastMeters / metersPerDegLon,
    )

    /** A straight run east, a point every ten metres. */
    private fun straight(
        meters: Double,
        eastOffset: Double = 0.0,
        northOffset: Double = 0.0,
    ): List<GeoPoint> =
        (0..(meters / 10.0).toInt()).map { at(eastOffset + it * 10.0, northOffset) }

    /** East, then a single left turn and a limb north. */
    private fun corner(runInMeters: Double, limbMeters: Double): List<GeoPoint> =
        straight(runInMeters) +
            (1..(limbMeters / 10.0).toInt()).map { at(runInMeters, it * 10.0) }

    /** East, north for [limbMeters], then east again — two turns, close together. */
    private fun zigzag(runInMeters: Double, limbMeters: Double, runOutMeters: Double): List<GeoPoint> =
        corner(runInMeters, limbMeters) +
            (1..(runOutMeters / 10.0).toInt()).map { at(runInMeters + it * 10.0, limbMeters) }
}
