package app.shunt.solver.charging

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RangeCheckTest {

    private fun miles(m: Double) = (m * RangeEstimate.METERS_PER_MILE).toInt()

    @Test
    fun `usable range is derated and holds a reserve back`() {
        // 100 rated miles is not 100 real miles, and you must not arrive on 0%.
        val usable = RangeEstimate.usableMeters(100.0)
        assertTrue(usable < 100 * RangeEstimate.METERS_PER_MILE * 0.8)
        assertEquals(
            100 * RangeEstimate.METERS_PER_MILE * RangeEstimate.REAL_WORLD_FRACTION -
                RangeEstimate.RESERVE_METERS,
            usable,
            absoluteTolerance = 0.5,
        )
    }

    @Test
    fun `a nearly empty battery never reports negative usable range`() {
        assertEquals(0.0, RangeEstimate.usableMeters(1.0))
    }

    @Test
    fun `an unknown range makes no claim in either direction`() {
        assertNull(RangeEstimate.of(miles(50.0), miles(40.0), estimatedRangeMiles = null, batteryPercent = 42))
        assertNull(RangeEstimate.of(miles(50.0), miles(40.0), estimatedRangeMiles = 0.0, batteryPercent = 42))
    }

    @Test
    fun `a short trip on a full battery is fine`() {
        val check = RangeEstimate.of(miles(20.0), miles(18.0), estimatedRangeMiles = 200.0, batteryPercent = 80)!!
        assertEquals(RangeCheck.Level.FINE, check.level)
        assertFalse(check.detourIsTheProblem)
        assertEquals(0.0, check.shortfallMeters)
    }

    @Test
    fun `a trip that only just fits is called tight, not fine`() {
        // 100 rated miles -> 65 usable; 60 miles is 92% of that.
        val check = RangeEstimate.of(miles(60.0), miles(58.0), estimatedRangeMiles = 100.0, batteryPercent = 40)!!
        assertEquals(RangeCheck.Level.TIGHT, check.level)
    }

    @Test
    fun `a trip past the usable range is short`() {
        val check = RangeEstimate.of(miles(90.0), miles(85.0), estimatedRangeMiles = 100.0, batteryPercent = 40)!!
        assertEquals(RangeCheck.Level.SHORT, check.level)
        assertTrue(check.shortfallMeters > 0)
    }

    @Test
    fun `the detour is named as the problem when the direct route would have made it`() {
        // The case that motivates this whole check: 30 rated miles is about
        // 12.5 usable. The direct route fits; the camera-avoiding one doesn't.
        val check = RangeEstimate.of(
            routeMeters = miles(15.0),
            shortestOptionMeters = miles(11.0),
            estimatedRangeMiles = 30.0,
            batteryPercent = 12,
        )!!
        assertEquals(RangeCheck.Level.SHORT, check.level)
        assertTrue(
            check.detourIsTheProblem,
            "when the short option fits and the chosen one doesn't, that IS the finding",
        )
    }

    @Test
    fun `a trip too long for any option is not blamed on the detour`() {
        val check = RangeEstimate.of(
            routeMeters = miles(200.0),
            shortestOptionMeters = miles(180.0),
            estimatedRangeMiles = 100.0,
            batteryPercent = 40,
        )!!
        assertEquals(RangeCheck.Level.SHORT, check.level)
        assertFalse(check.detourIsTheProblem, "no option fits — charging is needed either way")
    }
}
