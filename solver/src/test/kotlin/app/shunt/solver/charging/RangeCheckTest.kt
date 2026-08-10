package app.shunt.solver.charging

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RangeCheckTest {

    private fun miles(m: Double) = (m * RangeEstimate.METERS_PER_MILE).toInt()

    @Test
    fun `usable range holds a reserve back`() {
        // The car's figure is `est_battery_range`, which already has real
        // driving in it, so the margin is the reserve and nothing else.
        val usable = RangeEstimate.usableMeters(100.0)
        val raw = 100 * RangeEstimate.METERS_PER_MILE
        assertTrue(usable < raw, "you must not plan to arrive on empty")
        assertEquals(
            raw * RangeEstimate.RANGE_TRUST_FRACTION - RangeEstimate.RESERVE_METERS,
            usable,
            absoluteTolerance = 0.5,
        )
    }

    @Test
    fun `the real reading that used to cry wolf now reads as tight`() {
        // From a real car: the equivalent of 160 miles reported at 55%, against
        // a 241 km camera-avoiding route. Deraing an already-real-world estimate
        // by a further quarter turned "makes it with 17 km in hand" into a red
        // "not enough range". Tight is the honest answer — and the driver's.
        val check = RangeEstimate.of(
            routeMeters = 240_900,
            shortestOptionMeters = 204_800,
            estimatedRangeMiles = 160.4,
            batteryPercent = 55,
        )!!
        assertEquals(RangeCheck.Level.TIGHT, check.level, "it fits, and only just")
        assertEquals(0.0, check.shortfallMeters, "so there is no shortfall to report")
        assertFalse(check.detourIsTheProblem)
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
        // 100 estimated miles -> 90 usable once the reserve is held back;
        // 84 miles is 93% of that.
        val check = RangeEstimate.of(miles(84.0), miles(80.0), estimatedRangeMiles = 100.0, batteryPercent = 40)!!
        assertEquals(RangeCheck.Level.TIGHT, check.level)
    }

    @Test
    fun `a trip past the usable range is short`() {
        // 100 estimated miles is 90 usable; 105 is past it by any reading.
        val check = RangeEstimate.of(miles(105.0), miles(100.0), estimatedRangeMiles = 100.0, batteryPercent = 40)!!
        assertEquals(RangeCheck.Level.SHORT, check.level)
        assertTrue(check.shortfallMeters > 0)
    }

    @Test
    fun `the detour is named as the problem when the direct route would have made it`() {
        // The case that motivates this whole check: 30 estimated miles is 20
        // usable. The direct route fits; the camera-avoiding one doesn't.
        val check = RangeEstimate.of(
            routeMeters = miles(24.0),
            shortestOptionMeters = miles(18.0),
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
    fun `splitting a trip at a charging stop is judged leg by leg`() {
        // The bug this fixes: a trip that needs a charge stayed "not enough
        // range" after one was added, because the check compared the whole
        // 250-mile trip against 65 miles of range instead of looking at the
        // legs it had just been broken into.
        val whole = RangeEstimate.of(
            routeMeters = miles(250.0),
            shortestOptionMeters = miles(240.0),
            estimatedRangeMiles = 100.0,
            batteryPercent = 100,
        )!!
        assertEquals(RangeCheck.Level.SHORT, whole.level, "unbroken, this trip cannot be made")

        val split = RangeEstimate.of(
            routeMeters = miles(250.0),
            shortestOptionMeters = miles(240.0),
            estimatedRangeMiles = 100.0,
            batteryPercent = 100,
            // Eight comfortable legs rather than one impossible run. A charge
            // to 80% of a 100-mile car is about 50 usable miles, so the legs
            // have to be well inside that — not merely shorter than the total.
            legMeters = List(8) { miles(31.25) },
        )!!
        assertEquals(RangeCheck.Level.FINE, split.level, "each leg fits, so the trip does")
        assertTrue(split.hasChargingStops)
        assertEquals(0.0, split.shortfallMeters)
    }

    @Test
    fun `a leg past what a charge covers is still called short`() {
        val check = RangeEstimate.of(
            routeMeters = miles(300.0),
            shortestOptionMeters = miles(290.0),
            estimatedRangeMiles = 100.0,
            batteryPercent = 100,
            // The second leg is far too long for one charge.
            legMeters = listOf(miles(50.0), miles(250.0)),
        )!!
        assertEquals(RangeCheck.Level.SHORT, check.level)
        assertTrue(check.shortfallMeters > 0, "the long leg's shortfall must be reported")
    }

    @Test
    fun `the first leg runs on the battery you have, not on a charge`() {
        // Setting off on 20% and stopping to charge: the first leg is limited
        // by what is in the car now, the rest by what a stop puts back.
        val check = RangeEstimate.of(
            routeMeters = miles(120.0),
            shortestOptionMeters = miles(120.0),
            estimatedRangeMiles = 40.0, // 20% of a 200-mile car
            batteryPercent = 20,
            legMeters = listOf(miles(60.0), miles(60.0)),
        )!!
        assertTrue(
            check.chargedUsableMeters > check.usableMeters,
            "a charge must buy more range than is in the battery now",
        )
        assertEquals(RangeCheck.Level.SHORT, check.level, "the first leg outruns the current charge")
        assertEquals(
            listOf(true, false),
            check.legShortfalls.map { it > 0 },
            "only the first leg should be over",
        )
    }

    @Test
    fun `the detour explanation stops applying once a charging stop is added`() {
        val check = RangeEstimate.of(
            routeMeters = miles(15.0),
            shortestOptionMeters = miles(11.0),
            estimatedRangeMiles = 30.0,
            batteryPercent = 12,
            legMeters = listOf(miles(7.0), miles(8.0)),
        )!!
        assertFalse(
            check.detourIsTheProblem,
            "with a charging stop the comparison is against a different trip",
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
