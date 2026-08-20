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
    private val w1 = GeoPoint(33.0, -97.00)
    private val w2 = GeoPoint(33.0, -96.98)
    private val dest = GeoPoint(33.0, -96.96)
    private val chain = listOf(w1, w2, dest)

    /** A point [meters] west of [p] (approaching from the west, heading east). */
    private fun west(p: GeoPoint, meters: Double): GeoPoint {
        val degPerMeterLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(p.lat)))
        return GeoPoint(p.lat, p.lon - meters * degPerMeterLon)
    }

    /** Where a straight eastbound road starts, for the along-route fixtures. */
    private val origin = GeoPoint(33.0, -97.10)

    /** A point [meters] east of [p]. */
    private fun east(p: GeoPoint, meters: Double): GeoPoint = west(p, -meters)

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
    fun `close-spaced pins on a fast road are not skipped two at a time`() {
        // **From a real drive: "the waypoints are REALLY sensitive and going way
        // too early".** Pin spacing tightens on camera density — 250 m where
        // they are thick — while the lead grows with speed: 450 m at 25 m/s.
        // A fast road through a watched corridor gets both, so the monitor
        // re-aimed two and three pins ahead at once and the turns they were
        // placed for were never forced.
        val road = (0..30).map { east(origin, it * 100.0) } // 3 km, 100 m apart
        val pins = listOf(east(origin, 1_000.0), east(origin, 1_250.0), east(origin, 1_500.0))
        val engine = DriveMonitorEngine(
            chain = pins + east(origin, 3_000.0),
            cameras = emptyList(),
            routePolyline = road,
        )

        // 100 m short of the first pin, which is a kilometre from the start —
        // the speed lead applies in full and it advances.
        val first = engine.onLocation(update(east(origin, 900.0)))
        assertTrue(first.any { it is DriveSignal.ApproachingWaypoint }, "the first pin should advance")

        // Now aiming at a pin 250 m further on. The speed lead alone is 450 m,
        // so without the gap cap this fix re-aims past it while the car is still
        // 300 m short — the pin never constrains anything.
        val tooEarly = engine.onLocation(update(east(origin, 950.0)))
        assertTrue(
            tooEarly.none { it is DriveSignal.ApproachingWaypoint },
            "re-aimed past a pin 300 m ahead of the car, which is the reported fault",
        )

        // Genuinely close to it: advance.
        val onTime = engine.onLocation(update(east(origin, 1_180.0)))
        assertTrue(onTime.any { it is DriveSignal.ApproachingWaypoint }, "should advance when actually near")
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
        val cam = Camera(7, GeoPoint(33.0, -96.985))
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
        val north = Camera(1, GeoPoint(33.002, -96.985))
        val south = Camera(2, GeoPoint(32.998, -96.985))
        val engine = DriveMonitorEngine(chain, listOf(north, south))
        val signals = engine.onLocation(update(GeoPoint(33.0, -96.987), bearing = 90.0))
            .filterIsInstance<DriveSignal.ApproachingCamera>().associateBy { it.camera.id }
        assertEquals(Side.LEFT, signals[1]?.side)
        assertEquals(Side.RIGHT, signals[2]?.side)
    }

    @Test
    fun `camera side is null without heading`() {
        val cam = Camera(1, GeoPoint(33.002, -96.99))
        val engine = DriveMonitorEngine(chain, listOf(cam))
        val signal = engine.onLocation(update(GeoPoint(33.0, -96.99), bearing = null))
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

    // ---- Route adherence -------------------------------------------------
    //
    // Leaving the route is safety-critical: the camera avoidance was computed
    // for the planned line, so once the car is off it the "camera-free" promise
    // no longer holds and the driver has to be told.

    /** The planned line: straight east along lat 33, through the chain. */
    private val routeLine = listOf(GeoPoint(33.0, -97.01), GeoPoint(33.0, -96.95))

    /** A point [meters] north of [p] — perpendicular to an eastbound route. */
    private fun north(p: GeoPoint, meters: Double) =
        GeoPoint(p.lat + meters / 111_320.0, p.lon)

    private fun engineOnRoute() =
        DriveMonitorEngine(chain, cameras = emptyList(), routePolyline = routeLine)

    @Test
    fun `driving along the planned line never reports off-route`() {
        val engine = engineOnRoute()
        // Slight wander well inside tolerance, as real GPS does.
        for (offset in listOf(0.0, 15.0, -20.0, 30.0, -10.0)) {
            val p = north(GeoPoint(33.0, -96.99), offset)
            val signals = engine.onLocation(update(p))
            assertTrue(
                signals.none { it is DriveSignal.OffRoute },
                "normal GPS wander of ${offset} m must not read as off-route",
            )
        }
    }

    @Test
    fun `a single bad fix does not cry wolf`() {
        val engine = engineOnRoute()
        // One wild fix (under a bridge, beside a building) then back on.
        val far = engine.onLocation(update(north(GeoPoint(33.0, -96.99), 300.0)))
        assertTrue(far.none { it is DriveSignal.OffRoute }, "one outlier must not trigger it")
        val back = engine.onLocation(update(GeoPoint(33.0, -96.99)))
        assertTrue(back.none { it is DriveSignal.OffRoute })
    }

    @Test
    fun `a sustained departure reports off-route once, with the distance`() {
        val engine = engineOnRoute()
        val off = north(GeoPoint(33.0, -96.99), 300.0)
        // Default requires 3 consecutive fixes beyond 80 m.
        assertTrue(engine.onLocation(update(off)).none { it is DriveSignal.OffRoute })
        assertTrue(engine.onLocation(update(off)).none { it is DriveSignal.OffRoute })
        val signal = engine.onLocation(update(off)).filterIsInstance<DriveSignal.OffRoute>().single()
        assertTrue(signal.metersOffRoute > 250, "reported ${signal.metersOffRoute} m")

        // Already reported: it must not repeat on every subsequent fix.
        assertTrue(
            engine.onLocation(update(off)).none { it is DriveSignal.OffRoute },
            "off-route must be reported once per departure, not per fix",
        )
    }

    @Test
    fun `rejoining the route reports back-on-route and can trigger again later`() {
        val engine = engineOnRoute()
        val off = north(GeoPoint(33.0, -96.99), 300.0)
        repeat(3) { engine.onLocation(update(off)) }

        val rejoin = engine.onLocation(update(GeoPoint(33.0, -96.985)))
        assertTrue(rejoin.any { it is DriveSignal.BackOnRoute }, "rejoining must be reported")

        // A second departure must be detected afresh.
        repeat(2) { engine.onLocation(update(off)) }
        assertTrue(
            engine.onLocation(update(off)).any { it is DriveSignal.OffRoute },
            "a later departure must be caught too",
        )
    }

    @Test
    fun `hysteresis stops chatter at the boundary`() {
        val engine = engineOnRoute()
        // Sit just beyond the off-route threshold to trigger it.
        val off = north(GeoPoint(33.0, -96.99), 300.0)
        repeat(3) { engine.onLocation(update(off)) }
        // Now hover between the two thresholds (45 m .. 80 m): still off route,
        // but neither re-reported nor cleared.
        val between = north(GeoPoint(33.0, -96.99), 60.0)
        val signals = engine.onLocation(update(between))
        assertTrue(signals.none { it is DriveSignal.OffRoute }, "must not re-report")
        assertTrue(signals.none { it is DriveSignal.BackOnRoute }, "60 m is not rejoined")
    }

    @Test
    fun `with no route line, off-route detection is simply inactive`() {
        // The chain alone doesn't describe the roads taken, so without the line
        // we must not guess — and must not fire spurious alerts.
        val engine = DriveMonitorEngine(chain, cameras = emptyList())
        val signals = engine.onLocation(update(north(GeoPoint(33.0, -96.99), 5_000.0)))
        assertTrue(signals.none { it is DriveSignal.OffRoute })
    }

    // ---- The driver's own stops -----------------------------------------
    //
    // The vehicle parks at every waypoint, which is why shaping pins are shed
    // early. A stop the driver asked for is the opposite: shedding it means
    // driving straight past where they wanted to be.

    @Test
    fun `a real stop is not shed early the way a shaping pin is`() {
        val engine = DriveMonitorEngine(
            chain, cameras = emptyList(), stopPoints = setOf(w1),
        )
        // Well inside the lead distance that would drop a shaping pin (450 m).
        val signals = engine.onLocation(update(west(w1, 300.0)))
        assertTrue(
            signals.none { it is DriveSignal.ApproachingWaypoint },
            "a stop must not be dropped on approach",
        )
        assertTrue(signals.none { it is DriveSignal.ReachedStop }, "not there yet either")
    }

    @Test
    fun `arriving at a stop reports it and carries the rest of the trip`() {
        val engine = DriveMonitorEngine(
            chain, cameras = emptyList(), stopPoints = setOf(w1),
        )
        engine.onLocation(update(west(w1, 300.0)))
        val reached = engine.onLocation(update(west(w1, 20.0)))
            .filterIsInstance<DriveSignal.ReachedStop>()
            .single()
        assertEquals(w1, reached.stop)
        assertEquals(listOf(w2, dest), reached.remaining, "the rest of the trip must follow")
    }

    @Test
    fun `shaping pins on the same trip still advance early`() {
        // w1 is a real stop; w2 is a shaping pin and must behave as before.
        val engine = DriveMonitorEngine(
            chain, cameras = emptyList(), stopPoints = setOf(w1),
        )
        engine.onLocation(update(west(w1, 20.0))) // pass the stop
        val signals = engine.onLocation(update(west(w2, 400.0)))
        assertTrue(
            signals.any { it is DriveSignal.ApproachingWaypoint },
            "the shaping pin must still be shed early",
        )
    }
}
