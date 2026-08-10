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
