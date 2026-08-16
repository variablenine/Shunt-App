package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.destinationPoint
import btools.router.RoutingContext
import btools.router.RoutingParamCollector
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * A camera watching the destination must not cost the driver every other camera.
 *
 * Reported from real plans into Washington DC and San Francisco: the **last**
 * leg of a long trip routed straight through cameras. That is the only leg whose
 * end is the driver's real destination — every other one ends at a leg boundary
 * chosen precisely because nothing is watching it — which is exactly why it was
 * the last leg and only the last leg.
 *
 * The cause was that a route may not begin or end inside a zone the router has
 * been told is impassable (BRouter throws `last wpt in restricted area`), and
 * Shunt's answer to that was to skip the hard-block pass *entirely* and fall
 * back to weighted avoidance. Weighted avoidance is a different promise: BRouter
 * charges (metres inside the zone × weight), so a road clipping the edge of a
 * cone is cheap and gets taken. One unavoidable camera at the kerb bought a
 * dozen avoidable ones on the way in. The maintainer's reading was right: "I
 * have a hard time believing they're all unavoidable."
 *
 * Only the zones holding an endpoint come out now. Everything else stays
 * blocked, so the route is still camera-free wherever a camera-free road exists.
 */
class EndpointZoneTest {

    /** Neutral placeholder coordinates; every assertion here is about geometry. */
    private val destination = GeoPoint(39.0, -98.0)

    private val router = BrouterRouter(File("no-segments"), File("no-profiles"))

    /** Nogos as BRouter would receive them — radii are only filled in by prepare. */
    private fun blocked(vararg cameras: CameraVision) =
        router.buildNogos(cameras.toList(), Double.NaN, RoutingParamCollector())
            .also { RoutingContext.prepareNogoPoints(it) }

    @Test
    fun `the zone watching the destination is the only one dropped`() {
        val atDestination = CameraVision(destination, directionDegrees = null)
        // Far enough that no snap margin could reach it, and squarely on the way.
        val downTheRoad = CameraVision(destination.let { destinationPoint(it, 0.0, 5_000.0) }, null)
        val origin = destinationPoint(destination, 0.0, 40_000.0)

        val nogos = blocked(atDestination, downTheRoad)
        assertEquals(2, nogos.size, "the fixture must produce one zone per camera")

        val kept = router.withoutZonesHolding(nogos, listOf(origin, destination))

        assertEquals(
            1, kept.size,
            "exactly one zone holds an endpoint, so exactly one may be dropped",
        )
        // Identity rather than count: dropping the wrong one would also leave 1.
        assertTrue(
            kept.single() === nogos[1],
            "the camera five kilometres up the road is avoidable and must stay blocked",
        )
    }

    @Test
    fun `a camera watching the origin is dropped too`() {
        // A driver setting off from a watched driveway is the same problem at the
        // other end, and BRouter tolerates it silently rather than throwing — so
        // it would never have shown up as a failure, only as a route that could
        // not be found.
        val atOrigin = CameraVision(destination, directionDegrees = null)
        val far = destinationPoint(destination, 0.0, 40_000.0)

        val kept = router.withoutZonesHolding(blocked(atOrigin), listOf(destination, far))
        assertTrue(kept.isEmpty(), "a zone over the origin cannot be honoured either")
    }

    @Test
    fun `a zone near enough to catch the snapped waypoint is dropped`() {
        // BRouter does not test the coordinate handed to it. It snaps each
        // waypoint onto the nearest road first — up to waypointCatchingRange
        // away — and tests that instead. A margin narrower than the snap lets
        // the original failure straight back through on any destination that
        // isn't already on a road.
        //
        // The camera therefore sits *outside* its own zone's reach of the
        // destination, which is what stops this passing for the trivial reason
        // the other cases pass for.
        val zoneRadius = CameraVision.OMNI_RANGE_M + BrouterRouter.NOGO_MARGIN_METERS
        val offset = zoneRadius + 20.0
        assertTrue(
            offset > zoneRadius && offset < BrouterRouter.ENDPOINT_SNAP_MARGIN_METERS,
            "the fixture only tests the snap margin if it sits between the zone and the margin",
        )

        val justOff = CameraVision(destinationPoint(destination, 90.0, offset), directionDegrees = null)
        val origin = destinationPoint(destination, 0.0, 40_000.0)
        val nogos = blocked(justOff)

        // Stated rather than assumed: the destination is genuinely clear of this
        // zone, so only the ring around it can be what drops the zone.
        assertTrue(
            app.shunt.solver.geo.haversineMeters(destination, justOff.location) > nogos.single().radius,
            "the destination must be outside the zone for this to be about snapping at all",
        )

        assertTrue(
            router.withoutZonesHolding(nogos, listOf(origin, destination)).isEmpty(),
            "a zone within the snap radius of the destination can still hold the matched waypoint",
        )
    }

    @Test
    fun `weighted zones are never dropped`() {
        // Only an impassable zone can refuse a waypoint; a weighted one is a
        // price, and a route is perfectly free to start by paying it. Dropping
        // them would quietly weaken the balanced option for no reason at all.
        val atDestination = CameraVision(destination, directionDegrees = null)
        val weighted = router.buildNogos(listOf(atDestination), 500.0, RoutingParamCollector())
            .also { RoutingContext.prepareNogoPoints(it) }
        val origin = destinationPoint(destination, 0.0, 40_000.0)

        assertEquals(
            weighted,
            router.withoutZonesHolding(weighted, listOf(origin, destination)),
            "a weighted zone over the destination is a cost, not an obstruction",
        )
    }

    @Test
    fun `a trip clear of every camera keeps the whole list`() {
        // The overwhelmingly common case, and the one where a bug here would be
        // most expensive: silently dropping zones on an ordinary trip would turn
        // "fewest cameras" back into a suggestion.
        val cameras = (1..5).map {
            CameraVision(destinationPoint(destination, it * 60.0, 3_000.0 + it * 500), null)
        }
        val nogos = blocked(*cameras.toTypedArray())
        val origin = destinationPoint(destination, 0.0, 40_000.0)

        assertEquals(
            nogos,
            router.withoutZonesHolding(nogos, listOf(origin, destination)),
            "no zone holds an endpoint here, so the block must be handed over intact",
        )
    }

    @Test
    fun `the count shown to a driver never exceeds the cameras on the route`() {
        // Two numbers exist here and they are not the same: how many *zones*
        // came out of the block, and how many cameras the driver is being told
        // about. The first counts sites and reaches past them by the snap
        // margin, so reporting it would routinely claim more unavoidable
        // cameras than the route passes at all — which on this app reads as
        // avoidance having done nothing.
        //
        // A route that passes nothing must therefore report nothing, however
        // many zones were dropped to plan it.
        val atDestination = CameraVision(destination, directionDegrees = null)
        val origin = destinationPoint(destination, 0.0, 40_000.0)
        // A line that stops well short of the camera: dropped zone, clean route.
        val clear = listOf(origin, destinationPoint(destination, 0.0, 20_000.0))

        val nogos = blocked(atDestination)
        assertTrue(
            router.withoutZonesHolding(nogos, listOf(origin, destination)).isEmpty(),
            "the fixture must actually drop a zone, or this proves nothing",
        )
        assertEquals(
            0,
            CameraIndex(listOf(atDestination)).seeing(clear).size,
            "and the route must genuinely pass nothing",
        )
    }

    @Test
    fun `a directional camera facing away from the destination stays blocked`() {
        // The whole point of tracking facing is that a route may pass behind a
        // camera. One mounted at the destination but pointed away does not watch
        // it, is not what BRouter would refuse, and must keep constraining the
        // approach.
        val behind = destinationPoint(destination, 180.0, 40.0)
        val facingAway = CameraVision(behind, directionDegrees = 180.0)
        val origin = destinationPoint(destination, 0.0, 40_000.0)

        val nogos = blocked(facingAway)
        val kept = router.withoutZonesHolding(nogos, listOf(origin, destination))
        assertEquals(nogos, kept, "a camera looking the other way does not hold the destination")
    }
}
