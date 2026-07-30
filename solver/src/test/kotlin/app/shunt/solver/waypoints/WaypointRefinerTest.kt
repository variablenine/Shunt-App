package app.shunt.solver.waypoints

import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.CameraVision
import app.shunt.solver.geo.haversineMeters
import app.shunt.solver.geo.pointToPolyline
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The car takes the quickest road to whatever point it was given, and will not
 * make an extra turn to reach one. So a pin only avoids a camera if the fastest
 * path to it already does — which is what these tests check, by routing each leg
 * the way the car would and looking at where that goes.
 */
class WaypointRefinerTest {

    // A trip due east along a straight road, with a camera partway along it and
    // a detour that loops north around the camera and rejoins.
    //
    //          (detour, north)
    //        ┌─────────────────┐
    //   ─────┘        [cam]    └──────  (fast road, east)
    //  origin                        destination

    private val origin = GeoPoint(39.0, -98.20)
    private val destination = GeoPoint(39.0, -97.80)

    /** Sits on the fast road, in the middle of the stretch the detour bypasses. */
    private val cameraAt = GeoPoint(39.0, -98.05)

    /** North offset in degrees for a given number of metres. */
    private fun north(meters: Double) = meters / 111_320.0

    /** The fast road: a straight line east, passing the camera. */
    private val fastRoad: List<GeoPoint> = (0..40).map {
        GeoPoint(39.0, -98.20 + it * 0.01)
    }

    /** The avoiding route: east to the fork, 2 km north around the camera, back. */
    private val detour: List<GeoPoint> = buildList {
        // East along the fast road to the fork at -98.10.
        for (i in 0..10) add(GeoPoint(39.0, -98.20 + i * 0.01))
        // North onto the detour road.
        for (i in 1..4) add(GeoPoint(39.0 + north(i * 500.0), -98.10))
        // East along it, well clear of the camera, as far as -98.00.
        for (i in 1..10) add(GeoPoint(39.0 + north(2000.0), -98.10 + i * 0.01))
        // South again to rejoin the fast road at -98.00.
        for (i in 3 downTo 0) add(GeoPoint(39.0 + north(i * 500.0), -98.00))
        // On east to the destination.
        for (i in 1..20) add(GeoPoint(39.0, -98.00 + i * 0.01))
    }

    /** Only this camera matters; it sits on the fast road and not on the detour. */
    private val camera = CameraVision(cameraAt, directionDegrees = null)

    /** The northern (detour) leg only. */
    private val northernLeg: List<GeoPoint> =
        detour.filter { it.lat > 39.0 + north(1500.0) }

    /**
     * Stands in for the car's own navigation: the quickest way between two
     * points, which here means dropping back to the fast road unless both ends
     * already sit on the detour's northern leg.
     */
    private suspend fun carRoute(from: GeoPoint, to: GeoPoint): List<GeoPoint> {
        val onDetour = { p: GeoPoint -> pointToPolyline(p, northernLeg).distanceMeters < 300.0 }
        if (onDetour(from) && onDetour(to)) return listOf(from, to)
        return listOf(from, GeoPoint(39.0, from.lon), GeoPoint(39.0, to.lon), to)
    }

    /** A pin out at the far (eastern) end of the loop. */
    private val farEndPin = detour.first { it.lat > 39.0 + north(1500.0) && it.lon > -98.02 }

    @Test
    fun `a pin at the far end of the detour is not enough, and one gets added at the fork`() = runTest {
        // A single pin near the end of the loop: the car would drive the fast
        // road past the camera and join the detour at its tail — exactly the
        // failure this exists to prevent.
        val refined = WaypointRefiner.refine(
            chosen = detour,
            pins = listOf(farEndPin),
            avoid = listOf(camera),
            carRoute = ::carRoute,
        )

        assertTrue(refined.size > 1, "a pin had to be added before the far-end one")
        val added = refined.first()
        assertTrue(
            added.lat > 39.0 + north(200.0),
            "the added pin must sit on the detour road itself, not back on the fast road: $added",
        )
        assertTrue(
            added.lon < farEndPin.lon,
            "and before the pin that wasn't working, not after it",
        )
    }

    @Test
    fun `the added pin is close to the fork, not out at the far end`() = runTest {
        val refined = WaypointRefiner.refine(
            chosen = detour,
            pins = listOf(farEndPin),
            avoid = listOf(camera),
            carRoute = ::carRoute,
        )

        // Placed too far along and the car reaches it by the fast road anyway;
        // the whole point is that it is committed by the time it gets there.
        val fork = GeoPoint(39.0, -98.10)
        val distanceFromFork = haversineMeters(refined.first(), fork)
        assertTrue(
            distanceFromFork < 3_000.0,
            "pin should be just past the fork, was ${distanceFromFork.toInt()} m away",
        )
    }

    @Test
    fun `a route with nothing to avoid is left exactly as it was`() = runTest {
        val pins = listOf(detour[12], detour[20])
        assertEquals(
            pins,
            WaypointRefiner.refine(
                chosen = detour,
                pins = pins,
                avoid = emptyList(),
                carRoute = ::carRoute,
            ),
        )
    }

    @Test
    fun `a camera the route drives past anyway earns no pins`() = runTest {
        // It is already counted and warned about; pinning against it would buy
        // nothing and spend budget that a genuinely avoided camera may need.
        val onRoute = CameraVision(detour[5], directionDegrees = null)
        val pins = listOf(detour[20])
        assertEquals(
            pins,
            WaypointRefiner.refine(
                chosen = detour,
                pins = pins,
                avoid = listOf(onRoute),
                carRoute = ::carRoute,
            ),
        )
    }

    @Test
    fun `the pin budget is never exceeded`() = runTest {
        val refined = WaypointRefiner.refine(
            chosen = detour,
            pins = List(3) { detour[30] },
            avoid = listOf(camera),
            maxPins = 3,
            carRoute = ::carRoute,
        )
        assertTrue(refined.size <= 3, "budget blown: ${refined.size}")
    }

    @Test
    fun `a leg the router cannot answer for is left alone rather than guessed at`() = runTest {
        val pins = listOf(detour[30])
        assertEquals(
            pins,
            WaypointRefiner.refine(
                chosen = detour,
                pins = pins,
                avoid = listOf(camera),
                carRoute = { _, _ -> null },
            ),
        )
    }
}
