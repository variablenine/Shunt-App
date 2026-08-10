package app.shunt.app.drive

import app.shunt.solver.waypoints.WaypointExtractor
import app.shunt.solver.waypoints.WaypointRefiner
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pin constants in `:solver` and the advance rule in this module are one
 * mechanism, and nothing else can see both halves of it — `:solver` is
 * Android-free and cannot import [DriveMonitorConfig], so the coupling lives here or
 * nowhere.
 *
 * **The rule.** The monitor stops aiming at a pin and re-aims at the next one as
 * soon as the car is within `max(waypointLeadMinMeters, speed × waypointLeadSeconds)`
 * of it. So a pin closer to a fork than that lead distance is abandoned *before
 * the car reaches the fork*, and the turn it existed to force is no longer
 * forced. Same for two pins closer together than the lead: the second is
 * advanced past before the car ever aims at it, so they are one constraint.
 *
 * This was live for most of the project. `PAST_FORK_METERS` was 250 m against a
 * highway lead of ~563 m, so on any fast road the pin was dropped 313 m before
 * the fork — the "long stretches where the car doesn't follow the route"
 * report. Changing the monitor's numbers silently re-breaks it, which is what
 * these tests exist to prevent.
 */
class PinSpacingMatchesMonitorTest {

    private val config = DriveMonitorConfig()

    /** What the monitor uses, mirrored from `DriveMonitorEngine.advanceOrArrive`. */
    private fun leadMetersAt(speedMetersPerSec: Double): Double =
        maxOf(config.waypointLeadMinMeters, speedMetersPerSec * config.waypointLeadSeconds)

    private fun mph(x: Double) = x * 0.44704

    @Test
    fun `an open-road pin clears the fork by more than the monitor's highway lead`() {
        val lead = leadMetersAt(mph(70.0))
        assertTrue(
            WaypointRefiner.PAST_FORK_METERS >= lead,
            "at 70 mph the monitor re-aims ${lead} m out, so a pin " +
                "${WaypointRefiner.PAST_FORK_METERS} m past a fork is abandoned before the turn",
        )
        assertTrue(
            WaypointExtractor.MIN_PIN_SPACING_METERS >= lead,
            "pins ${WaypointExtractor.MIN_PIN_SPACING_METERS} m apart are one constraint at $lead m of lead",
        )
    }

    @Test
    fun `a dense-area pin clears the fork by more than the monitor's city lead`() {
        // 30 mph is the fast end of what a grid with a camera every block is
        // driven at; taking the faster end is the conservative choice, since
        // the lead grows with speed.
        val lead = leadMetersAt(mph(30.0))
        assertTrue(
            WaypointRefiner.DENSE_PAST_FORK_METERS >= lead,
            "at 30 mph the monitor re-aims ${lead} m out, so a pin " +
                "${WaypointRefiner.DENSE_PAST_FORK_METERS} m past a fork is abandoned before the turn",
        )
        assertTrue(
            WaypointExtractor.DENSE_PIN_SPACING_METERS >= lead,
            "pins ${WaypointExtractor.DENSE_PIN_SPACING_METERS} m apart are one constraint at $lead m of lead",
        )
    }

    @Test
    fun `the dense end is genuinely tighter than the open-road end`() {
        // Otherwise the density scale is decoration and a city gets highway pins.
        assertTrue(WaypointRefiner.DENSE_PAST_FORK_METERS < WaypointRefiner.PAST_FORK_METERS)
        assertTrue(WaypointExtractor.DENSE_PIN_SPACING_METERS < WaypointExtractor.MIN_PIN_SPACING_METERS)
    }
}
