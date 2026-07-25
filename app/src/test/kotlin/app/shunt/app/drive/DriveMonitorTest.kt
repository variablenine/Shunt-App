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

    private fun plan(cameras: List<Camera> = emptyList()) = DrivePlan(
        destination = Destination("Home", dest),
        chain = chain,
        cameras = cameras,
        polyline = chain,
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
            replan = { from ->
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
    fun `a failed re-plan says plainly that nothing is protecting you`() = runTest {
        val alerter = RecordingAlerter()
        DriveMonitor(
            vehicle = FakeVehicleNavClient(),
            alerter = alerter,
            replan = { null }, // e.g. camera data unavailable out here
        ).run(routedPlan(), flowOf(*departure().toTypedArray()))

        val failed = alerter.alerts.filterIsInstance<Alert.ReplanFailed>().single()
        assertEquals(Alert.Severity.URGENT, failed.severity)
        assertTrue(alerter.alerts.none { it is Alert.Replanned }, "must not claim a new route")
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
            replan = { from ->
                DrivePlan(Destination("Home", dest), listOf(dest), listOf(onNewRoute), listOf(from, dest))
            },
        ).run(routedPlan(), flowOf(*fixes.toTypedArray()))

        assertTrue(
            alerter.alerts.filterIsInstance<Alert.CameraApproaching>().any { it.camera.id == 42L },
            "the new plan's cameras must be the ones warned about; alerts=${alerter.alerts}",
        )
    }
}
