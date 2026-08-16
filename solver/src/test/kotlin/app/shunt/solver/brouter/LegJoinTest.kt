package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.destinationPoint
import app.shunt.solver.geo.haversineMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The out-and-back where two legs meet.
 *
 * A leg boundary is a hard waypoint chosen on the *direct* road, which is not
 * where a camera-avoiding route goes — so the first leg can drive out to touch
 * it and the second immediately come back the same way. Reported from a real
 * plan: "a leg needs to go backwards after it found the way to the next spot".
 *
 * Neutral placeholder coordinates throughout (central US), per CLAUDE.md §3.
 */
class LegJoinTest {

    private val origin = GeoPoint(39.0, -98.0)

    /** A straight run of points [count] apart, [spacing] metres, on [bearing]. */
    private fun run(from: GeoPoint, bearing: Double, spacing: Double, count: Int): List<GeoPoint> =
        (0 until count).map { destinationPoint(from, bearing, spacing * it) }

    private fun lengthOf(line: List<GeoPoint>): Double =
        (1 until line.size).sumOf { haversineMeters(line[it - 1], line[it]) }

    @Test
    fun `a leg that doubles straight back loses the spur from both sides`() {
        // East for 5 km to the boundary, then the next leg comes back west over
        // the same road for 3 km before turning north. The last 3 km out and
        // the first 3 km back are the same road driven twice for nothing.
        val previous = run(origin, 90.0, 500.0, 11) // 0..5 km east
        val turnPoint = previous[4] // 2 km east
        val next = run(previous.last(), 270.0, 500.0, 7) + // 5 km back to 2 km
            run(turnPoint, 0.0, 500.0, 6).drop(1) // then north

        val trimmed = LegJoin.trimDoubleBack(previous, next)

        assertTrue(trimmed.changed, "the spur must be found")
        // Both trimmed ends must meet at the same place — that is what makes
        // the concatenation still a drivable line.
        assertTrue(
            haversineMeters(trimmed.previous.last(), trimmed.next.first()) <= LegJoin.OVERLAP_METERS,
            "the trimmed legs must still join: ${trimmed.previous.last()} vs ${trimmed.next.first()}",
        )
        assertTrue(
            trimmed.previous.size < previous.size,
            "the first leg must lose its outbound spur",
        )
        assertTrue(
            trimmed.next.size < next.size,
            "the second leg must lose its retrace",
        )
        // And the whole thing must actually get shorter.
        val before = lengthOf(previous) + lengthOf(next)
        val after = lengthOf(trimmed.previous) + lengthOf(trimmed.next)
        assertTrue(after < before, "trimming must shorten the drive: $before -> $after")
    }

    @Test
    fun `two legs that simply continue are left alone`() {
        // The ordinary case, and the one LegSplitter aims for: the boundary
        // lands where both legs were going the same way anyway. Trimming here
        // would be deleting real route.
        val previous = run(origin, 90.0, 500.0, 11)
        val next = run(previous.last(), 90.0, 500.0, 11)

        val trimmed = LegJoin.trimDoubleBack(previous, next)

        assertTrue(!trimmed.changed, "a straight continuation has no spur")
        assertEquals(previous, trimmed.previous)
        assertEquals(next, trimmed.next)
    }

    @Test
    fun `a leg that turns a corner at the boundary is left alone`() {
        // East, then north from the boundary. Nothing is retraced, so there is
        // nothing to remove — this is the shape a trim must never touch,
        // because every ordinary junction looks like it.
        val previous = run(origin, 90.0, 500.0, 11)
        val next = run(previous.last(), 0.0, 500.0, 11)

        val trimmed = LegJoin.trimDoubleBack(previous, next)

        assertTrue(!trimmed.changed, "a corner is not a spur")
    }

    @Test
    fun `a few metres of shared road at the join is not worth cutting`() {
        // Legs meeting at a shared waypoint always overlap slightly — that is
        // what a shared waypoint means. Cutting there would be churn, and would
        // move the join for no gain.
        val previous = run(origin, 90.0, 100.0, 11) // 1 km east
        val next = listOf(previous.last(), previous[9]) + run(previous[9], 0.0, 500.0, 6).drop(1)

        val trimmed = LegJoin.trimDoubleBack(previous, next)

        assertTrue(!trimmed.changed, "a 200 m overlap is below the spur threshold")
    }

    @Test
    fun `a route that legitimately doubles back later keeps that part`() {
        // The trap. A switchback, a frontage road, or a there-and-back to a
        // charging stop all bring a route near its own path — and only the
        // overlap *at the join* is a spur. Cutting a later one would delete a
        // real part of the drive.
        val previous = run(origin, 90.0, 500.0, 11)
        // Second leg: north away from the boundary at once, so no retrace at
        // the join, then later comes back across the first leg's road.
        val next = run(previous.last(), 0.0, 500.0, 5) +
            run(destinationPoint(previous.last(), 0.0, 2_000.0), 270.0, 500.0, 9).drop(1)

        val trimmed = LegJoin.trimDoubleBack(previous, next)

        assertTrue(!trimmed.changed, "only an overlap at the join is a spur")
        assertEquals(next, trimmed.next, "the later crossing must survive intact")
    }

    @Test
    fun `a leg is never trimmed out of existence`() {
        // A second leg that retraces the *whole* of the first would otherwise
        // leave nothing behind, and a zero-length leg is a missing route rather
        // than a short one — the drive monitor would be handed an empty chain.
        val previous = run(origin, 90.0, 500.0, 11)
        val next = previous.reversed()

        val trimmed = LegJoin.trimDoubleBack(previous, next)

        assertTrue(trimmed.previous.size >= 2, "the first leg must survive")
        assertTrue(trimmed.next.size >= 2, "the second leg must survive")
    }

    @Test
    fun `degenerate input is returned untouched`() {
        val line = run(origin, 90.0, 500.0, 4)
        assertTrue(!LegJoin.trimDoubleBack(emptyList(), line).changed)
        assertTrue(!LegJoin.trimDoubleBack(line, emptyList()).changed)
        assertTrue(!LegJoin.trimDoubleBack(listOf(origin), line).changed)
    }
}
