package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.waypoints.PinSites
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A pin is a coordinate the car snaps to *its* road graph, so a second road a
 * few tens of metres away is one it may pick instead. Reported twice from real
 * drives: a waypoint near a highway sending the car to the other carriageway,
 * and the car preferring a parallel side road over the highway.
 */
class PinRoadAmbiguityTest {

    private val base = GeoPoint(39.0, -98.0)
    private val metersPerDegLon = 111_320.0 * cos(Math.toRadians(39.0))

    /** A straight run east, a point every ten metres. */
    private val route = (0..300).map { GeoPoint(base.lat, base.lon + it * 10.0 / metersPerDegLon) }

    private fun planner(roadsNear: (List<GeoPoint>, Double) -> List<List<Double>>) =
        BrouterPlanner(
            route = { emptyList() },
            missingTiles = { emptyList() },
            camerasIn = { emptyList() },
            roadsNear = roadsNear,
        )

    @Test
    fun `a pin beside a parallel road moves to where there is only one road`() {
        val pin = route[150]
        // Everything within 80 m of the pin has a road 20 m off; past that it is
        // clear. The nudge should carry the pin out of the ambiguous stretch.
        val moved = planner { points, _ ->
            points.map { p ->
                if (kotlin.math.abs(alongIndexOf(p) - 150) <= 8) listOf(20.0) else emptyList()
            }
        }.onUnambiguousRoad(route, listOf(pin))

        assertEquals(1, moved.size, "the pin should have moved, not vanished")
        assertTrue(moved.single() != pin, "the pin stayed in the ambiguous stretch")
        assertTrue(
            kotlin.math.abs(alongIndexOf(moved.single()) - 150) > 8,
            "moved to index ${alongIndexOf(moved.single())}, still alongside",
        )
    }

    @Test
    fun `a divided highway keeps its pins rather than losing them all`() {
        // **The case that makes this "best available" rather than pass-or-fail.**
        // Two carriageways run twenty metres apart for the whole length, so no
        // nudge finds clear road — and a motorway with no pins at all is how the
        // car takes an exit nobody planned.
        val pins = listOf(route[100], route[150], route[200])
        val kept = planner { points, _ -> points.map { listOf(20.0) } }
            .onUnambiguousRoad(route, pins)

        assertEquals(pins.size, kept.size, "pins were dropped along a divided highway")
    }

    @Test
    fun `a pin with another road close on every side is still kept`() {
        // **Never dropped, only moved**, and this is the case that taught us
        // why. An interchange has ramps, a service road and the far carriageway
        // all within any threshold worth setting, so a rule that deleted pins
        // there took the steering off a motorway exactly where it decides
        // something — reported as "pretty much unusable on highways".
        val tooClose = PinSites.MIN_OTHER_ROAD_METERS / 2
        val kept = planner { points, _ -> points.map { listOf(tooClose) } }
            .onUnambiguousRoad(route, listOf(route[150]))

        assertEquals(1, kept.size, "a pin was deleted for want of a clear spot: $kept")
    }

    @Test
    fun `a pin moves to whichever nearby position has the most room`() {
        // The query's job is to improve placement. The wanted position is
        // offered first, so it wins ties; here it is the worst of the set and
        // must lose.
        val wanted = route[150]
        val kept = planner { points, _ ->
            // The first candidate is the wanted position, the rest are nudges.
            points.mapIndexed { i, _ -> if (i == 0) listOf(4.0) else listOf(30.0) }
        }.onUnambiguousRoad(route, listOf(wanted))

        assertEquals(1, kept.size)
        assertTrue(kept.single() != wanted, "stayed on the cramped position $wanted")
    }

    @Test
    fun `the road the pin sits on is not a second road`() {
        // Our own way reads as nought. Counting it would delete every pin ever
        // placed, since every pin is on the route by construction.
        val pins = listOf(route[100], route[200])
        val kept = planner { points, _ -> points.map { listOf(0.0, 0.4) } }
            .onUnambiguousRoad(route, pins)

        assertEquals(pins, kept)
    }

    @Test
    fun `with no graph to ask, placement is left exactly as the geometry decided`() {
        val pins = listOf(route[100], route[200])
        val planner = BrouterPlanner(
            route = { emptyList() },
            missingTiles = { emptyList() },
            camerasIn = { emptyList() },
        )
        assertEquals(pins, planner.onUnambiguousRoad(route, pins))
    }

    @Test
    fun `a route that runs back near itself does not relocate its pins`() {
        // **A nearest-vertex search over the whole line matches the wrong
        // passage** wherever a route comes back near itself, and the nudge is
        // then applied from there — so the pin lands on a different road
        // entirely. Reported as waypoints "missing the highway entirely".
        val out = (0..150).map { GeoPoint(base.lat, base.lon + it * 10.0 / metersPerDegLon) }
        val back = (0..150).map {
            GeoPoint(base.lat + 25.0 / 111_320.0, base.lon + (150 - it) * 10.0 / metersPerDegLon)
        }
        val loop = out + back
        val pin = out[75]

        val kept = BrouterPlanner(
            route = { emptyList() },
            missingTiles = { emptyList() },
            camerasIn = { emptyList() },
            roadsNear = { points, _ -> points.map { emptyList() } },
        ).onUnambiguousRoad(loop, listOf(pin))

        // With nothing near anything the pin should not move at all; the failure
        // mode is it being re-placed on the return limb, twenty-five metres north.
        assertEquals(listOf(pin), kept)
    }

    private fun alongIndexOf(p: GeoPoint): Int =
        route.indices.minByOrNull {
            kotlin.math.abs(route[it].lon - p.lon) + kotlin.math.abs(route[it].lat - p.lat)
        } ?: -1
}
