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
    fun `a route that comes back near itself does not flush the whole chain`() {
        // **From a real drive: "the first waypoint triggered way too soon and
        // the rest of them all got sent to my car at once".**
        //
        // The route runs east, turns north, and comes back west a short way
        // above the outbound leg — the shape of the re-planned route in that
        // log. Sitting at the start, the car is within a hundred metres of the
        // *return* leg, and the off-route check falls back to a full scan of the
        // line whenever the window finds nothing close. That scan answers "how
        // far from the line am I", which is a different question from "how far
        // along am I", and using its answer for both made the car read as
        // kilometres further on than it was. Every pin behind that point then
        // measured zero metres away and advanced, one per fix.
        val out = (0..20).map { east(origin, it * 100.0) } // 2 km east
        val up = (1..3).map { north(out.last(), it * 40.0) } // 120 m north
        val back = (1..20).map { north(east(origin, 2_000.0 - it * 100.0), 120.0) }
        val road = out + up + back
        val pins = listOf(east(origin, 700.0), east(origin, 1_400.0), east(origin, 2_000.0))
        val engine = DriveMonitorEngine(
            chain = pins + north(origin, 120.0),
            cameras = emptyList(),
            routePolyline = road,
        )

        // Parked a little off the start — enough that the window scan misses and
        // the full scan runs, which is what put the old code on the return leg.
        val signals = engine.onLocation(update(north(east(origin, 20.0), 100.0)))
        assertTrue(
            signals.none { it is DriveSignal.ApproachingWaypoint },
            "the car is at the start; nothing on the chain has been reached",
        )
        // And it does not unravel over the next few fixes either.
        repeat(4) { i ->
            val more = engine.onLocation(update(north(east(origin, 20.0 + i * 5.0), 100.0)))
            assertTrue(
                more.none { it is DriveSignal.ApproachingWaypoint },
                "the chain flushed while the car sat at the start",
            )
        }
    }

    @Test
    fun `the advance fires at the same distance whatever the car is doing`() {
        // **The lead is the expected speed, not the current one.** Asked for as
        // "let's make waypoint triggers static based on expected speed", after
        // "waypoints are triggered way earlier than it shows on the map": a lead
        // that tracked the speedometer moved every trigger point continuously,
        // so the marks drawn described a moment that had already gone.
        val lead = DriveMonitorConfig().let {
            it.expectedSpeedMetersPerSec * it.waypointLeadSeconds
        }
        for (speed in listOf(0.0, 8.0, 40.0, null)) {
            val short = DriveMonitorEngine(chain, emptyList())
            assertTrue(
                short.onLocation(update(west(w1, lead + 200.0), speed = speed)).none {
                    it is DriveSignal.ApproachingWaypoint
                },
                "advanced from beyond the lead at speed $speed",
            )
            val near = DriveMonitorEngine(chain, emptyList())
            assertTrue(
                near.onLocation(update(west(w1, lead - 100.0), speed = speed)).any {
                    it is DriveSignal.ApproachingWaypoint
                },
                "did not advance inside the lead at speed $speed",
            )
        }
    }

    @Test
    fun `the floor holds where the gap would give almost no lead`() {
        // The lead is capped at a share of the gap the pins were placed at, and
        // then floored: a pin the monitor cannot re-aim before is worse than one
        // it re-aims at slightly early.
        val engine = DriveMonitorEngine(chain, emptyList())
        assertTrue(engine.leadMetersFor(0) >= DriveMonitorConfig().waypointLeadMinMeters)
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

    @Test
    fun `a waypoint the car has driven away from is given up on`() {
        // Reported: "during navigation, it can still get caught up on a previous
        // waypoint and I'll have to exit and restart navigation to fix." The
        // along-route gates cannot fire once the driver leaves the route —
        // progress is forward-only and windowed, so it stops advancing and the
        // pin sticks for the rest of the drive.
        val line = (0..59).map { east(origin, it * 100.0) }
        val pin = line[20]
        val engine = DriveMonitorEngine(
            chain = listOf(pin, line.last()),
            routePolyline = line,
            cameras = emptyList(),
        )
        // Drive up to the pin but never inside the lead, then head away north.
        engine.onLocation(LocationUpdate(line[10], speedMetersPerSec = 25.0, bearingDegrees = 90.0))
        var signals = emptyList<DriveSignal>()
        var away = line[19]
        repeat(20) {
            away = north(away, 300.0)
            signals = engine.onLocation(
                LocationUpdate(away, speedMetersPerSec = 25.0, bearingDegrees = 0.0),
            )
            if (signals.any { it is DriveSignal.ApproachingWaypoint }) return
        }
        kotlin.test.fail("the monitor never let go of a pin it had driven away from")
    }

    @Test
    fun `an ordinary bend does not count as driving past a waypoint`() {
        // The guard must not fire on a route that swings away before coming
        // back — which is every switchback and every motorway interchange.
        val line = (0..59).map { east(origin, it * 100.0) }
        val engine = DriveMonitorEngine(
            chain = listOf(line[40], line.last()),
            routePolyline = line,
            cameras = emptyList(),
        )
        // Two fixes pointing away, then back on course — nowhere near the
        // sustained run the guard needs.
        repeat(2) {
            engine.onLocation(LocationUpdate(line[5], speedMetersPerSec = 25.0, bearingDegrees = 270.0))
        }
        val signals = engine.onLocation(
            LocationUpdate(line[10], speedMetersPerSec = 25.0, bearingDegrees = 90.0),
        )
        assertTrue(
            signals.none { it is DriveSignal.ApproachingWaypoint },
            "a pin still well ahead was abandoned: $signals",
        )
    }

    @Test
    fun `turns along the route are reported for the charging gate`() {
        // East, then a right-angle turn north.
        val eastLimb = (0..29).map { east(origin, it * 100.0) }
        val corner = eastLimb.last()
        val line = eastLimb + (1..30).map { north(corner, it * 100.0) }
        val engine = DriveMonitorEngine(
            chain = listOf(line.last()),
            routePolyline = line,
            cameras = emptyList(),
        )
        engine.onLocation(LocationUpdate(line[5], speedMetersPerSec = 25.0, bearingDegrees = 90.0))

        val ahead = engine.metersToNextTurn(line[5])
        assertTrue(ahead != null && ahead > 2_000.0, "the turn is about 2.4 km ahead, got $ahead")
        assertTrue(engine.metersSinceLastTurn(line[5]) == null, "no turn has been passed yet")
    }

    @Test
    fun `each waypoint's trigger sits a lead short of it`() {
        val line = (0..59).map { east(origin, it * 100.0) }
        val engine = DriveMonitorEngine(
            chain = listOf(line[20], line[40], line.last()),
            routePolyline = line,
            cameras = emptyList(),
        )
        engine.onLocation(update(line[0]))

        val triggers = engine.triggerPoints()
        // Two intermediate pins; the destination is arrived at, not advanced
        // past, so it has no trigger.
        assertEquals(2, triggers.size, "got $triggers")
        for ((i, pin) in listOf(line[20], line[40]).withIndex()) {
            val short = haversineMeters(triggers[i], pin)
            assertTrue(short > 0.0, "trigger ${triggers[i]} sits on top of its pin")
            assertTrue(short <= 1_000.0, "trigger for pin $i is $short m short, too far back")
        }
    }

    @Test
    fun `the trigger does not move with the car's speed`() {
        // **Asked for in these words:** "let's make waypoint triggers static
        // based on expected speed." A lead that tracked the speedometer made
        // every mark on the map a moving target — the driver's report was
        // "waypoints are triggered way earlier than it shows on the map", which
        // is what a rule whose answer changes between looking and arriving feels
        // like from the seat.
        val line = (0..59).map { east(origin, it * 100.0) }
        fun triggerAt(speed: Double): GeoPoint {
            val engine = DriveMonitorEngine(
                chain = listOf(line[40], line.last()),
                routePolyline = line,
                cameras = emptyList(),
            )
            engine.onLocation(update(line[0], speed = speed))
            return engine.triggerPoints().first()
        }
        assertEquals(
            triggerAt(8.0),
            triggerAt(30.0),
            "the trigger must be the same place whatever the car is doing",
        )
    }

    @Test
    fun `a shorter gap between pins still shortens the lead`() {
        // Static does not mean uniform. Spacing already tightens with density,
        // so the geometry carries what the speedometer used to stand in for —
        // and it carries it before the drive rather than during it.
        val line = (0..99).map { east(origin, it * 100.0) }
        fun leadFor(gapPoints: Int): Double {
            val engine = DriveMonitorEngine(
                chain = listOf(line[50], line[50 + gapPoints], line.last()),
                routePolyline = line,
                cameras = emptyList(),
            )
            engine.onLocation(update(line[0]))
            return engine.leadMetersFor(1)
        }
        assertTrue(leadFor(4) < leadFor(40), "a tight gap must give a shorter lead")
    }

    @Test
    fun `progress keeps moving across a segment longer than the search window`() {
        // **The bug behind the trigger reports.** A route's segments are as long
        // as the road is straight, so on a motorway they run to kilometres. The
        // progress window was measured from the *start of the current segment*,
        // which put the next segment beyond it — so progress saturated at the
        // end of the segment the car was on and stopped there for good.
        //
        // From then on everything measured along the route was wrong in both
        // directions at once: pins ahead never got closer and never fired, and
        // pins whose own position was behind the frozen point read as reached
        // and fired all at once.
        val a = origin
        val b = east(a, 6_000.0)
        val c = east(a, 12_000.0)
        val engine = DriveMonitorEngine(
            chain = listOf(c),
            routePolyline = listOf(a, b, c),
            cameras = emptyList(),
        )

        var previous = Double.MAX_VALUE
        for (step in 0..59) {
            val at = east(a, step * 200.0)
            engine.onLocation(update(at))
            val left = engine.metersToEnd(at)
            assertTrue(left < previous, "progress stalled at $left m with the car ${step * 200} m along")
            previous = left
        }
        assertTrue(previous < 400.0, "never got near the end: $previous m short")
    }

    @Test
    fun `the ring is drawn exactly where the waypoint fires, on a long segment`() {
        // **The two were computed by different arithmetic.** The advance uses
        // `alongOf`, which projects the car into its segment and is accurate to
        // the metre. The mark was drawn by rounding forward to the next vertex —
        // so on a segment as long as a straight motorway run, it landed at the
        // far end, and the waypoint fired correctly a long way before the driver
        // reached the ring. Reported as "waypoints firing before I hit the
        // trigger point".
        val a = origin
        val b = east(a, 8_000.0)
        val c = east(a, 16_000.0)
        val pin = c
        val engine = DriveMonitorEngine(
            chain = listOf(pin, east(a, 20_000.0)),
            routePolyline = listOf(a, b, c, east(a, 20_000.0)),
            cameras = emptyList(),
        )
        engine.onLocation(update(a))
        val ring = engine.triggerPoints().first()

        // Walk up to the ring: nothing should fire before it.
        var firedAt: Double? = null
        for (step in 1..190) {
            val metres = step * 100.0
            val at = east(a, metres)
            val signals = engine.onLocation(update(at))
            if (signals.any { it is DriveSignal.ApproachingWaypoint }) {
                firedAt = metres
                break
            }
        }
        val fired = firedAt ?: kotlin.test.fail("the waypoint never fired")
        val ringAt = haversineMeters(a, ring)
        assertTrue(
            kotlin.math.abs(fired - ringAt) <= 150.0,
            "fired at $fired m but the ring is drawn at $ringAt m",
        )
    }

    @Test
    fun `two pins inside one long segment do not fire together`() {
        // **Reported exactly:** "the pin triggers early and then the next pin
        // after that fires shortly after." Snapping each pin to its nearest
        // route *vertex* gave both pins in a long segment the same along-route
        // position — so their gap was zero, and the moment one advanced the next
        // was already inside its lead.
        val a = origin
        val far = east(a, 20_000.0)
        // One 20 km segment, with two pins 4 km apart inside it.
        val first = east(a, 8_000.0)
        val second = east(a, 12_000.0)
        val engine = DriveMonitorEngine(
            chain = listOf(first, second, far),
            routePolyline = listOf(a, far),
            cameras = emptyList(),
        )

        val firedAt = mutableListOf<Double>()
        for (step in 0..199) {
            val metres = step * 100.0
            val signals = engine.onLocation(update(east(a, metres)))
            repeat(signals.count { it is DriveSignal.ApproachingWaypoint }) { firedAt += metres }
        }

        assertTrue(firedAt.size >= 2, "expected both pins to fire, got $firedAt")
        assertTrue(
            firedAt[1] - firedAt[0] > 2_000.0,
            "the two pins are 4 km apart but fired ${firedAt[1] - firedAt[0]} m apart: $firedAt",
        )
    }

    @Test
    fun `a trigger sits past its turn, never on it`() {
        // Reported from the map: "trigger points need to be after turns not at
        // them." At the junction the car still has the choice the pin was placed
        // to remove.
        val corner = east(origin, 3_000.0)
        val eastLimb = (0..29).map { east(origin, it * 100.0) }
        val line = eastLimb + (1..30).map { north(corner, it * 100.0) }
        // A pin well past the corner, so the commit point is what binds.
        val pin = north(corner, 800.0)
        val engine = DriveMonitorEngine(
            chain = listOf(pin, line.last()),
            routePolyline = line,
            cameras = emptyList(),
        )
        engine.onLocation(update(line[2]))

        val trigger = engine.triggerPoints().first()
        // Past the corner means north of it, by a clear margin.
        val northOfCorner = haversineMeters(corner, trigger)
        assertTrue(
            trigger.lat > corner.lat,
            "the trigger is at or before the turn, not past it",
        )
        assertTrue(
            northOfCorner >= DriveMonitorConfig().turnCommitClearanceMeters - 1.0,
            "the trigger is only $northOfCorner m past the turn",
        )
    }

    @Test
    fun `an engine extending another starts where the car already is`() {
        // **The reported skip.** An extension's polyline is the leg being driven
        // plus the new one, so the car is far along a line the replacement
        // engine would otherwise think it had not started. Progress used to
        // restart at zero and creep, so nothing advanced for a minute and then
        // every pin behind the car fired on consecutive fixes.
        val line = (0..200).map { east(origin, it * 100.0) }
        val pin = line[150]
        val carriedFrom = DriveMonitorEngine(
            chain = listOf(pin, line.last()),
            routePolyline = line,
            cameras = emptyList(),
        )
        // Drive 14 km on the first engine.
        for (step in 0..140) carriedFrom.onLocation(update(east(origin, step * 100.0)))

        val extended = DriveMonitorEngine(
            chain = listOf(pin, line.last()),
            routePolyline = line,
            cameras = emptyList(),
            startSegment = carriedFrom.progressAt,
        )
        val at = east(origin, 14_000.0)
        extended.onLocation(update(at))

        assertTrue(
            extended.metersToEnd(at) < 6_500.0,
            "the replacement engine lost the car: ${extended.metersToEnd(at)} m to the end",
        )
    }

    @Test
    fun `progress cannot jump further than the car has driven`() {
        // A fixed window let progress move a kilometre in one fix — thirty times
        // a second's driving — which is enough to put several pins behind the car
        // at once wherever the route runs back near itself.
        val line = (0..200).map { east(origin, it * 100.0) }
        val engine = DriveMonitorEngine(
            chain = listOf(line.last()),
            routePolyline = line,
            cameras = emptyList(),
        )
        engine.onLocation(update(origin))
        val startLeft = engine.metersToEnd(origin)

        // A 30 m step must not move progress by anything like a kilometre.
        val next = east(origin, 30.0)
        engine.onLocation(update(next))
        val moved = startLeft - engine.metersToEnd(next)
        assertTrue(moved < DriveMonitorEngine.PROGRESS_MIN_STEP_METERS + 100.0, "progress jumped $moved m")
    }
}
