package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.destinationPoint
import app.shunt.solver.geo.haversineMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * What leg planning **actually does**, across every regime the spine can land
 * in, written before anything was changed.
 *
 * Leg planning and the pending-road line were misbehaving in a way that varied
 * between trips, which is the signature of behaviour that depends on which
 * branch a trip happens to take rather than on anything about the trip. There
 * are four such branches and nothing was pinning any of them:
 *
 *  1. trip shorter than `SPINE_FULL_LIMIT_METERS` — the spine is the real road
 *     all the way to the destination
 *  2. trip longer than it — the spine stops at a probe point past the leg window
 *  3. the forced-probe retry — the full spine timed out, so the probe is used
 *  4. the straight-line fallback — no spine could be routed at all
 *
 * Neutral placeholder coordinates in the central US throughout, per CLAUDE.md §3.
 * No network: the routing engine is a lambda that returns a straight dense line.
 */
class LegPlanningCharacterizationTest {

    private val origin = GeoPoint(39.0, -98.0)

    private fun east(meters: Double) = destinationPoint(origin, 90.0, meters)

    /**
     * A dense straight polyline through [points], standing in for a road.
     *
     * Two kilometres between vertices, which is finer than
     * `SPINE_SAMPLE_METERS`, so `sampleSpine` has something real to sample and
     * the test is not accidentally measuring its own coarseness.
     */
    private fun road(points: List<GeoPoint>): List<GeoPoint> {
        val out = mutableListOf(points.first())
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val steps = (haversineMeters(a, b) / 2_000.0).toInt().coerceAtLeast(1)
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                out += GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
        }
        return out
    }

    private fun lengthOf(line: List<GeoPoint>): Int =
        (1 until line.size).sumOf { haversineMeters(line[it - 1], line[it]) }.toInt()

    /**
     * A planner whose engine always answers with the straight road.
     *
     * [failFirst] makes the very first call come back empty, which is how the
     * forced-probe retry is reached: the full spine ran out of time.
     */
    private fun planner(
        failFirst: Boolean = false,
        neverRoute: Boolean = false,
        asked: MutableList<List<GeoPoint>> = mutableListOf(),
    ) = BrouterPlanner(
        route = { (pts, _, _) ->
            asked += pts
            when {
                // Only the spine call. Failing the chooser too would be testing
                // "no route exists", which is a different thing entirely.
                neverRoute && asked.size == 1 -> emptyList()
                failFirst && asked.size == 1 -> emptyList()
                else -> {
                    val line = road(pts)
                    listOf(
                        BrouterRoute(
                            RouteChoice.FASTEST, line, lengthOf(line),
                            lengthOf(line) / 25, 0, 0,
                        ),
                    )
                }
            }
        },
        missingTiles = { emptyList() },
        camerasIn = { emptyList() },
    )

    private suspend fun planTo(
        destination: GeoPoint,
        failFirst: Boolean = false,
        neverRoute: Boolean = false,
    ): PlanOutcome.Routes {
        val outcome = planner(failFirst, neverRoute)
            .plan(listOf(origin, destination), maxLegMeters = LegSplitter.MAX_LEG_METERS)
        assertIs<PlanOutcome.Routes>(outcome)
        return outcome
    }

    /** How far the pending line stops short of where the trip is going. */
    private fun shortfall(outcome: PlanOutcome.Routes, destination: GeoPoint): Double =
        haversineMeters(outcome.directAhead.last(), destination)

    // ---- Regime 1: full spine (trip under SPINE_FULL_LIMIT_METERS) ----

    @Test
    fun `full spine - the pending line reaches the destination`() = runTest {
        val destination = east(800_000.0)
        val outcome = planTo(destination)

        assertTrue(outcome.isPartial, "an 800 km trip must be cut into legs")
        assertTrue(outcome.directAhead.isNotEmpty(), "and must describe the road ahead")
        assertTrue(
            shortfall(outcome, destination) < 10_000.0,
            "the pending line must reach the destination, not stop short of it by " +
                "${shortfall(outcome, destination).toInt() / 1000} km",
        )
    }

    @Test
    fun `full spine - remaining starts where this leg ends`() = runTest {
        val destination = east(800_000.0)
        val outcome = planTo(destination)

        val legEnd = outcome.options.first().polyline.last()
        assertTrue(
            haversineMeters(legEnd, outcome.remaining.first()) < 1_000.0,
            "the next leg must start where this one stops",
        )
        assertEquals(destination, outcome.remaining.last(), "and must still end at the destination")
    }

    @Test
    fun `full spine - the whole-trip distance describes the whole trip`() = runTest {
        val destination = east(800_000.0)
        val outcome = planTo(destination)
        val whole = outcome.wholeTripMeters

        assertTrue(whole != null, "a split trip must report how long the whole thing is")
        assertTrue(
            whole!! >= outcome.options.first().distanceMeters,
            "the whole trip cannot be shorter than its first leg: $whole vs " +
                outcome.options.first().distanceMeters,
        )
        assertTrue(
            whole in 750_000..1_000_000,
            "and must be about the real 800 km rather than a guess: $whole",
        )
    }

    // ---- Regime 2: probe spine (trip over SPINE_FULL_LIMIT_METERS) ----

    @Test
    fun `probe spine - the pending line reaches the destination`() = runTest {
        // The hypothesis under test. The probe stops the spine just past the leg
        // window, and directAhead is a slice of that spine — so if nothing
        // extends it, the pending line covers a fraction of a very long trip and
        // simply stops in open country.
        val destination = east(2_000_000.0)
        val outcome = planTo(destination)

        assertTrue(outcome.isPartial, "a 2,000 km trip must be cut into legs")
        assertTrue(outcome.directAhead.isNotEmpty(), "and must describe the road ahead")
        assertTrue(
            shortfall(outcome, destination) < 10_000.0,
            "the pending line must reach the destination, not stop " +
                "${shortfall(outcome, destination).toInt() / 1000} km short of it",
        )
    }

    @Test
    fun `probe spine - remaining starts where this leg ends`() = runTest {
        val destination = east(2_000_000.0)
        val outcome = planTo(destination)

        val legEnd = outcome.options.first().polyline.last()
        assertTrue(
            haversineMeters(legEnd, outcome.remaining.first()) < 1_000.0,
            "the next leg must start where this one stops",
        )
        assertEquals(destination, outcome.remaining.last(), "and must still end at the destination")
    }

    @Test
    fun `probe spine - the whole-trip distance describes the whole trip`() = runTest {
        val destination = east(2_000_000.0)
        val outcome = planTo(destination)
        val whole = outcome.wholeTripMeters

        assertTrue(whole != null, "a split trip must report how long the whole thing is")
        assertTrue(
            whole!! > 1_500_000,
            "the whole trip is 2,000 km and must not be reported as a fraction of it: $whole",
        )
    }

    // ---- Regime 3: the forced-probe retry ----

    @Test
    fun `forced probe - the pending line reaches the destination`() = runTest {
        // The full spine timed out and the probe was used instead. Same trip
        // length as regime 1, different branch — which is exactly the kind of
        // difference that makes a symptom look inconsistent between trips.
        val destination = east(800_000.0)
        val outcome = planTo(destination, failFirst = true)

        assertTrue(outcome.isPartial, "the trip must still be cut into legs")
        assertTrue(
            shortfall(outcome, destination) < 10_000.0,
            "the pending line must reach the destination, not stop " +
                "${shortfall(outcome, destination).toInt() / 1000} km short of it",
        )
    }

    @Test
    fun `forced probe - remaining starts where this leg ends`() = runTest {
        val destination = east(800_000.0)
        val outcome = planTo(destination, failFirst = true)

        val legEnd = outcome.options.first().polyline.last()
        assertTrue(
            haversineMeters(legEnd, outcome.remaining.first()) < 1_000.0,
            "the next leg must start where this one stops",
        )
    }

    // ---- Regime 4: the straight-line fallback ----

    @Test
    fun `straight-line fallback - the trip is still cut and still reaches the destination`() = runTest {
        // No spine could be routed at all, but the roads are there — only the
        // spine pass came back empty. The trip that most needs splitting is the
        // one whose spine failed, so it must still be split, and the pending
        // line still has to describe the whole way.
        //
        // 2,000 km so the probe branch is taken and there is no forced retry;
        // one failed call is therefore the whole spine phase.
        val destination = east(2_000_000.0)
        val outcome = planTo(destination, neverRoute = true)

        assertTrue(outcome.isPartial, "a trip whose spine failed must still be cut")
        assertTrue(
            shortfall(outcome, destination) < 10_000.0,
            "the pending line must reach the destination, not stop " +
                "${shortfall(outcome, destination).toInt() / 1000} km short of it",
        )
    }

    // ---- Shared invariants, stated once ----

    @Test
    fun `the pending line never doubles back past the leg boundary`() = runTest {
        // It describes what has NOT been planned. A point behind the boundary is
        // road the first leg already covers, and drawing it would put the dashed
        // line back over the solid one.
        val destination = east(800_000.0)
        val outcome = planTo(destination)
        val boundary = outcome.remaining.first()

        val behind = outcome.directAhead.filter {
            haversineMeters(it, destination) > haversineMeters(boundary, destination) + 5_000.0
        }
        assertTrue(behind.isEmpty(), "${behind.size} pending points lie behind the leg boundary")
    }

    @Test
    fun `an unsplit trip has nothing pending`() = runTest {
        // Short enough to plan whole: no boundary, no remainder, and therefore
        // nothing for the map to draw as not-yet-planned.
        val destination = east(100_000.0)
        val outcome = planTo(destination)

        assertTrue(!outcome.isPartial, "a 100 km trip is not split")
        assertTrue(outcome.directAhead.isEmpty(), "so there is no pending road")
        assertTrue(outcome.wholeTripMeters == null, "and no separate whole-trip figure")
    }

    @Test
    fun `the pending line is sampled at the spine's own spacing`() {
        // Step 3 of the investigation, pinned rather than left to inspection.
        // directAhead is a slice of the sampled spine, so its resolution is
        // SPINE_SAMPLE_METERS — 5 km. That is what makes it cut corners across
        // a winding road, and it is why this is an overview line rather than
        // something to draw at street zoom.
        val destination = east(2_000_000.0)
        val outcome = kotlinx.coroutines.runBlocking { planTo(destination) }

        val gaps = (1 until outcome.directAhead.size)
            .map { haversineMeters(outcome.directAhead[it - 1], outcome.directAhead[it]) }
        assertTrue(gaps.isNotEmpty(), "the pending line must have segments to measure")
        // Generous either side: the sampler emits a final point at whatever is
        // left over, and the straight tail is sampled the same way.
        assertTrue(
            gaps.count { it > 8_000.0 } <= 2,
            "no leg of the pending line should be far over the 5 km sampling: " +
                gaps.filter { it > 8_000.0 }.map { it.toInt() },
        )
    }
}
