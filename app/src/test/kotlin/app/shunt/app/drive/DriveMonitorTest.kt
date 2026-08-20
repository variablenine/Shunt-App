package app.shunt.app.drive

import app.shunt.app.plan.Destination
import app.shunt.app.plan.DrivePlan
import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.tesla.FakeVehicleNavClient
import app.shunt.tesla.PushResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DriveMonitorTest {

    private val w1 = GeoPoint(33.0, -97.00)
    private val w2 = GeoPoint(33.0, -96.98)
    private val dest = GeoPoint(33.0, -96.96)
    private val chain = listOf(w1, w2, dest)

    private fun west(p: GeoPoint, meters: Double): GeoPoint {
        val degPerMeterLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(p.lat)))
        return GeoPoint(p.lat, p.lon - meters * degPerMeterLon)
    }

    private fun fix(p: GeoPoint) = LocationUpdate(p, speedMetersPerSec = 25.0, bearingDegrees = 90.0)

    private fun plan(cameras: List<Camera> = emptyList(), steered: Boolean = false) = DrivePlan(
        destination = Destination("Home", dest),
        chain = chain,
        cameras = cameras,
        polyline = chain,
        steerByWaypoints = steered,
    )

    private class RecordingAlerter : Alerter {
        val alerts = mutableListOf<Alert>()
        override fun alert(alert: Alert) { alerts.add(alert) }
    }

    // A full approach: far, near w1, near w2, near destination.
    private val approach = listOf(
        west(w1, 1000.0), west(w1, 300.0), west(w2, 300.0), west(dest, 30.0),
    )

    @Test
    fun `advances each waypoint via the vehicle and arrives`() = runTest {
        val fake = FakeVehicleNavClient()
        val statuses = mutableListOf<DriveStatus>()
        val monitor = DriveMonitor(fake, RecordingAlerter(), onStatus = { statuses.add(it) })

        monitor.run(plan(), flowOf(*approach.map { fix(it) }.toTypedArray()))

        val advances = fake.calls().filterIsInstance<FakeVehicleNavClient.Call.AdvanceTo>()
        assertEquals(listOf(listOf(w2, dest), listOf(dest)), advances.map { it.waypoints })
        assertTrue(statuses.first() is DriveStatus.Driving)
        assertEquals(DriveStatus.Arrived, statuses.last())
    }

    @Test
    fun `advance failure raises a loud alert but the drive continues`() = runTest {
        // Fail the FIRST advanceTo (the w1 -> [w2, dest] drop).
        val fake = FakeVehicleNavClient(
            FakeVehicleNavClient.Behavior(
                failOnCalls = setOf(1),
                failure = PushResult.Failed("vehicle offline", retryable = true),
            ),
        )
        val alerter = RecordingAlerter()
        val monitor = DriveMonitor(fake, alerter)

        monitor.run(plan(), flowOf(*approach.map { fix(it) }.toTypedArray()))

        val failure = alerter.alerts.filterIsInstance<Alert.AdvanceFailed>().single()
        assertEquals("vehicle offline", failure.reason)
        assertTrue(failure.retryable)
        assertEquals(Alert.Severity.URGENT, failure.severity)
        // The second advance (call #2) still happened — monitoring did not abort.
        assertEquals(2, fake.calls().filterIsInstance<FakeVehicleNavClient.Call.AdvanceTo>().size)
    }

    @Test
    fun `camera warnings fire from the cached set with no vehicle interaction`() = runTest {
        val cam = Camera(9, GeoPoint(33.0, -96.985), mapOf("manufacturer" to "Flock Safety"))
        val fake = FakeVehicleNavClient()
        val alerter = RecordingAlerter()
        val monitor = DriveMonitor(fake, alerter)

        // Approach passing close to the camera at both tiers.
        val locations = listOf(west(cam.location, 380.0), west(cam.location, 120.0), west(dest, 30.0))
        monitor.run(plan(cameras = listOf(cam)), flowOf(*locations.map { fix(it) }.toTypedArray()))

        val cameraAlerts = alerter.alerts.filterIsInstance<Alert.CameraApproaching>()
        assertEquals(2, cameraAlerts.size)
        assertTrue(!cameraAlerts[0].imminent && cameraAlerts[1].imminent)
        assertEquals(Alert.Severity.WARNING, cameraAlerts[0].severity)
        assertEquals(Alert.Severity.URGENT, cameraAlerts[1].severity)
    }

    @Test
    fun `stops at arrival even if more fixes would follow`() = runTest {
        val fake = FakeVehicleNavClient()
        val monitor = DriveMonitor(fake, RecordingAlerter())
        // Two extra fixes past the destination that must not be processed.
        val locations = approach + listOf(dest, west(dest, 5.0))
        monitor.run(plan(), flowOf(*locations.map { fix(it) }.toTypedArray()))
        // Only the two real advances; nothing spurious after arrival.
        assertEquals(2, fake.calls().filterIsInstance<FakeVehicleNavClient.Call.AdvanceTo>().size)
    }

    @Test
    fun `status returns to idle if the flow ends before arrival`() = runTest {
        val fake = FakeVehicleNavClient()
        val statuses = mutableListOf<DriveStatus>()
        val monitor = DriveMonitor(fake, RecordingAlerter(), onStatus = { statuses.add(it) })
        // End the drive early (cancelled trip): only the far and near-w1 fixes.
        monitor.run(plan(), flowOf(fix(west(w1, 1000.0)), fix(west(w1, 300.0))))
        assertEquals(DriveStatus.Idle, statuses.last())
    }

    // ---- Steering a single-destination car pin by pin ---------------------

    @Test
    fun `steering sends the car the next waypoint, not the rest of the route`() = runTest {
        // The car takes one destination, so handing it the remaining chain hands
        // it the far end and loses the shape. Only the next pin goes out — and
        // as each is approached, the next one after it.
        val fake = FakeVehicleNavClient()
        val monitor = DriveMonitor(fake, RecordingAlerter())

        monitor.run(
            plan().copy(destinationOnly = true, steerByWaypoints = true),
            flowOf(*approach.map { fix(it) }.toTypedArray()),
        )

        val advances = fake.calls().filterIsInstance<FakeVehicleNavClient.Call.AdvanceTo>()
        assertEquals(listOf(listOf(w2), listOf(dest)), advances.map { it.waypoints })
    }

    @Test
    fun `steering carries onto a route that replaces the one being steered`() = runTest {
        // A re-planned leg is planned the ordinary way and knows nothing about
        // how this car is being driven; the monitor holds that.
        val vehicle = FakeVehicleNavClient()
        val pinned = listOf(GeoPoint(33.2, -96.97), dest)
        val published = mutableListOf<DrivePlan>()

        DriveMonitor(
            vehicle = vehicle,
            alerter = RecordingAlerter(),
            replan = { _, _, _ -> DrivePlan(Destination("Home", dest), pinned, emptyList(), pinned) },
            onPlanChanged = { published += it },
        ).run(
            routedPlan().copy(destinationOnly = true, steerByWaypoints = true),
            flowOf(*departure().toTypedArray()),
        )

        assertEquals(
            listOf(listOf(pinned.first())),
            vehicle.calls().filterIsInstance<FakeVehicleNavClient.Call.PushRoute>().map { it.waypoints },
            "only the new route's first pin should have been sent",
        )
        assertTrue(
            published.single().steerByWaypoints,
            "the screen must be told the replacement is being steered too",
        )
    }

    @Test
    fun `a steered car is re-aimed even when the new route needs no shaping`() = runTest {
        // The usual rule — don't disturb a car that would drive this road anyway
        // — doesn't hold here: the car is aimed at a pin on the route just left.
        val vehicle = FakeVehicleNavClient()
        val plain = listOf(dest)

        DriveMonitor(
            vehicle = vehicle,
            alerter = RecordingAlerter(),
            replan = { _, _, _ -> DrivePlan(Destination("Home", dest), plain, emptyList(), plain) },
        ).run(
            DrivePlan(Destination("Home", dest), plain, emptyList(), routeLine, steerByWaypoints = true),
            flowOf(*departure().toTypedArray()),
        )

        assertEquals(
            listOf(plain),
            vehicle.calls().filterIsInstance<FakeVehicleNavClient.Call.PushRoute>().map { it.waypoints },
        )
    }

    // ---- Leaving the planned route --------------------------------------

    /** Straight eastbound line; the plan's polyline for adherence checks. */
    private val routeLine = listOf(GeoPoint(33.0, -97.01), GeoPoint(33.0, -96.95))

    private fun routedPlan(cameras: List<Camera> = emptyList()) = DrivePlan(
        destination = Destination("Home", dest),
        chain = chain,
        cameras = cameras,
        polyline = routeLine,
    )

    private fun north(p: GeoPoint, meters: Double) =
        GeoPoint(p.lat + meters / 111_320.0, p.lon)

    /** Enough consecutive off-line fixes to clear the hysteresis. */
    private fun departure(): List<LocationUpdate> {
        val off = north(GeoPoint(33.0, -96.995), 300.0)
        return List(4) { fix(off) }
    }

    @Test
    fun `the road just abandoned is kept out of the new route`() = runTest {
        // A closed road is invisible to Shunt — all it can see is that the
        // driver left here and did not come back. Re-planning without saying so
        // sends them straight back onto it, which is the loop that made the app
        // fight the driver for the car.
        var refused: List<GeoPoint>? = null

        DriveMonitor(
            vehicle = FakeVehicleNavClient(),
            alerter = RecordingAlerter(),
            replan = { _, _, blocked ->
                refused = blocked
                DrivePlan(Destination("Home", dest), chain, emptyList(), routeLine)
            },
        ).run(routedPlan(), flowOf(*departure().toTypedArray()))

        val blocked = refused
        assertTrue(blocked != null && blocked.isNotEmpty(), "the refused road must reach the planner")
        assertTrue(
            blocked.all { app.shunt.solver.geo.pointToPolyline(it, routeLine).distanceMeters < 200.0 },
            "what gets blocked is the route just left, not somewhere else",
        )
        // Blocking everything ahead would block the trip rather than the road.
        val spanned = app.shunt.solver.geo.haversineMeters(blocked.first(), blocked.last())
        assertTrue(
            spanned <= DriveMonitorBounds.ABANDONED_STRETCH_METERS + 500.0,
            "only the stretch ahead may be blocked, not the rest of the trip: $spanned m",
        )
    }

    @Test
    fun `a re-plan does not re-announce cameras already warned about`() = runTest {
        // Each re-plan built a fresh engine with no memory of what it had
        // already said, so every camera still in range was announced again.
        // With re-plans arriving one after another — the closed-road loop —
        // that is what became alerts that would not stop.
        val alerter = RecordingAlerter()
        val nearby = Camera(id = 42, location = GeoPoint(33.0, -96.994))

        DriveMonitor(
            vehicle = FakeVehicleNavClient(),
            alerter = alerter,
            // The replacement route carries the same camera, still in range.
            replan = { _, _, _ ->
                DrivePlan(Destination("Home", dest), chain, listOf(nearby), routeLine)
            },
        ).run(
            routedPlan(cameras = listOf(nearby)),
            flowOf(*List(40) { fix(north(GeoPoint(33.0, -96.995), 300.0)) }.toTypedArray()),
        )

        val warnings = alerter.alerts.filterIsInstance<Alert.CameraApproaching>()
            .filter { !it.imminent }
        assertTrue(
            warnings.size <= 1,
            "one camera, one warning — got ${warnings.size} across the re-plans",
        )
    }

    @Test
    fun `re-planning that turns into a fight hands the car back to the driver`() = runTest {
        // The closed-road loop, from a real drive: the route wants a road the
        // driver will not take, so leaving it re-plans, the re-plan is pushed,
        // the car turns back towards that road, and the driver leaves it again.
        // Each turn of the loop overrode what the driver had just done on the
        // car's own screen; the only escape was cancelling navigation in the
        // app. Shunt has to lose that argument.
        val alerter = RecordingAlerter()
        val vehicle = FakeVehicleNavClient()
        var replans = 0

        DriveMonitor(
            vehicle = vehicle,
            alerter = alerter,
            // Always answers with the same line the car is already off — which
            // is what a closure looks like from in here.
            replan = { _, _, _ ->
                replans++
                DrivePlan(Destination("Home", dest), chain, emptyList(), routeLine)
            },
        ).run(
            routedPlan(),
            flowOf(*List(80) { fix(north(GeoPoint(33.0, -96.995), 300.0)) }.toTypedArray()),
        )

        assertEquals(
            1,
            alerter.alerts.count { it is Alert.StoodDown },
            "must hand the car back, once, rather than keep re-planning",
        )
        assertTrue(replans <= 3, "must stop re-planning, not merely stop pushing: $replans")
    }

    @Test
    fun `once stood down, nothing further is sent to the car`() = runTest {
        // Standing down has to mean the car is genuinely the driver's. A monitor
        // that stops re-planning but keeps advancing waypoints is still fighting.
        val alerter = RecordingAlerter()
        val vehicle = FakeVehicleNavClient()

        DriveMonitor(
            vehicle = vehicle,
            alerter = alerter,
            replan = { _, _, _ -> DrivePlan(Destination("Home", dest), chain, emptyList(), routeLine) },
        ).run(
            routedPlan(),
            flowOf(*List(80) { fix(north(GeoPoint(33.0, -96.995), 300.0)) }.toTypedArray()),
        )

        val stoodDownAt = alerter.alerts.indexOfFirst { it is Alert.StoodDown }
        assertTrue(stoodDownAt >= 0, "this test is meaningless unless it stood down")
        assertTrue(
            alerter.alerts.drop(stoodDownAt).none { it is Alert.Replanned },
            "no route may be put in force after handing the car back",
        )
        assertTrue(
            alerter.alerts.filterIsInstance<Alert.OffRoute>().last().replanning == false,
            "and it must stop telling the driver it is re-planning",
        )
    }

    @Test
    fun `leaving the route warns that camera avoidance no longer applies`() = runTest {
        val alerter = RecordingAlerter()
        // No re-planner wired: detection alone must still be loud and honest.
        DriveMonitor(FakeVehicleNavClient(), alerter).run(routedPlan(), flowOf(*departure().toTypedArray()))

        val offRoute = alerter.alerts.filterIsInstance<Alert.OffRoute>().single()
        assertEquals(Alert.Severity.URGENT, offRoute.severity, "leaving the route is urgent")
        assertTrue(!offRoute.replanning, "no re-planner was provided")
    }

    @Test
    fun `leaving the route re-plans from the car's actual position and pushes it`() = runTest {
        val alerter = RecordingAlerter()
        val vehicle = FakeVehicleNavClient()
        val freshChain = listOf(GeoPoint(33.2, -96.97), dest)
        var replannedFrom: GeoPoint? = null

        DriveMonitor(
            vehicle = vehicle,
            alerter = alerter,
            replan = { from, _, _ ->
                replannedFrom = from
                DrivePlan(Destination("Home", dest), freshChain, emptyList(), freshChain)
            },
        ).run(routedPlan(), flowOf(*departure().toTypedArray()))

        assertTrue(replannedFrom != null, "must re-plan from where the car actually is")
        assertTrue(
            replannedFrom!!.lat > 33.001,
            "must re-plan from the off-route position, not the original start",
        )
        // The driver is told before the re-plan lands, then told the outcome.
        assertTrue(alerter.alerts.any { it is Alert.OffRoute })
        val replanned = alerter.alerts.filterIsInstance<Alert.Replanned>().single()
        assertEquals(0, replanned.camerasOnNewRoute)
        // And the car got the new route.
        val pushes = vehicle.calls().filterIsInstance<FakeVehicleNavClient.Call.PushRoute>()
        assertEquals(listOf(freshChain), pushes.map { it.waypoints })
    }

    @Test
    fun `a camera-free replacement route is not pushed at the car`() = runTest {
        // Straying off an already camera-free route should update what the app
        // shows and leave the car alone. Re-sending the destination it already
        // holds interrupts the navigation on its screen and tells it nothing.
        val vehicle = FakeVehicleNavClient()
        val plain = listOf(dest) // no shaping pins: the car's own road is fine
        // The route being left is unpinned too, so the car is already aimed at
        // the destination and there is nothing to correct.
        val unpinned = DrivePlan(Destination("Home", dest), plain, emptyList(), routeLine)

        DriveMonitor(
            vehicle = vehicle,
            alerter = RecordingAlerter(),
            replan = { from, _, _ -> DrivePlan(Destination("Home", dest), plain, emptyList(), listOf(from, dest)) },
        ).run(unpinned, flowOf(*departure().toTypedArray()))

        assertTrue(
            vehicle.calls().filterIsInstance<FakeVehicleNavClient.Call.PushRoute>().isEmpty(),
            "nothing needed steering, so the car should have been left alone",
        )
    }

    @Test
    fun `coming off a pinned route restores the destination the car is missing`() = runTest {
        // The car is still aimed at a shaping pin that no longer exists. Even
        // though the replacement needs no steering, the stale pin has to go.
        val vehicle = FakeVehicleNavClient()
        val plain = listOf(dest)

        DriveMonitor(
            vehicle = vehicle,
            alerter = RecordingAlerter(),
            replan = { _, _, _ -> DrivePlan(Destination("Home", dest), plain, emptyList(), plain) },
        ).run(routedPlan(), flowOf(*departure().toTypedArray()))

        assertEquals(
            listOf(plain),
            vehicle.calls().filterIsInstance<FakeVehicleNavClient.Call.PushRoute>().map { it.waypoints },
            "the destination must be restored over the abandoned pin",
        )
    }

    @Test
    fun `a replacement route that needs steering is still pushed`() = runTest {
        val vehicle = FakeVehicleNavClient()
        val pinned = listOf(GeoPoint(33.2, -96.97), dest)

        DriveMonitor(
            vehicle = vehicle,
            alerter = RecordingAlerter(),
            replan = { _, _, _ -> DrivePlan(Destination("Home", dest), pinned, emptyList(), pinned) },
        ).run(routedPlan(), flowOf(*departure().toTypedArray()))

        assertEquals(
            listOf(pinned),
            vehicle.calls().filterIsInstance<FakeVehicleNavClient.Call.PushRoute>().map { it.waypoints },
        )
    }

    @Test
    fun `the route in force is published so the screen can follow it`() = runTest {
        // Without this a re-plan is invisible: the monitor drives the new line
        // while the map still shows the one that was abandoned.
        val fresh = DrivePlan(Destination("Home", dest), listOf(dest), emptyList(), listOf(dest))
        val published = mutableListOf<DrivePlan>()

        DriveMonitor(
            vehicle = FakeVehicleNavClient(),
            alerter = RecordingAlerter(),
            replan = { _, _, _ -> fresh },
            onPlanChanged = { published += it },
        ).run(routedPlan(), flowOf(*departure().toTypedArray()))

        assertEquals(listOf(fresh), published, "the new route must reach the screen")
    }

    @Test
    fun `a re-plan while under way is given the direction of travel`() = runTest {
        // The bug this prevents: a re-plan that answers "turn round and go back
        // to the road you just left". At 60 mph that isn't a route.
        var seenHeading: Double? = null
        DriveMonitor(
            vehicle = FakeVehicleNavClient(),
            alerter = RecordingAlerter(),
            replan = { _, heading, _ ->
                seenHeading = heading
                DrivePlan(Destination("Home", dest), listOf(dest), emptyList(), listOf(dest))
            },
        ).run(routedPlan(), flowOf(*departure().toTypedArray()))

        assertEquals(90.0, seenHeading, "the fix's own bearing must reach the router")
    }

    @Test
    fun `a re-plan while stopped is given no direction at all`() = runTest {
        // A parked car's last bearing is just the way it happened to come to
        // rest; holding a new route to it would rule out the road behind.
        var seenHeading: Double? = 123.0
        val stopped = departure().map { it.copy(speedMetersPerSec = 0.0) }
        DriveMonitor(
            vehicle = FakeVehicleNavClient(),
            alerter = RecordingAlerter(),
            replan = { _, heading, _ ->
                seenHeading = heading
                DrivePlan(Destination("Home", dest), listOf(dest), emptyList(), listOf(dest))
            },
        ).run(routedPlan(), flowOf(*stopped.toTypedArray()))

        assertEquals(null, seenHeading, "a stationary bearing must not constrain the route")
    }

    @Test
    fun `a failed re-plan says plainly that nothing is protecting you`() = runTest {
        val alerter = RecordingAlerter()
        DriveMonitor(
            vehicle = FakeVehicleNavClient(),
            alerter = alerter,
            replan = { _, _, _ -> null }, // e.g. camera data unavailable out here
        ).run(routedPlan(), flowOf(*departure().toTypedArray()))

        val failed = alerter.alerts.filterIsInstance<Alert.ReplanFailed>().single()
        assertEquals(Alert.Severity.URGENT, failed.severity)
        assertTrue(alerter.alerts.none { it is Alert.Replanned }, "must not claim a new route")
    }

    // ---- Charging stops the car adds by itself ---------------------------

    private val charger = GeoPoint(33.1, -96.99)

    private fun navigatingTo(p: GeoPoint, name: String) = app.shunt.tesla.ActiveRoute(
        destinationName = name,
        latitude = p.lat,
        longitude = p.lon,
        milesToArrival = null,
        minutesToArrival = null,
        energyAtArrival = null,
        batteryLevel = null,
        estimatedRangeMiles = null,
    )

    private fun chargingCoordinator(
        vehicle: FakeVehicleNavClient,
        reads: MutableList<app.shunt.tesla.ActiveRoute?>,
        chargerPlan: DrivePlan?,
        steering: Boolean = false,
    ) = ChargeStopCoordinator(
        vehicle = vehicle,
        steering = steering,
        readActiveRoute = { reads.removeFirstOrNull() },
        planLeg = { _, _, to, _ -> if (to.location == charger) chargerPlan else null },
        // Cadence is covered in ChargeStopCoordinatorTest; here every fix is
        // due so the test is about what the monitor does with the answer.
        window = ProbeWindow(readIntervalMillis = 0, minIntervalMillis = 0),
        pause = {},
    )

    @Test
    fun `a charging stop the car added takes over the drive, and arriving at it is not arriving`() = runTest {
        val vehicle = FakeVehicleNavClient()
        val alerter = RecordingAlerter()
        val statuses = mutableListOf<DriveStatus>()
        val chargerLeg = DrivePlan(
            destination = Destination("Supercharger Anytown", charger),
            chain = listOf(charger),
            cameras = emptyList(),
            polyline = listOf(w1, charger),
        )
        val charging = chargingCoordinator(
            vehicle,
            mutableListOf(navigatingTo(charger, "Supercharger Anytown")),
            chargerLeg,
        )

        // Set off, then drive to the charger rather than the original destination.
        val fixes = listOf(fix(west(w1, 1000.0)), fix(west(charger, 300.0)), fix(charger))
        DriveMonitor(
            vehicle = vehicle,
            alerter = alerter,
            onStatus = { statuses.add(it) },
            charging = charging,
        ).run(plan(), flowOf(*fixes.toTypedArray()))

        val announced = alerter.alerts.filterIsInstance<Alert.ChargeStopAhead>().single()
        assertEquals("Supercharger Anytown", announced.name)
        // The car got the leg to the charger...
        assertTrue(
            vehicle.calls().filterIsInstance<FakeVehicleNavClient.Call.PushRoute>()
                .any { it.waypoints == listOf(charger) },
        )
        // ...and reaching it is a stop on the way, not the end of the trip.
        assertTrue(alerter.alerts.any { it is Alert.ReachedChargeStop })
        assertTrue(alerter.alerts.none { it is Alert.Arrived }, "the trip is not over at the charger")
        assertTrue(statuses.none { it is DriveStatus.Arrived })
        assertEquals(
            "Supercharger Anytown",
            statuses.filterIsInstance<DriveStatus.Driving>().last().chargingVia,
            "the UI must be able to say the car is charging first",
        )
    }

    @Test
    fun `a charging stop we cannot route to is called out as unprotected`() = runTest {
        val alerter = RecordingAlerter()
        val charging = chargingCoordinator(
            FakeVehicleNavClient(),
            mutableListOf(navigatingTo(charger, "Supercharger Anytown")),
            chargerPlan = null, // e.g. no offline tile out there
        )

        DriveMonitor(FakeVehicleNavClient(), alerter, charging = charging)
            .run(plan(), flowOf(fix(west(w1, 1000.0))))

        val unroutable = alerter.alerts.filterIsInstance<Alert.ChargeStopUnroutable>().single()
        assertEquals("Supercharger Anytown", unroutable.name)
        assertEquals(Alert.Severity.URGENT, unroutable.severity, "no avoidance in force is urgent")
    }

    @Test
    fun `a charging check always leaves a steered car aimed at the next pin`() = runTest {
        // Observed on a real drive. A charging probe has to redirect the car at
        // the final destination to ask its question, and putting the aim back was
        // left to the coordinator — which does it on the paths it knows about and
        // cannot on the rest. Here the car reports it has dropped the charger, so
        // the coordinator tries to resume to the destination, and the re-plan for
        // that comes back empty (no tile out there, engine busy, whatever). It
        // reports "nothing changed" and the car is left holding the *destination*.
        //
        // A car holding the destination drives to it. The driver leaves the
        // shaped route, which reads as off-route, which re-plans — and they end
        // up on a road with cameras on it that they had a clean route around.
        // That is the whole failure, and it starts here.
        //
        // Its own geometry, deliberately: the car sits far short of the next pin
        // so nothing advances, and every call the vehicle sees comes from the
        // charging path. Otherwise a stale advance from earlier in the drive is
        // the last call and the assertion passes without meaning anything.
        val start = GeoPoint(33.0, -97.0)
        val line = (0..50).map { west(start, -it * 100.0) } // 5 km east, every 100 m
        val pin = line[40]
        val far = line[50]
        val vehicle = FakeVehicleNavClient()
        val chargerLeg = DrivePlan(
            destination = Destination("Supercharger Anytown", charger),
            chain = listOf(charger),
            cameras = emptyList(),
            polyline = listOf(line[2], charger),
        )
        val charging = chargingCoordinator(
            vehicle,
            // First the car reports the charger it inserted, then that it has
            // gone back to heading for the destination.
            mutableListOf(
                navigatingTo(charger, "Supercharger Anytown"),
                navigatingTo(far, "Home"),
            ),
            chargerLeg,
            steering = true,
        )

        DriveMonitor(vehicle, RecordingAlerter(), charging = charging).run(
            DrivePlan(
                destination = Destination("Home", far),
                chain = listOf(pin, far),
                cameras = emptyList(),
                polyline = line,
                steerByWaypoints = true,
            ),
            flowOf(fix(line[2]), fix(line[3])),
        )

        // What matters is the *last* thing the car was told, because that is what
        // it drives to.
        val last = vehicle.calls().last()
        assertIs<FakeVehicleNavClient.Call.AdvanceTo>(
            last,
            "the probe's own redirect to the destination was the last word to the car: $last",
        )
        assertEquals(
            listOf(charger),
            last.waypoints,
            "the car must end up aimed at the pin in force, not the trip's destination",
        )
    }

    @Test
    fun `a free read costs the car no extra commands`() = runTest {
        // The other half of the rule above. A car that holds the destination can
        // be read without touching it, so there is nothing to put back and
        // re-sending its own destination would be pure traffic on a rate-limited
        // API. Only the two ordinary waypoint advances.
        val vehicle = FakeVehicleNavClient()
        val charging = chargingCoordinator(
            vehicle,
            mutableListOf(navigatingTo(dest, "Home")),
            chargerPlan = null,
        )

        DriveMonitor(vehicle, RecordingAlerter(), charging = charging)
            .run(plan(), flowOf(*approach.map { fix(it) }.toTypedArray()))

        assertEquals(
            listOf(listOf(w2, dest), listOf(dest)),
            vehicle.calls().filterIsInstance<FakeVehicleNavClient.Call.AdvanceTo>().map { it.waypoints },
        )
    }

    @Test
    fun `a car heading straight to the destination drives exactly as before`() = runTest {
        val vehicle = FakeVehicleNavClient()
        val alerter = RecordingAlerter()
        val charging = chargingCoordinator(
            vehicle,
            mutableListOf(navigatingTo(dest, "Home")),
            chargerPlan = null,
        )

        DriveMonitor(vehicle, alerter, charging = charging)
            .run(plan(), flowOf(*approach.map { fix(it) }.toTypedArray()))

        assertTrue(alerter.alerts.any { it is Alert.Arrived })
        assertTrue(alerter.alerts.none { it is Alert.ChargeStopAhead })
        // Only the two ordinary waypoint advances — no probe traffic.
        assertEquals(
            listOf(listOf(w2, dest), listOf(dest)),
            vehicle.calls().filterIsInstance<FakeVehicleNavClient.Call.AdvanceTo>().map { it.waypoints },
        )
    }

    @Test
    fun `after re-planning, camera warnings follow the new route`() = runTest {
        val alerter = RecordingAlerter()
        // A camera that is only on the *replacement* route.
        val onNewRoute = Camera(42, north(GeoPoint(33.0, -96.99), 300.0))
        val off = north(GeoPoint(33.0, -96.995), 300.0)
        val fixes = List(4) { fix(off) } + fix(north(GeoPoint(33.0, -96.991), 300.0))

        DriveMonitor(
            vehicle = FakeVehicleNavClient(),
            alerter = alerter,
            replan = { from, _, _ ->
                DrivePlan(Destination("Home", dest), listOf(dest), listOf(onNewRoute), listOf(from, dest))
            },
        ).run(routedPlan(), flowOf(*fixes.toTypedArray()))

        assertTrue(
            alerter.alerts.filterIsInstance<Alert.CameraApproaching>().any { it.camera.id == 42L },
            "the new plan's cameras must be the ones warned about; alerts=${alerter.alerts}",
        )
    }

    // ---- Extending a drive with a leg planned while moving ----------------
    //
    // A long trip is handed over as its first leg so the driver can set off in
    // seconds rather than minutes; the rest arrives while the car is moving.
    // The property that matters is that appending changes nothing about the part
    // already driven.

    @Test
    fun `a leg that lands mid-drive extends the chain without losing progress`() = runTest {
        val vehicle = FakeVehicleNavClient()
        val boundary = GeoPoint(33.0, -96.94)
        val realEnd = GeoPoint(33.0, -96.90)
        val firstLeg = DrivePlan(
            destination = Destination("Boundary", dest),
            chain = chain,
            cameras = emptyList(),
            polyline = chain,
            remaining = listOf(dest, realEnd),
        )
        val nextLeg = DrivePlan(
            destination = Destination("Home", realEnd),
            chain = listOf(boundary, realEnd),
            cameras = emptyList(),
            polyline = listOf(dest, boundary, realEnd),
        )
        val extensions = kotlinx.coroutines.channels.Channel<DrivePlan>(kotlinx.coroutines.channels.Channel.CONFLATED)
        val published = mutableListOf<DrivePlan>()

        val monitor = DriveMonitor(
            vehicle = vehicle,
            alerter = RecordingAlerter(),
            onPlanChanged = { published += it },
            extensions = extensions,
        )
        // Set off and pass w1 *before* the next leg lands — building the fixes
        // eagerly would deliver it on the first fix, when there is no progress
        // to preserve and the test proves nothing.
        val locations = kotlinx.coroutines.flow.flow {
            emit(fix(west(w1, 1000.0)))
            emit(fix(west(w1, 300.0)))
            extensions.trySend(nextLeg)
            emit(fix(west(w2, 300.0)))
            emit(fix(west(dest, 300.0)))
        }
        monitor.run(firstLeg, locations)

        val extended = published.single()
        assertEquals(
            Destination("Home", realEnd),
            extended.destination,
            "the extension must restore the real destination the first leg stood in for",
        )
        assertTrue(
            w1 !in extended.chain,
            "a pin already driven past came back into the chain: ${extended.chain}",
        )
        assertEquals(
            listOf(boundary, realEnd),
            extended.chain.takeLast(2),
            "the new leg's chain must be appended in order",
        )
        assertTrue(extended.remaining.isEmpty(), "this extension reaches the destination")
    }

    @Test
    fun `two legs that landed before the drive started are both kept`() = runTest {
        // **The conflation bug, in the shape it actually happened.** Later legs
        // are planned from the moment the chooser appears, and each takes
        // seconds; the monitor that drains them does not exist until Go is
        // tapped. So a driver reading the options for a minute has several legs
        // waiting before the first GPS fix. A channel holding one kept the last
        // and the drive jumped straight to it — skipping a leg's road, its pins
        // and, worst of all, its cameras.
        val vehicle = FakeVehicleNavClient()
        val boundary = GeoPoint(33.0, -96.94)
        val middle = GeoPoint(33.0, -96.90)
        val realEnd = GeoPoint(33.0, -96.86)
        val seenOnLegTwo = Camera(77, GeoPoint(33.0, -96.92))
        val firstLeg = DrivePlan(
            destination = Destination("Boundary", dest),
            chain = chain,
            cameras = emptyList(),
            polyline = chain,
            remaining = listOf(dest, realEnd),
        )
        val legTwo = DrivePlan(
            destination = Destination("Middle", middle),
            chain = listOf(boundary, middle),
            cameras = listOf(seenOnLegTwo),
            polyline = listOf(dest, boundary, middle),
            remaining = listOf(middle, realEnd),
        )
        val legThree = DrivePlan(
            destination = Destination("Home", realEnd),
            chain = listOf(realEnd),
            cameras = emptyList(),
            polyline = listOf(middle, realEnd),
        )
        // **The production channel**, not a channel the test chose. Building it
        // here is the whole point: with the conflated one this exact fixture
        // keeps only the last leg.
        val extensions = legExtensionChannel()
        // Both land while the chooser is still on screen — before any fix.
        extensions.trySend(legTwo)
        extensions.trySend(legThree)
        val published = mutableListOf<DrivePlan>()

        val monitor = DriveMonitor(
            vehicle = vehicle,
            alerter = RecordingAlerter(),
            onPlanChanged = { published += it },
            extensions = extensions,
        )
        monitor.run(firstLeg, kotlinx.coroutines.flow.flow { emit(fix(west(w1, 1000.0))) })

        val extended = published.last()
        assertEquals(
            Destination("Home", realEnd),
            extended.destination,
            "the last leg still names the real destination",
        )
        assertTrue(
            boundary in extended.chain && middle in extended.chain && realEnd in extended.chain,
            "every leg's chain must survive, in order: ${extended.chain}",
        )
        assertTrue(
            seenOnLegTwo.id in extended.cameras.map { it.id },
            "a camera on the middle leg was dropped with the leg: ${extended.cameras}",
        )
    }

    @Test
    fun `reaching a leg boundary with nothing beyond it is not an arrival`() = runTest {
        // Vanishingly rare by design — the boundary is an hour's driving away and
        // the next leg plans in seconds — but announcing arrival in open country
        // is worse than admitting the app is behind.
        val alerter = RecordingAlerter()
        val partial = DrivePlan(
            destination = Destination("Boundary", dest),
            chain = chain,
            cameras = emptyList(),
            polyline = chain,
            remaining = listOf(dest, GeoPoint(33.0, -96.90)),
        )

        DriveMonitor(FakeVehicleNavClient(), alerter)
            .run(partial, flowOf(*approach.map { fix(it) }.toTypedArray()))

        assertTrue(
            alerter.alerts.any { it is Alert.LegBoundaryReached },
            "the driver must be told the route is unfinished: ${alerter.alerts}",
        )
        assertTrue(
            alerter.alerts.none { it is Alert.Arrived },
            "a leg boundary was announced as the destination",
        )
    }
}
