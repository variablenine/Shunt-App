package app.shunt.app.drive

import app.shunt.core.GeoPoint
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Shunt must not pull the car out of a turn lane.
 *
 * Reported from a real drive: stopped at a red light in a centre lane waiting to
 * turn, a little short of a waypoint just past the junction. The monitor's lead
 * distance floors at 150 m for crawling traffic, the car was inside that and
 * stationary, so the waypoint was advanced past — and the next one lay straight
 * ahead, so FSD moved to leave the turn lane.
 *
 * A waypoint abandoned before the turn it exists to force is worse than no
 * waypoint: it actively steers the car the wrong way, at a junction, under
 * driver assistance.
 */
class TurnCommitAdvanceTest {

    // A road east, a hard left turn north, then on north. The waypoint sits
    // just past the corner — where the refiner puts them — and the next one is
    // further north again.
    //
    //                    ┌── [pin] ── (next)
    //                    │
    //   ──── approach ───┘
    private val metresEast = 1.0 / (111_320.0 * kotlin.math.cos(Math.toRadians(39.0)))
    private val metresNorth = 1.0 / 111_320.0

    private val corner = GeoPoint(39.0, -98.0)

    /** Sampled every 10 m so the turn is a corner, not a smooth curve. */
    private val route: List<GeoPoint> = buildList {
        for (i in 100 downTo 1) add(GeoPoint(39.0, -98.0 - i * 10 * metresEast))
        add(corner)
        for (i in 1..100) add(GeoPoint(39.0 + i * 10 * metresNorth, -98.0))
    }

    /**
     * 130 m past the corner, and 900 m past it: the pin and the one after.
     *
     * The near one matters. Straight-line, a car 40 m short of the corner is
     * 136 m from it — inside the monitor's 150 m stationary floor — while still
     * on the wrong side of the junction. That is the reported situation, and the
     * distances have to be that shape or the test proves nothing.
     */
    private val pin = GeoPoint(39.0 + 130 * metresNorth, -98.0)
    private val nextPin = GeoPoint(39.0 + 900 * metresNorth, -98.0)

    private fun engine() = DriveMonitorEngine(
        chain = listOf(pin, nextPin),
        cameras = emptyList(),
        routePolyline = route,
    )

    /** On the approach road, [metres] short of the corner. */
    private fun approaching(metres: Double) = GeoPoint(39.0, -98.0 - metres * metresEast)

    @Test
    fun `a car stopped in the turn lane does not advance past the waypoint`() {
        val engine = engine()
        // 40 m short of the corner and stationary: 136 m from the pin in a
        // straight line, inside the 150 m floor, and still on the wrong side of
        // the junction.
        val signals = engine.onLocation(LocationUpdate(approaching(40.0), speedMetersPerSec = 0.0))

        assertTrue(
            signals.none { it is DriveSignal.ApproachingWaypoint },
            "advanced before the turn was taken: $signals",
        )
    }

    @Test
    fun `sitting at the light repeatedly still does not advance`() {
        // A red light is many fixes, not one. Nothing may accumulate into an
        // advance while the car has not moved.
        val engine = engine()
        repeat(20) {
            val signals = engine.onLocation(LocationUpdate(approaching(40.0), speedMetersPerSec = 0.0))
            assertTrue(signals.none { it is DriveSignal.ApproachingWaypoint }, "advanced on fix $it")
        }
    }

    @Test
    fun `once round the corner it advances as usual`() {
        val engine = engine()
        engine.onLocation(LocationUpdate(approaching(40.0), speedMetersPerSec = 0.0))
        // Through the turn and heading north, 60 m short of the pin.
        val throughTheTurn = GeoPoint(39.0 + 70 * metresNorth, -98.0)
        val signals = engine.onLocation(LocationUpdate(throughTheTurn, speedMetersPerSec = 8.0))

        assertTrue(
            signals.any { it is DriveSignal.ApproachingWaypoint },
            "the whole point of the lead is to advance before the car stops at the pin: $signals",
        )
    }

    @Test
    fun `passing close to a waypoint the route has not reached does not trigger it`() {
        // Spotted on a planned route through a cloverleaf: the line comes back
        // within metres of itself, so a waypoint on the far pass sits right
        // beside the car while still being a long way off *along the route*.
        // A ruler reads that as arrival; the road says two kilometres to go.
        //
        // The approach to the waypoint is deliberately straight for well over
        // `turnCommitLookbackMeters`, so the turn-commit gate is inactive and
        // the only thing that can prevent the trigger is along-route progress.
        val m = metresNorth
        val e = metresEast
        val loop = buildList {
            for (i in 0..100) add(GeoPoint(39.0, -98.0 + i * 10 * e))            // east 1 km
            for (i in 1..40) add(GeoPoint(39.0 + i * 10 * m, -98.0 + 1000 * e))  // north 400 m
            for (i in 1..40) add(GeoPoint(39.0 + 400 * m, -98.0 + (1000 - i * 10) * e)) // west 400 m
            for (i in 1..100) add(GeoPoint(39.0 + (400 - i * 10) * m, -98.0 + 600 * e)) // south 1 km
        }
        // 300 m south of where the route crossed its own outbound leg — so 300 m
        // from the car by ruler, about 1.9 km by road.
        val late = GeoPoint(39.0 - 300 * m, -98.0 + 600 * e)
        val engine = DriveMonitorEngine(
            chain = listOf(late, GeoPoint(39.0 - 550 * m, -98.0 + 600 * e)),
            cameras = emptyList(),
            routePolyline = loop,
        )

        // On the outbound leg at speed, passing directly north of that waypoint.
        val signals = engine.onLocation(
            LocationUpdate(GeoPoint(39.0, -98.0 + 600 * e), speedMetersPerSec = 25.0),
        )

        assertTrue(
            signals.none { it is DriveSignal.ApproachingWaypoint },
            "a waypoint ~1.9 km further along the route was triggered by proximity: $signals",
        )
    }

    @Test
    fun `a sparse route line still measures progress inside a long hop`() {
        // Measuring along the route means knowing where along it the car is, and
        // rounding that back to the last vertex is exact on a dense line and
        // hopeless on a sparse one. A re-planned leg or a straight hop between
        // junctions is two points a couple of kilometres apart; rounded back,
        // the car reads as sitting at the start of that hop until it reaches the
        // far end, and every waypoint inside it looks kilometres away and never
        // advances. Same trap the planner's `sampleSpine` fell into.
        val hop = listOf(
            GeoPoint(39.0, -98.0),
            GeoPoint(39.0, -98.0 + 2000 * metresEast),
        )
        val atTheEnd = GeoPoint(39.0, -98.0 + 2000 * metresEast)
        val engine = DriveMonitorEngine(
            chain = listOf(atTheEnd, GeoPoint(39.0, -98.0 + 3000 * metresEast)),
            cameras = emptyList(),
            routePolyline = hop,
        )

        // 300 m short of the waypoint at 25 m/s: a 450 m lead, so this advances.
        val signals = engine.onLocation(
            LocationUpdate(GeoPoint(39.0, -98.0 + 1700 * metresEast), speedMetersPerSec = 25.0),
        )
        assertTrue(
            signals.any { it is DriveSignal.ApproachingWaypoint },
            "300 m from the waypoint inside a 2 km hop and it did not advance: $signals",
        )
    }

    @Test
    fun `a straight approach is unaffected`() {
        // No turn to commit to, so nothing should change for the ordinary case.
        val straight = (0..200).map { GeoPoint(39.0, -98.0 + it * 10 * metresEast) }
        val target = GeoPoint(39.0, -98.0 + 1000 * metresEast)
        val engine = DriveMonitorEngine(
            chain = listOf(target, GeoPoint(39.0, -98.0 + 1900 * metresEast)),
            cameras = emptyList(),
            routePolyline = straight,
        )
        val signals = engine.onLocation(
            LocationUpdate(GeoPoint(39.0, -98.0 + 900 * metresEast), speedMetersPerSec = 0.0),
        )
        assertEquals(
            1,
            signals.count { it is DriveSignal.ApproachingWaypoint },
            "a straight road has no commit point and must advance on the lead alone",
        )
    }
}
