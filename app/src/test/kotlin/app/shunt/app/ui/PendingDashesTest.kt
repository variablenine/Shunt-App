package app.shunt.app.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The marching ants on the pending line have to walk, not twitch.
 *
 * MapLibre has no dash offset to animate, so movement is faked by cycling
 * dash patterns whose solid run sits half a line-width further along each time
 * — see [PENDING_DASHES]. That only reads as movement if the cycle is
 * *continuous*: same period throughout, same solid length, and the last step
 * handing back to the first with the same half-width step as every other.
 *
 * Held by a test because the sequence looks like seven interchangeable rows of
 * numbers and is not. It shipped with the second half missing, which snapped
 * the pattern back 3.5 widths every 630 ms — reported simply as the animation
 * being "jumpy and weird", which is all a driver can be expected to say about
 * it.
 */
class PendingDashesTest {

    private val tolerance = 1e-6

    /** Where the solid run starts within one period, and how long it is. */
    private fun solidRun(pattern: Array<Float>): Pair<Double, Double> {
        val period = pattern.sum().toDouble()
        val runs = mutableListOf<Pair<Double, Double>>()
        var at = 0.0
        pattern.forEachIndexed { i, length ->
            // Entries alternate dash, gap, dash, gap … starting with a dash.
            if (i % 2 == 0 && length > 0f) runs += at to at + length
            at += length
        }
        // A run split across the period boundary is one run, not two.
        if (runs.size == 2 && abs(runs.first().first) < tolerance &&
            abs(runs.last().second - period) < tolerance
        ) {
            val wrapped = runs.last()
            return wrapped.first to (wrapped.second - wrapped.first) + runs.first().second
        }
        assertEquals(1, runs.size, "expected one solid run per period, got $runs in ${pattern.toList()}")
        return runs.single().first to (runs.single().second - runs.single().first)
    }

    @Test
    fun `every step has the same period and the same solid length`() {
        val periods = PENDING_DASHES.map { it.sum().toDouble() }
        assertTrue(
            periods.all { abs(it - periods.first()) < tolerance },
            "patterns must share one period, got $periods",
        )
        val lengths = PENDING_DASHES.map { solidRun(it).second }
        assertTrue(
            lengths.all { abs(it - lengths.first()) < tolerance },
            "the solid run must not change length, got $lengths",
        )
    }

    @Test
    fun `the solid run advances one step every frame, and wraps cleanly`() {
        val period = PENDING_DASHES.first().sum().toDouble()
        // Round the trip: comparing each step with the one before it, all the way
        // back to the start. The wrap is the case that was broken.
        for (i in PENDING_DASHES.indices) {
            val from = solidRun(PENDING_DASHES[i]).first
            val to = solidRun(PENDING_DASHES[(i + 1) % PENDING_DASHES.size]).first
            val step = ((to - from) % period + period) % period
            assertEquals(
                PENDING_DASH_STEP.toDouble(), step, tolerance,
                "step ${i + 1} → ${(i + 1) % PENDING_DASHES.size + 1} moves $step, not $PENDING_DASH_STEP",
            )
        }
    }
}
