package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.destinationPoint
import app.shunt.solver.geo.haversineMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // ---- Handing a leg's tail to the next leg ----------------------------
    //
    // The alternative to repairing a boundary is not creating one. A leg starts
    // a little way *inside* the leg before it and that one is cut back to meet
    // it, so the join is a vertex of both lines by construction — there is
    // nothing to trim, no proximity threshold and no third routing pass.

    @Test
    fun `the handover sits a handover's length back from the end`() {
        val leg = run(origin, 90.0, 1_000.0, 121) // 120 km east, 1 km apart
        val handover = assertNotNull(LegJoin.handoverInto(leg))

        val fromEnd = lengthOf(leg.subList(handover.index, leg.size))
        assertTrue(
            fromEnd >= LegJoin.HANDOVER_METERS && fromEnd < LegJoin.HANDOVER_METERS + 1_500,
            "the next leg should take over ${LegJoin.HANDOVER_METERS} m back, not $fromEnd m",
        )
        assertEquals(leg[handover.index], handover.point, "the handover must be a vertex of the leg")
    }

    @Test
    fun `truncating meets the handover exactly, so there is nothing to repair`() {
        val leg = run(origin, 90.0, 1_000.0, 121)
        val handover = assertNotNull(LegJoin.handoverInto(leg))
        val kept = LegJoin.truncateAt(leg, handover)

        assertEquals(
            handover.point,
            kept.last(),
            "the published leg must end exactly where the next one is planned from",
        )
        assertTrue(kept.size >= 2, "what is kept must still be a line")
        assertTrue(
            kept.all { it in leg },
            "the kept part must be a prefix of what was planned, not a new route",
        )
    }

    @Test
    fun `the handover carries the bearing the leg arrives on`() {
        // Due north for the last stretch, so the next leg is planned setting off
        // northward rather than free to turn back down the road just driven —
        // which is what produced the loop around a camera at a boundary.
        val leg = run(origin, 90.0, 1_000.0, 60) + run(destinationPoint(origin, 90.0, 59_000.0), 0.0, 1_000.0, 60)
        val handover = assertNotNull(LegJoin.handoverInto(leg))

        assertTrue(
            handover.bearingDegrees < 5.0 || handover.bearingDegrees > 355.0,
            "expected roughly north, got ${handover.bearingDegrees}",
        )
    }

    @Test
    fun `a leg with nothing to spare hands over nothing`() {
        // Shorter than the handover plus what must remain: cutting it back would
        // move the boundary somewhere nobody chose, which is the failure handing
        // over exists to prevent. Null means this boundary keeps the old
        // behaviour, and the trim is still there for it.
        val tooShort = run(origin, 90.0, 1_000.0, 21) // 20 km
        assertNull(LegJoin.handoverInto(tooShort))

        val barelyLong = run(origin, 90.0, 1_000.0, 31) // 30 km, under 15 + 20
        assertNull(LegJoin.handoverInto(barelyLong))
    }

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

    @Test
    fun `the seam window is a real stretch either side of the boundary`() {
        // The window has to reach back into the first leg and forward into the
        // second, because a boundary that bends the route bends it on both
        // sides — re-planning only one of them leaves half the C in place.
        val previous = run(origin, 90.0, 1_000.0, 40) // 39 km east
        val next = run(previous.last(), 0.0, 1_000.0, 40) // 39 km north

        val seam = LegJoin.seamOf(previous, next)

        assertTrue(seam != null, "a 39 km leg is long enough to give up a window")
        assertTrue(seam!!.fromIndex in 1 until previous.lastIndex, "the window must start inside the first leg")
        assertTrue(seam.toIndex in 1 until next.lastIndex, "and end inside the second")
        assertTrue(seam.currentMeters > 0.0, "and describe road that is currently driven")
    }

    @Test
    fun `a leg too short to give up a window is left alone`() {
        // A leg that is mostly seam has nothing left to be a leg, and this must
        // never quietly turn into re-planning the whole thing.
        val previous = run(origin, 90.0, 200.0, 5) // 800 m
        val next = run(previous.last(), 0.0, 200.0, 5)

        assertTrue(LegJoin.seamOf(previous, next) == null)
    }

    @Test
    fun `splicing a shorter path through the seam joins both legs cleanly`() {
        // The C: out east, then north. A straight diagonal between the window
        // ends is shorter, which is exactly the shape a boundary produces and
        // that trimDoubleBack cannot touch — no road is driven twice.
        val previous = run(origin, 90.0, 1_000.0, 40)
        val next = run(previous.last(), 0.0, 1_000.0, 40)
        val seam = LegJoin.seamOf(previous, next)!!
        // The direct line between the window ends, sampled.
        val direct = (0..20).map { i ->
            GeoPoint(
                seam.from.lat + (seam.to.lat - seam.from.lat) * i / 20.0,
                seam.from.lon + (seam.to.lon - seam.from.lon) * i / 20.0,
            )
        }

        val spliced = LegJoin.spliceSeam(previous, next, seam, direct)

        assertTrue(spliced != null, "a shorter path through the seam must be taken")
        assertTrue(spliced!!.savedMeters > 0, "and must report what it saved")
        // The two legs must still meet, or the drive has a hole in it.
        assertTrue(
            haversineMeters(spliced.previous.last(), spliced.next.first()) < 1.0,
            "the spliced legs must join exactly",
        )
        // Road outside the window is untouched.
        assertEquals(previous.first(), spliced.previous.first())
        assertEquals(next.last(), spliced.next.last())
        assertTrue(
            lengthOf(spliced.previous) + lengthOf(spliced.next) <
                lengthOf(previous) + lengthOf(next),
            "and the whole thing must get shorter",
        )
    }

    @Test
    fun `a seam replacement that saves nothing is refused`() {
        // Two legs already going the same way have a straight seam, so a
        // re-plan finds the same road back. Replacing it would be churn, and
        // would move the boundary for no gain.
        val previous = run(origin, 90.0, 1_000.0, 40)
        val next = run(previous.last(), 90.0, 1_000.0, 40)
        val seam = LegJoin.seamOf(previous, next)!!
        val same = previous.subList(seam.fromIndex, previous.size) + next.subList(0, seam.toIndex + 1)

        assertTrue(LegJoin.spliceSeam(previous, next, seam, same) == null)
    }
}
