package app.shunt.app.drive

import app.shunt.core.GeoPoint
import app.shunt.tesla.ActiveRoute
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ChargeStopsTest {

    private val destination = GeoPoint(39.0, -98.0)

    private fun route(
        lat: Double?,
        lon: Double?,
        name: String? = "somewhere",
        energy: Double? = null,
    ) = ActiveRoute(
        destinationName = name,
        latitude = lat,
        longitude = lon,
        milesToArrival = null,
        minutesToArrival = null,
        energyAtArrival = energy,
        batteryLevel = null,
        estimatedRangeMiles = null,
    )

    @Test
    fun `a car heading where we sent it has no charging stop`() {
        val probe = ChargeStopReading.classify(route(39.0, -98.0), destination)
        assertEquals(ChargeProbe.DirectToDestination, probe)
    }

    @Test
    fun `a destination snapped a block or two away is still the same place`() {
        // Tesla resolves a shared point to its own nearest address, which moves
        // it. That must not read as the car having inserted a stop.
        val nudged = route(39.0 + 300.0 / 111_320.0, -98.0)
        assertEquals(ChargeProbe.DirectToDestination, ChargeStopReading.classify(nudged, destination))
    }

    @Test
    fun `a destination miles away is the car's own charging stop`() {
        val charger = route(39.4, -98.3, name = "Supercharger Anytown")
        val probe = assertIs<ChargeProbe.StopInserted>(ChargeStopReading.classify(charger, destination))
        assertEquals("Supercharger Anytown", probe.stop.name)
        assertEquals(GeoPoint(39.4, -98.3), probe.stop.at)
    }

    @Test
    fun `an unreadable car is Unknown, never a clean trip`() {
        // The whole safety argument: "couldn't read it" must not become
        // "no charging stop", the same way missing camera data must not become
        // "no cameras".
        assertEquals(ChargeProbe.Unknown, ChargeStopReading.classify(null, destination))
        assertEquals(ChargeProbe.Unknown, ChargeStopReading.classify(route(null, null), destination))
        assertEquals(ChargeProbe.Unknown, ChargeStopReading.classify(route(39.4, null), destination))
    }

    @Test
    fun `an unnamed stop still reports a usable label`() {
        val probe = assertIs<ChargeProbe.StopInserted>(
            ChargeStopReading.classify(route(39.4, -98.3, name = "  "), destination),
        )
        assertEquals(ChargeStopReading.UNNAMED_STOP, probe.stop.name)
    }

    // ---- Probe window ----------------------------------------------------

    private val window = ProbeWindow()

    @Test
    fun `probing waits for clear road ahead`() {
        val elapsed = window.minIntervalMillis + 1

        assertTrue(
            window.isSafeUnderWay(elapsed, metersToNextWaypoint = 9_000.0, metersToNearestCamera = 9_000.0, offRoute = false),
        )
        assertFalse(
            window.isSafeUnderWay(elapsed, metersToNextWaypoint = 500.0, metersToNearestCamera = 9_000.0, offRoute = false),
            "a waypoint is imminent — a momentary different instruction could cost the turn",
        )
        assertFalse(
            window.isSafeUnderWay(elapsed, metersToNextWaypoint = 9_000.0, metersToNearestCamera = 300.0, offRoute = false),
            "a camera is close — this is the worst possible moment to redirect the car",
        )
        assertFalse(
            window.isSafeUnderWay(elapsed, metersToNextWaypoint = 9_000.0, metersToNearestCamera = 9_000.0, offRoute = true),
            "already off route — sort that out before probing",
        )
    }

    @Test
    fun `probing respects the minimum interval`() {
        assertFalse(window.isSafeUnderWay(1_000, 9_000.0, 9_000.0, offRoute = false))
    }

    @Test
    fun `nothing left ahead counts as clear road, not as a blocker`() {
        assertTrue(
            window.isSafeUnderWay(window.minIntervalMillis, null, null, offRoute = false),
        )
    }

    @Test
    fun `a free read is gated on cameras but not on waypoints`() {
        val elapsed = window.readIntervalMillis
        // Nothing is being redirected, so an imminent turn is not a reason to
        // wait — but a close camera still is: the read occupies the monitor.
        assertTrue(window.isSafeToRead(elapsed, metersToNearestCamera = 9_000.0, offRoute = false))
        assertFalse(window.isSafeToRead(elapsed, metersToNearestCamera = 300.0, offRoute = false))
        assertFalse(window.isSafeToRead(elapsed - 1, metersToNearestCamera = 9_000.0, offRoute = false))
        assertFalse(window.isSafeToRead(elapsed, metersToNearestCamera = null, offRoute = true))
    }

    @Test
    fun `free reads come round far more often than re-asserts`() {
        // The car only plans its charging once in drive, so the free read has
        // to be frequent enough to catch that happening.
        assertTrue(window.readIntervalMillis < window.minIntervalMillis)
    }

    @Test
    fun `parked at a charger only the interval applies`() {
        // Distances are meaningless when stopped — the car is on top of the
        // waypoint it just reached, which would otherwise block forever.
        assertTrue(window.isSafeParked(window.parkedIntervalMillis))
        assertFalse(window.isSafeParked(window.parkedIntervalMillis - 1))
    }

    @Test
    fun `a probe holds off before a turn`() {
        // Reported: "we can't have it check for charging if there are any
        // potential turns around that the car would take if navigating directly
        // to the destination… we also can't have that happen right before a
        // turn either." A probe re-asserts the destination, so for a few
        // seconds the car is navigating its own way there.
        val window = ProbeWindow()
        val clear = window.isSafeUnderWay(
            millisSinceLastProbe = 10 * 60_000,
            metersToNextWaypoint = 9_000.0,
            metersToNearestCamera = 9_000.0,
            offRoute = false,
            metersToNextTurn = 9_000.0,
            metersSinceLastTurn = 9_000.0,
        )
        assertTrue(clear, "open road with nothing near should probe")

        assertFalse(
            window.isSafeUnderWay(
                millisSinceLastProbe = 10 * 60_000,
                metersToNextWaypoint = 9_000.0,
                metersToNearestCamera = 9_000.0,
                offRoute = false,
                metersToNextTurn = window.clearOfTurnMeters - 1,
                metersSinceLastTurn = 9_000.0,
            ),
            "a turn just ahead must block the probe",
        )
        assertFalse(
            window.isSafeUnderWay(
                millisSinceLastProbe = 10 * 60_000,
                metersToNextWaypoint = 9_000.0,
                metersToNearestCamera = 9_000.0,
                offRoute = false,
                metersToNextTurn = 9_000.0,
                metersSinceLastTurn = window.clearOfLastTurnMeters - 1,
            ),
            "a turn just behind must block it too — the car is still settling",
        )
    }

    @Test
    fun `a route with no turns left does not block the probe`() {
        // Null means "there is none", which is clear road rather than a blocker
        // — the same convention the waypoint and camera gates use.
        assertTrue(
            ProbeWindow().isSafeUnderWay(
                millisSinceLastProbe = 10 * 60_000,
                metersToNextWaypoint = null,
                metersToNearestCamera = null,
                offRoute = false,
                metersToNextTurn = null,
                metersSinceLastTurn = null,
            ),
        )
    }
}
