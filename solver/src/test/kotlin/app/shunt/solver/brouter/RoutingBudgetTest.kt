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
    fun `the fewest-cameras pass runs before the balanced one`() {
        // Observed on a real trip into dense metro: "balanced" ran first, spent
        // the entire budget, timed out with nothing, and the fewest-cameras pass
        // — the reason anyone installed this — was never attempted. The driver
        // was left with the plain fastest road through fifty-one cameras.
        //
        // Balanced is a convenience. Fewest cameras is the product. When the
        // budget binds, the product wins.
        var now = 0L
        val subject = router({ now += 1; now }, budget = 75_000)

        subject.route(trip, cameras)

        val order = subject.lastPassTimings.map { it.label.substringBefore(" (") }
        assertTrue(
            order.indexOf("blocked") < order.indexOf("balanced"),
            "fewest-cameras must be attempted before balanced: $order",
        )
    }

    @Test
    fun `the hard-block pass cannot spend the whole budget`() {
        // A hard block that finds nothing is the most expensive outcome there
        // is — it exhausts every reachable road before concluding — and the
        // weighted fallback is what rescues that case. If the block were allowed
        // everything, the rescue would never run.
        assertTrue(
            BrouterRouter.BLOCKED_BUDGET_SHARE < 1.0,
            "the block must leave room for the fallback that covers its failure",
        )
        assertTrue(
            BrouterRouter.BLOCKED_BUDGET_SHARE > 0.0,
            "...while still getting a real attempt",
        )
    }

    @Test
    fun `a camera on the destination no longer cancels the hard block`() {
        // This used to skip the pass outright, on the reasoning that a route may
        // not end inside a zone the router has been told is impassable — true of
        // *that* zone, and true of nothing else. In a city centre the
        // destination is very often watched, so the driver was handed the
        // weighted fallback and every avoidable camera on the way in, which is
        // the "last leg routes straight through cameras" report from Washington
        // DC and San Francisco.
        //
        // The zone over the destination comes out; the block still runs. See
        // BrouterRouter.withoutZonesHolding and EndpointZoneTest.
        var now = 0L
        val camerasOnDestination = listOf(CameraVision(trip.last(), directionDegrees = null))
        val subject = router({ now += 1; now }, budget = 75_000)

        subject.route(RouteRequest(trip, camerasOnDestination))

        val labels = subject.lastPassTimings.map { it.label }
        val blockedLine = labels.single { it.startsWith("blocked") }
        assertTrue(
            "skipped" !in blockedLine,
            "the block must be attempted even with the destination watched: $labels",
        )
        // And it must say how many cameras it could not block, because that is
        // the difference between "one camera watches your destination" and
        // "avoidance did nothing".
        assertTrue(
            "unblockable" in blockedLine,
            "the block must name the cameras it could not honour: $blockedLine",
        )
    }

    @Test
    fun `a clear endpoint reports nothing unblockable`() {
        // The common case, and the one where a spurious count would be worst: a
        // camera nowhere near either end is perfectly avoidable and must not be
        // reported to the driver as something no route could dodge.
        var now = 0L
        val farAway = listOf(CameraVision(app.shunt.core.GeoPoint(20.0, -100.0), directionDegrees = null))
        val subject = router({ now += 1; now }, budget = 75_000)

        subject.route(RouteRequest(trip, farAway))

        assertTrue(
            subject.lastPassTimings.any { it.label.startsWith("blocked") && "unblockable" !in it.label },
            "the block must be attempted, clean: ${subject.lastPassTimings.map { it.label }}",
        )
    }

    @Test
    fun `a leg planned while driving gets far longer than one planned at the kerb`() {
        // These bound completely different things. The kerbside figure is how
        // long a driver will watch a spinner; a later leg is computed in the
        // background with LegSplitter.MIN_LEG_METERS of road still ahead of the
        // car, which is an hour at motorway speed. Giving later legs the
        // patience figure is how a long trip's last leg came back as the plain
        // fastest road.
        assertTrue(
            BrouterRouter.LEG_PASS_BUDGET_MILLIS > BrouterRouter.PASS_BUDGET_MILLIS,
            "a leg nobody is waiting on must not be held to a waiting driver's patience",
        )
        // The deadline it actually has to beat, stated rather than assumed.
        val secondsOfRoadAhead = LegSplitter.MIN_LEG_METERS / (120_000.0 / 3600.0)
        assertTrue(
            BrouterRouter.LEG_PASS_BUDGET_MILLIS / 1000.0 < secondsOfRoadAhead / 2,
            "a leg must still plan in comfortably less than the driving time before it is needed",
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
