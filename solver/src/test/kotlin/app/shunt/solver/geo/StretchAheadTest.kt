package app.shunt.solver.geo

import app.shunt.core.GeoPoint
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Describing the road a driver has just refused.
 *
 * These points become impassable circles, so two properties carry the whole
 * feature: they must start where the driver actually left the route (not at its
 * beginning), and they must be close enough together that the blocked circles
 * overlap. A gap between them is a thread the router will use, which puts the
 * car straight back onto the road it was supposed to stop offering.
 */
class StretchAheadTest {

    /** A straight line east, one point per ~870 m — deliberately sparse. */
    private val line: List<GeoPoint> = (0..40).map { GeoPoint(39.0, -98.0 + it * 0.01) }

    @Test
    fun `the stretch starts where the driver left, not at the start of the route`() {
        val leftAt = GeoPoint(39.0, -97.80) // 20 points along
        val stretch = stretchAhead(line, leftAt, lengthMeters = 3_000.0, spacingMeters = 100.0)

        assertTrue(stretch.isNotEmpty(), "there is road ahead here")
        assertTrue(
            stretch.all { it.lon >= -97.801 },
            "nothing may be blocked behind the driver: first was ${stretch.first().lon}",
        )
        assertTrue(
            haversineMeters(stretch.first(), leftAt) < 200.0,
            "and it must begin at the point they left, not somewhere further on",
        )
    }

    @Test
    fun `the points are close enough that the blocked circles cannot be threaded`() {
        // The line's own vertices are ~870 m apart. Stepping vertex to vertex
        // would leave gaps far wider than the circles, and the router would
        // simply drive between them.
        val stretch = stretchAhead(line, line.first(), lengthMeters = 5_000.0, spacingMeters = 100.0)

        assertTrue(stretch.size > 40, "a 5 km stretch at 100 m spacing needs many points: ${stretch.size}")
        val worstGap = stretch.zipWithNext().maxOf { (a, b) -> haversineMeters(a, b) }
        assertTrue(worstGap <= 140.0, "widest gap was $worstGap m — a router would thread that")
    }

    @Test
    fun `only the stretch asked for is blocked, never the rest of the trip`() {
        // Blocking everything ahead would block the trip rather than the road.
        val stretch = stretchAhead(line, line.first(), lengthMeters = 2_000.0, spacingMeters = 100.0)

        val covered = haversineMeters(stretch.first(), stretch.last())
        assertTrue(covered <= 2_200.0, "covered $covered m of a 2 km stretch")
    }

    @Test
    fun `a degenerate line or length asks for nothing`() {
        assertEquals(emptyList(), stretchAhead(emptyList(), line.first(), 1_000.0, 100.0))
        assertEquals(emptyList(), stretchAhead(line, line.first(), 0.0, 100.0))
        assertEquals(emptyList(), stretchAhead(line, line.first(), 1_000.0, 0.0))
    }
}
