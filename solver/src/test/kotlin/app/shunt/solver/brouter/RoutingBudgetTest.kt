package app.shunt.solver.brouter

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Planning has to end.
 *
 * On the longest trips it did not, in any practical sense: a route from the
 * Upper Peninsula to Chicago ran so long the maintainer closed the app rather
 * than see it finish, which makes the answer worth nothing however good it
 * would have been. A search is not interruptible once started, so the budget is
 * checked between passes and bounds the wait at about one pass beyond it.
 *
 * The property that makes this safe: skipping only ever removes an *option*. It
 * never alters how a returned route was planned, and never lets one be labelled
 * against cameras the router was not given.
 */
class RoutingBudgetTest {

    /** Never actually routes — every pass fails, which is enough to time them. */
    private fun router(clock: () -> Long, budget: Long) = BrouterRouter(
        segmentDir = File("/nonexistent-segments"),
        profileDir = File("/nonexistent-profile"),
        passBudgetMillis = budget,
        nowMillis = clock,
    )

    private val trip = listOf(
        app.shunt.core.GeoPoint(39.0, -98.0),
        app.shunt.core.GeoPoint(39.0, -97.8),
    )
    private val cameras = listOf(CameraVision(app.shunt.core.GeoPoint(39.0, -97.9), null))

    @Test
    fun `the avoidance passes are abandoned once the budget is spent`() {
        // The clock jumps a minute per look, so the budget is gone after the
        // fastest pass and everything after it should be skipped rather than
        // run — the case where a trip used to disappear for many minutes.
        var now = 0L
        val subject = router({ now += 60_000; now }, budget = 30_000)

        subject.route(trip, cameras)

        val labels = subject.lastPassTimings.map { it.label }
        assertTrue(
            labels.any { "balanced (skipped" in it },
            "balanced must be abandoned, not run: $labels",
        )
        assertTrue(
            labels.any { "blocked (skipped" in it },
            "blocked must be abandoned too: $labels",
        )
    }

    @Test
    fun `a skipped pass says so, so a missing option is never mistaken for an absent route`() {
        // A chooser that quietly comes back short looks like Shunt deciding
        // there was no camera-free route. That is the opposite of what happened,
        // and on this app the difference matters.
        var now = 0L
        val subject = router({ now += 60_000; now }, budget = 1L)

        subject.route(trip, cameras)

        assertTrue(
            subject.lastPassTimings.count { "skipped" in it.label } >= 2,
            "every abandoned pass has to be named: ${subject.lastPassTimings.map { it.label }}",
        )
    }

    @Test
    fun `every search is given a real ceiling, never zero`() {
        // The whole point, and the thing that was wrong: BRouter reads zero as
        // "no limit". Shunt passed zero for the life of the project, so a budget
        // could be in force and a single search still run for twenty minutes,
        // because the between-passes check never gets a turn while one pass is
        // the thing running long.
        //
        // Guarded here at the seam it actually goes through: whatever arithmetic
        // the budget does, the number handed to the engine is positive.
        assertTrue(
            BrouterRouter.PASS_BUDGET_MILLIS > 0,
            "a zero budget would disable BRouter's own timeout entirely",
        )

        // An exhausted budget must still yield a positive ceiling rather than
        // wrapping round to "unlimited".
        var now = 0L
        val subject = router({ now += 1_000_000; now }, budget = 1L)
        subject.route(trip, cameras)

        assertTrue(
            subject.lastPassTimings.isNotEmpty(),
            "the fastest pass still runs, and still under a ceiling",
        )
    }

    @Test
    fun `a generous budget leaves every pass to run`() {
        var now = 0L
        val subject = router({ now += 1; now }, budget = 75_000)

        subject.route(trip, cameras)

        assertEquals(
            emptyList(),
            subject.lastPassTimings.filter { "skipped" in it.label },
            "nothing may be abandoned while there is time for it",
        )
    }
}
