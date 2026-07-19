package app.shunt.app.drive

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.geo.bearingDegrees
import app.shunt.solver.geo.haversineMeters
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DriveMonitorEngineTest {

    // A straight eastbound chain near lat 45; ~0.01 deg lon ≈ 787 m apart.
    private val w1 = GeoPoint(45.0, -88.00)
    private val w2 = GeoPoint(45.0, -87.98)
    private val dest = GeoPoint(45.0, -87.96)
    private val chain = listOf(w1, w2, dest)

    /** A point [meters] west of [p] (approaching from the west, heading east). */
    private fun west(p: GeoPoint, meters: Double): GeoPoint {
        val degPerMeterLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(p.lat)))
        return GeoPoint(p.lat, p.lon - meters * degPerMeterLon)
    }

    private fun update(p: GeoPoint, speed: Double? = 25.0, bearing: Double? = 90.0) =
        LocationUpdate(p, speed, bearing)

    @Test
    fun `advances each intermediate waypoint once, early, in order`() {
        val engine = DriveMonitorEngine(chain, cameras = emptyList())
        // Far away: nothing.
        assertTrue(engine.onLocation(update(west(w1, 1000.0))).isEmpty())

        // Within lead (25 m/s * 18 s = 450 m) of w1: advance, remaining = [w2, dest].
        val s1 = engine.onLocation(update(west(w1, 400.0)))
        val a1 = assertIs<DriveSignal.ApproachingWaypoint>(s1.single())
        assertEquals(listOf(w2, dest), a1.remaining)

        // Still near w1 but already advanced: no repeat.
        assertTrue(engine.onLocation(update(west(w1, 300.0))).none { it is DriveSignal.ApproachingWaypoint })

        // Approaching w2: advance, remaining = [dest].
        val s2 = engine.onLocation(update(west(w2, 400.0)))
        val a2 = assertIs<DriveSignal.ApproachingWaypoint>(s2.single())
        assertEquals(listOf(dest), a2.remaining)
    }

    @Test
    fun `lead distance honors the time lead and the floor`() {
        // Fast: lead = 40 m/s * 18 s = 720 m, so 600 m out already advances.
        val fast = DriveMonitorEngine(chain, emptyList())
        assertTrue(fast.onLocation(update(west(w1, 600.0), speed = 40.0)).any { it is DriveSignal.ApproachingWaypoint })

        // Stopped: speed 0 → floor of 150 m. 300 m out does NOT advance yet...
        val slow = DriveMonitorEngine(chain, emptyList())
        assertTrue(slow.onLocation(update(west(w1, 300.0), speed = 0.0)).none { it is DriveSignal.ApproachingWaypoint })
        // ...100 m out does.
        assertTrue(slow.onLocation(update(west(w1, 100.0), speed = 0.0)).any { it is DriveSignal.ApproachingWaypoint })
    }

    @Test
    fun `missing speed falls back to assumed speed`() {
        val engine = DriveMonitorEngine(chain, emptyList())
        // assumed 25 m/s → 450 m lead; 400 m out advances even with null speed.
        assertTrue(engine.onLocation(update(west(w1, 400.0), speed = null)).any { it is DriveSignal.ApproachingWaypoint })
    }

    @Test
    fun `arrival fires within radius and only for the destination`() {
        val engine = DriveMonitorEngine(chain, emptyList())
        // Blow through the two intermediate waypoints.
        engine.onLocation(update(w1))
        engine.onLocation(update(w2))
        // Near destination but outside arrival radius: not yet.
        assertTrue(engine.onLocation(update(west(dest, 200.0))).none { it is DriveSignal.Arrived })
        // Within arrival radius: Arrived.
        assertTrue(engine.onLocation(update(west(dest, 40.0))).any { it is DriveSignal.Arrived })
        // After arrival, nothing more.
        assertTrue(engine.onLocation(update(dest)).isEmpty())
    }

    @Test
    fun `direct route with no intermediate waypoints only arrives`() {
        val engine = DriveMonitorEngine(listOf(dest), emptyList())
        assertTrue(engine.onLocation(update(west(dest, 500.0))).isEmpty())
        assertTrue(engine.onLocation(update(dest)).single() is DriveSignal.Arrived)
    }

    @Test
    fun `camera warns at two escalating tiers, once each`() {
        val cam = Camera(7, GeoPoint(45.0, -87.985))
        val engine = DriveMonitorEngine(chain, listOf(cam))

        // 400 m warn tier.
        val warn = engine.onLocation(update(west(cam.location, 380.0)))
            .filterIsInstance<DriveSignal.ApproachingCamera>().single()
        assertTrue(!warn.imminent)

        // Between tiers: no repeat of the warn tier.
        assertTrue(engine.onLocation(update(west(cam.location, 300.0)))
            .filterIsInstance<DriveSignal.ApproachingCamera>().isEmpty())

        // 150 m imminent tier.
        val imminent = engine.onLocation(update(west(cam.location, 120.0)))
            .filterIsInstance<DriveSignal.ApproachingCamera>().single()
        assertTrue(imminent.imminent)

        // Passed: no third alert.
        assertTrue(engine.onLocation(update(west(cam.location, 50.0)))
            .filterIsInstance<DriveSignal.ApproachingCamera>().isEmpty())
    }

    @Test
    fun `camera side reflects heading`() {
        // Camera north of an eastbound route is on the LEFT; south is RIGHT.
        // Kept within the 400 m warn range of the observation point.
        val north = Camera(1, GeoPoint(45.002, -87.985))
        val south = Camera(2, GeoPoint(44.998, -87.985))
        val engine = DriveMonitorEngine(chain, listOf(north, south))
        val signals = engine.onLocation(update(GeoPoint(45.0, -87.987), bearing = 90.0))
            .filterIsInstance<DriveSignal.ApproachingCamera>().associateBy { it.camera.id }
        assertEquals(Side.LEFT, signals[1]?.side)
        assertEquals(Side.RIGHT, signals[2]?.side)
    }

    @Test
    fun `camera side is null without heading`() {
        val cam = Camera(1, GeoPoint(45.002, -87.99))
        val engine = DriveMonitorEngine(chain, listOf(cam))
        val signal = engine.onLocation(update(GeoPoint(45.0, -87.99), bearing = null))
            .filterIsInstance<DriveSignal.ApproachingCamera>().single()
        assertEquals(null, signal.side)
    }

    @Test
    fun `sanity of the test geometry`() {
        // Guard the helpers: 'west' really is west and ~the requested distance.
        val p = west(w1, 400.0)
        assertTrue(p.lon < w1.lon)
        assertEquals(400.0, haversineMeters(p, w1), 5.0)
        assertEquals(90.0, bearingDegrees(p, w1), 1.0)
    }
}
