package app.shunt.app.drive

import app.shunt.app.plan.Destination
import app.shunt.app.plan.DrivePlan
import app.shunt.core.GeoPoint
import app.shunt.tesla.ActiveRoute
import app.shunt.tesla.FakeVehicleNavClient
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChargeStopCoordinatorTest {

    private val here = GeoPoint(39.0, -98.6)
    private val destination = Destination("Destination", GeoPoint(39.0, -98.0))
    private val charger = GeoPoint(39.3, -98.4)
    private val otherCharger = GeoPoint(39.5, -98.2)

    private fun navigatingTo(point: GeoPoint, name: String = "Supercharger") = ActiveRoute(
        destinationName = name,
        latitude = point.lat,
        longitude = point.lon,
        milesToArrival = null,
        minutesToArrival = null,
        energyAtArrival = null,
        batteryLevel = null,
        estimatedRangeMiles = null,
    )

    private fun planFor(to: Destination) = DrivePlan(
        destination = to,
        chain = listOf(to.location),
        cameras = emptyList(),
        polyline = listOf(here, to.location),
    )

    /** Time the coordinator sees, advanced by hand so probes can be forced due. */
    private class Clock(var now: Long = 100_000)

    private fun coordinator(
        vehicle: FakeVehicleNavClient,
        clock: Clock = Clock(),
        reads: MutableList<ActiveRoute?>,
        steering: Boolean = false,
        planLeg: suspend (GeoPoint, List<GeoPoint>, Destination, Double?) -> DrivePlan? =
            { _, _, to, _ -> planFor(to) },
    ) = ChargeStopCoordinator(
        vehicle = vehicle,
        readActiveRoute = { reads.removeFirstOrNull() },
        planLeg = planLeg,
        steering = steering,
        // No real waiting: the settle delay is virtual under runTest anyway,
        // but skipping it keeps the tests about the decisions.
        pause = {},
        nowMillis = { clock.now },
    )

    @Test
    fun `a car heading for the destination leaves the trip alone`() = runTest {
        val vehicle = FakeVehicleNavClient()
        val reads = mutableListOf<ActiveRoute?>(navigatingTo(destination.location))

        val change = coordinator(vehicle, reads = reads)
            .check(here, destination, emptyList(), emptyList())

        assertEquals(LegChange.None, change)
        assertTrue(
            vehicle.calls().isEmpty(),
            "the car already holds the destination, so asking must cost nothing",
        )
    }

    @Test
    fun `a charging stop the car inserted becomes a planned leg`() = runTest {
        val reads = mutableListOf<ActiveRoute?>(navigatingTo(charger, "Supercharger Anytown"))
        val subject = coordinator(FakeVehicleNavClient(), reads = reads)

        val change = assertIs<LegChange.ToChargeStop>(subject.check(here, destination, emptyList(), emptyList()))

        assertEquals("Supercharger Anytown", change.stop.name)
        assertEquals(charger, change.plan.destination.location)
        assertEquals(change.stop, subject.chargeStopUnderWay())
    }

    @Test
    fun `a charging stop we cannot route to is reported, not silently accepted`() = runTest {
        val reads = mutableListOf<ActiveRoute?>(navigatingTo(charger))
        val subject = coordinator(FakeVehicleNavClient(), reads = reads, planLeg = { _, _, _, _ -> null })

        val change = assertIs<LegChange.Unroutable>(subject.check(here, destination, emptyList(), emptyList()))

        assertEquals(charger, change.stop.at)
        assertEquals(null, subject.chargeStopUnderWay(), "an unroutable stop is not a leg in force")
    }

    @Test
    fun `an unreadable car never starts or ends a charging leg`() = runTest {
        val subject = coordinator(FakeVehicleNavClient(), reads = mutableListOf(null))
        assertEquals(LegChange.None, subject.check(here, destination, emptyList(), emptyList()))
        assertEquals(null, subject.chargeStopUnderWay())
    }

    // ---- Re-asserting the destination mid-drive --------------------------

    private val steering = listOf(GeoPoint(39.1, -98.5), charger)

    @Test
    fun `re-asserting sends the final destination and puts steering back`() = runTest {
        val vehicle = FakeVehicleNavClient()
        val reads = mutableListOf<ActiveRoute?>(
            navigatingTo(charger), // departure: charging stop found
            navigatingTo(charger), // re-assert: same charger, nothing changed
        )
        val subject = coordinator(vehicle, reads = reads)
        subject.check(here, destination, emptyList(), emptyList())

        val change = subject.check(here, destination, emptyList(), steering)

        assertEquals(LegChange.None, change)
        val calls = vehicle.calls()
        assertEquals(
            listOf(destination.location),
            assertIs<FakeVehicleNavClient.Call.PushRoute>(calls.first()).waypoints,
            "the probe hands the car the FINAL destination — that's what makes the read meaningful",
        )
        assertEquals(
            steering,
            assertIs<FakeVehicleNavClient.Call.AdvanceTo>(calls.last()).waypoints,
            "and steering must never be left pointing at the destination afterwards",
        )
    }

    @Test
    fun `a car that swapped chargers gets the new leg planned`() = runTest {
        val reads = mutableListOf<ActiveRoute?>(
            navigatingTo(charger, "Supercharger One"),
            navigatingTo(otherCharger, "Supercharger Two"),
        )
        val subject = coordinator(FakeVehicleNavClient(), reads = reads)
        subject.check(here, destination, emptyList(), emptyList())

        val change = assertIs<LegChange.ToChargeStop>(
            subject.check(here, destination, emptyList(), steering),
        )

        assertEquals("Supercharger Two", change.stop.name)
        assertEquals(otherCharger, subject.chargeStopUnderWay()?.at)
    }

    @Test
    fun `a car that no longer needs to charge gets routed on to the destination`() = runTest {
        val reads = mutableListOf<ActiveRoute?>(
            navigatingTo(charger),
            navigatingTo(destination.location),
        )
        val subject = coordinator(FakeVehicleNavClient(), reads = reads)
        subject.check(here, destination, emptyList(), emptyList())

        val change = assertIs<LegChange.ToDestination>(
            subject.check(here, destination, emptyList(), steering),
        )

        assertEquals(destination.location, change.plan.destination.location)
        assertEquals(null, subject.chargeStopUnderWay())
    }

    @Test
    fun `the driver's remaining stops survive the charging detour`() = runTest {
        val stop = GeoPoint(39.05, -98.2)
        var sawVia: List<GeoPoint>? = null
        val reads = mutableListOf<ActiveRoute?>(
            navigatingTo(charger),
            navigatingTo(destination.location),
        )
        val subject = coordinator(
            FakeVehicleNavClient(),
            reads = reads,
            planLeg = { _, via, to, _ -> sawVia = via; planFor(to) },
        )
        subject.check(here, destination, emptyList(), emptyList())

        subject.check(here, destination, remainingStops = listOf(stop), steeringChain = steering)

        assertEquals(listOf(stop), sawVia, "a stop the driver asked for must not be dropped")
    }

    @Test
    fun `an unreadable probe restores steering and changes nothing`() = runTest {
        val vehicle = FakeVehicleNavClient()
        val reads = mutableListOf<ActiveRoute?>(navigatingTo(charger), null)
        val subject = coordinator(vehicle, reads = reads)
        subject.check(here, destination, emptyList(), emptyList())

        val change = subject.check(here, destination, emptyList(), steering)

        assertEquals(LegChange.None, change)
        assertEquals(charger, subject.chargeStopUnderWay()?.at, "the leg in force must survive a failed read")
        assertEquals(
            steering,
            assertIs<FakeVehicleNavClient.Call.AdvanceTo>(vehicle.calls().last()).waypoints,
        )
    }

    @Test
    fun `a car still naming the waypoint we were steering to is not a new charger`() = runTest {
        // The probe push hasn't landed yet, so the read describes the OLD trip.
        // Taking it at face value would plan a leg to our own shaping pin.
        val reads = mutableListOf<ActiveRoute?>(
            navigatingTo(charger),
            navigatingTo(steering.first(), "our own pin"),
        )
        val subject = coordinator(FakeVehicleNavClient(), reads = reads)
        subject.check(here, destination, emptyList(), emptyList())

        assertEquals(LegChange.None, subject.check(here, destination, emptyList(), steering))
        assertEquals(charger, subject.chargeStopUnderWay()?.at)
    }

    @Test
    fun `a failed re-assert does not read a stale route`() = runTest {
        // The first vehicle call is the re-assert on the second check; fail it.
        val vehicle = FakeVehicleNavClient(FakeVehicleNavClient.Behavior(failOnCalls = setOf(1)))
        val reads = mutableListOf<ActiveRoute?>(
            navigatingTo(charger),
            // If the read happened anyway it would see the destination and
            // wrongly conclude the car had dropped the charging stop.
            navigatingTo(destination.location),
        )
        val subject = coordinator(vehicle, reads = reads)
        subject.check(here, destination, emptyList(), emptyList())

        val failed = assertIs<LegChange.VehicleUpdateFailed>(
            subject.check(here, destination, emptyList(), steering),
        )
        assertTrue(failed.retryable)
        assertEquals(1, reads.size, "the destination never landed, so nothing should have been read")
        assertEquals(charger, subject.chargeStopUnderWay()?.at)
    }

    @Test
    fun `a failed steering restore is surfaced instead of claiming nothing changed`() = runTest {
        val vehicle = FakeVehicleNavClient(FakeVehicleNavClient.Behavior(failOnCalls = setOf(2)))
        val reads = mutableListOf<ActiveRoute?>(navigatingTo(charger), null)
        val subject = coordinator(vehicle, reads = reads)
        subject.check(here, destination, emptyList(), emptyList())

        val failed = assertIs<LegChange.VehicleUpdateFailed>(
            subject.check(here, destination, emptyList(), steering),
        )
        assertTrue(failed.retryable)
        assertEquals(charger, subject.chargeStopUnderWay()?.at)
    }

    // ---- Check timing ----------------------------------------------------

    @Test
    fun `a parked car is never asked what it plans`() = runTest {
        // A Tesla works out its charging stops when it is put into drive, not
        // when a destination is shared, so asking before then is pure noise.
        val clock = Clock()
        val subject = coordinator(
            FakeVehicleNavClient(),
            clock = clock,
            reads = mutableListOf(navigatingTo(charger)),
        )
        clock.now += ProbeWindow().readIntervalMillis * 10

        assertEquals(false, subject.isCheckDue(moving = false, 9_000.0, 9_000.0, offRoute = false))
        assertTrue(subject.isCheckDue(moving = true, 9_000.0, 9_000.0, offRoute = false))
    }

    @Test
    fun `while the car holds the destination, checks are frequent and free`() = runTest {
        val clock = Clock()
        val vehicle = FakeVehicleNavClient()
        val window = ProbeWindow()
        val subject = coordinator(vehicle, clock = clock, reads = mutableListOf(navigatingTo(destination.location)))

        clock.now += window.readIntervalMillis
        assertTrue(
            subject.isCheckDue(moving = true, metersToNextWaypoint = 200.0, metersToNearestCamera = 9_000.0, offRoute = false),
            "nothing is being redirected, so an imminent waypoint is no reason to wait",
        )
        assertEquals(
            false,
            subject.isCheckDue(moving = true, metersToNextWaypoint = 9_000.0, metersToNearestCamera = 200.0, offRoute = false),
            "a close camera still outranks it — the fix stream must not stall there",
        )

        subject.check(here, destination, emptyList(), listOf(destination.location))
        assertTrue(vehicle.calls().isEmpty(), "a free check must send the car nothing at all")
    }

    @Test
    fun `re-asserts are rationed on the driving interval, and sooner once parked`() = runTest {
        val clock = Clock()
        val reads = mutableListOf<ActiveRoute?>(navigatingTo(charger))
        val subject = coordinator(FakeVehicleNavClient(), clock = clock, reads = reads)
        val window = ProbeWindow()
        subject.check(here, destination, emptyList(), emptyList())

        clock.now += window.parkedIntervalMillis
        assertEquals(
            false,
            subject.isCheckDue(moving = true, 9_000.0, 9_000.0, offRoute = false),
            "a minute is not long enough while under way",
        )

        clock.now += window.minIntervalMillis
        assertTrue(subject.isCheckDue(moving = true, 9_000.0, 9_000.0, offRoute = false))

        // Reaching the charger flips to the parked cadence and asks straight away.
        subject.onReachedChargeStop()
        assertIs<Leg.ParkedAt>(subject.leg)
        assertTrue(
            subject.isCheckDue(moving = true, 0.0, 0.0, offRoute = false),
            "parked on top of the waypoint, the geometry gate must not apply",
        )
    }

    @Test
    fun `parked at a charger the car still names, nothing changes`() = runTest {
        val reads = mutableListOf<ActiveRoute?>(
            navigatingTo(charger),
            navigatingTo(charger), // still plugged in, still pointed at it
        )
        val subject = coordinator(FakeVehicleNavClient(), reads = reads)
        subject.check(here, destination, emptyList(), emptyList())
        subject.onReachedChargeStop()

        assertEquals(LegChange.None, subject.check(charger, destination, emptyList(), emptyList()))
        assertIs<Leg.ParkedAt>(subject.leg)
    }

    @Test
    fun `once charged, the car's next plan becomes the next leg`() = runTest {
        val reads = mutableListOf<ActiveRoute?>(
            navigatingTo(charger),
            navigatingTo(destination.location),
        )
        val subject = coordinator(FakeVehicleNavClient(), reads = reads)
        subject.check(here, destination, emptyList(), emptyList())
        subject.onReachedChargeStop()

        val change = assertIs<LegChange.ToDestination>(
            subject.check(charger, destination, emptyList(), emptyList()),
        )
        assertEquals(destination.location, change.plan.destination.location)
        assertEquals(Leg.ToDestination, subject.leg)
    }

    // --- Steering pin by pin ------------------------------------------------
    //
    // A car that takes one destination can still be made to follow a shaped
    // route, by being aimed at the next pin and having that aim moved along. It
    // is also the case where the car is most likely to insert a Supercharger,
    // because these are the long trips. The two have to work at once.

    private val nextPin = GeoPoint(39.05, -98.5)
    private val steeredChain = listOf(nextPin, destination.location)

    @Test
    fun `while steering, a check re-asserts the destination instead of reading the pin`() = runTest {
        // The bug: a steered car names the pin we gave it. Read that for free
        // and we read back our own instruction, which says nothing about
        // charging — so the watch looks alive and reports nothing, forever.
        val vehicle = FakeVehicleNavClient()
        val reads = mutableListOf<ActiveRoute?>(navigatingTo(charger, "Supercharger Anytown"))
        val subject = coordinator(vehicle, reads = reads, steering = true)

        val change = assertIs<LegChange.ToChargeStop>(
            subject.check(here, destination, emptyList(), steeredChain),
            "the charger the car inserted must be found while steering too",
        )

        assertEquals(charger, change.stop.at)
        assertEquals(
            listOf(destination.location),
            assertIs<FakeVehicleNavClient.Call.PushRoute>(vehicle.calls().first()).waypoints,
            "asking has to cost a re-assert — a free read would only describe the pin",
        )
    }

    @Test
    fun `while steering, an unchanged verdict puts the steering back`() = runTest {
        // The re-assert points the car at the destination to ask the question.
        // Leaving it there abandons the shaped route without saying so.
        val vehicle = FakeVehicleNavClient()
        val reads = mutableListOf<ActiveRoute?>(navigatingTo(destination.location))
        val subject = coordinator(vehicle, reads = reads, steering = true)

        val change = subject.check(here, destination, emptyList(), steeredChain)

        assertEquals(LegChange.None, change)
        assertEquals(
            listOf(nextPin),
            assertIs<FakeVehicleNavClient.Call.AdvanceTo>(vehicle.calls().last()).waypoints,
            "the car must be put back on the PIN it was following — sending the whole " +
                "chain to a single-destination car collapses to the trip's destination",
        )
    }

    @Test
    fun `a steered car that names our own pin is not a charging stop`() = runTest {
        // Our re-assert has not landed yet, so the car still reports the pin we
        // pushed. Treating that as an inserted charger would announce a
        // "charging leg" to a point on the driver's own route.
        val vehicle = FakeVehicleNavClient()
        val reads = mutableListOf<ActiveRoute?>(navigatingTo(nextPin))
        val subject = coordinator(vehicle, reads = reads, steering = true)

        val change = subject.check(here, destination, emptyList(), steeredChain)

        assertEquals(LegChange.None, change, "our own pin echoed back says nothing")
        assertEquals(Leg.ToDestination, subject.leg)
    }

    @Test
    fun `a steered car waits out the redirect interval, not the free-read one`() = runTest {
        // Free reads fire every 45s because they cost nothing. While steering
        // they cost a redirect, so they have to be rationed like one — a minute
        // in, the un-steered car is due and the steered one must not be.
        val clock = Clock()
        val steered = coordinator(FakeVehicleNavClient(), clock, mutableListOf(), steering = true)
        val holding = coordinator(FakeVehicleNavClient(), clock, mutableListOf(), steering = false)
        clock.now += 60_000

        fun due(subject: ChargeStopCoordinator) = subject.isCheckDue(
            moving = true,
            metersToNextWaypoint = 20_000.0,
            metersToNearestCamera = 20_000.0,
            offRoute = false,
        )

        assertTrue(due(holding), "a car holding the destination is asked for free and often")
        assertTrue(!due(steered), "a steered car must not be redirected on the free-read cadence")
    }

    @Test
    fun `the driver's own stops survive a charging detour`() {
        // **A stop the driver typed in must not be deleted because the car
        // decided to charge.** The charge leg is planned with no stops, and the
        // monitor then builds a fresh engine from it — so without carrying them
        // the next check reads a plan that has none and routes the rest of the
        // trip straight past them.
        runTest {
            val vehicle = FakeVehicleNavClient()
            val nearStop = GeoPoint(39.05, -98.55)
            val farStop = GeoPoint(39.0, -98.1)
            val plannedWith = mutableListOf<List<GeoPoint>>()
            val clock = Clock()
            val reads = mutableListOf<ActiveRoute?>(
                navigatingTo(charger),
                navigatingTo(destination.location),
            )
            val coordinator = coordinator(
                vehicle, clock, reads,
                planLeg = { _, stops, to, _ ->
                    plannedWith += stops
                    planFor(to)
                },
            )

            val toCharger = coordinator.check(here, destination, listOf(nearStop, farStop), emptyList())
            assertIs<LegChange.ToChargeStop>(toCharger)
            assertEquals(
                listOf(nearStop),
                plannedWith.last(),
                "the stop before the charger belongs to the charging leg",
            )

            // The car finishes charging and heads for the destination. The
            // engine built from the charge leg knows nothing of the far stop.
            clock.now += 10 * 60_000
            val onward = coordinator.check(here, destination, emptyList(), emptyList())
            assertIs<LegChange.ToDestination>(onward)
            assertTrue(
                farStop in plannedWith.last(),
                "the stop beyond the charger was dropped: ${plannedWith.last()}",
            )
        }
    }

    @Test
    fun `a carried stop is not lost when the route onward fails to plan`() = runTest {
        val vehicle = FakeVehicleNavClient()
        val farStop = GeoPoint(39.0, -98.1)
        val plannedWith = mutableListOf<List<GeoPoint>>()
        var failOnce = true
        val clock = Clock()
        val reads = mutableListOf<ActiveRoute?>(
            navigatingTo(charger),
            navigatingTo(destination.location),
            navigatingTo(destination.location),
        )
        val coordinator = coordinator(
            vehicle, clock, reads,
            planLeg = { _, stops, to, _ ->
                plannedWith += stops
                if (to == destination && failOnce) {
                    failOnce = false
                    null
                } else {
                    planFor(to)
                }
            },
        )

        coordinator.check(here, destination, listOf(farStop), emptyList())
        clock.now += 10 * 60_000
        assertEquals(LegChange.None, coordinator.check(here, destination, emptyList(), emptyList()))
        clock.now += 10 * 60_000
        val onward = coordinator.check(here, destination, emptyList(), emptyList())

        assertIs<LegChange.ToDestination>(onward)
        assertTrue(
            farStop in plannedWith.last(),
            "a failed plan threw the carried stop away: ${plannedWith.last()}",
        )
    }
}
