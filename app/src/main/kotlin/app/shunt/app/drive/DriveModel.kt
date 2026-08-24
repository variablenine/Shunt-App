package app.shunt.app.drive

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera

/** One GPS fix. Speed and bearing may be absent (e.g. cold GPS). */
data class LocationUpdate(
    val point: GeoPoint,
    val speedMetersPerSec: Double? = null,
    val bearingDegrees: Double? = null,
)

/** Which side of the direction of travel something is on. */
enum class Side { LEFT, RIGHT }

/**
 * What the pure engine decides from a location fix. All local — nothing here
 * needs the network, which is what lets camera warnings survive offline.
 */
sealed interface DriveSignal {
    /**
     * The vehicle is within lead distance of the next waypoint. Call
     * advanceTo([remaining]) to drop it before the car treats it as a stop.
     */
    data class ApproachingWaypoint(val remaining: List<GeoPoint>) : DriveSignal

    /** Nearing an unavoidable camera. [imminent] is the closer, escalated tier. */
    data class ApproachingCamera(
        val camera: Camera,
        val distanceMeters: Double,
        val side: Side?,
        val imminent: Boolean,
    ) : DriveSignal

    /**
     * The vehicle has left the planned route by more than the configured
     * tolerance, for long enough that it isn't GPS noise. This matters beyond
     * navigation: the route's camera avoidance was computed for a path we are
     * no longer on, so the "camera-free" promise no longer holds.
     * [metersOffRoute] is the current distance from the planned line.
     */
    data class OffRoute(val at: GeoPoint, val metersOffRoute: Double) : DriveSignal

    /** The vehicle rejoined the planned route after being off it. */
    data object BackOnRoute : DriveSignal

    /**
     * Arrived at one of the driver's own stops (not a shaping pin). [remaining]
     * is the rest of the chain, to re-push once they set off again.
     */
    data class ReachedStop(val stop: GeoPoint, val remaining: List<GeoPoint>) : DriveSignal

    /** Reached the final destination. */
    data object Arrived : DriveSignal
}

/**
 * Tuning for the drive monitor. Waypoint lead is time-based (fire early, not
 * at the pin) with a distance floor for low speed; both are configurable.
 */
data class DriveMonitorConfig(
    /** Fire the advance this many seconds before reaching a waypoint. */
    val waypointLeadSeconds: Double = 18.0,
    /** ...but never closer than this, for crawling/stopped traffic. */
    val waypointLeadMinMeters: Double = 150.0,
    /**
     * ...and never more than this share of the gap to the previous waypoint.
     *
     * **The lead and the pin spacing were set independently, and on a fast road
     * through a camera-dense area they contradict each other.** Spacing tightens
     * on *camera density* — 250 m where they are thick — while the lead grows
     * with *speed*: 450 m at 45 mph, 563 m at 70. A 55 mph arterial through a
     * watched corridor therefore gets pins 250 m apart and a lead half a
     * kilometre long, so the monitor re-aims two and three pins ahead at once
     * and the turns they were placed for are never forced. Reported from a real
     * drive as the waypoints being "REALLY sensitive and going way too early".
     *
     * `PinSpacingMatchesMonitorTest` held the old relationship, and held it
     * *under an assumption* — dense spacing checked against a 30 mph lead,
     * open-road spacing against 70. Nothing made a dense stretch a slow one.
     * Capping against the gap the pins were actually placed at makes it
     * structural instead: whatever the spacing, the car aims at a pin for the
     * first part of its approach and re-aims for the last part.
     *
     * A half is the natural place to put it: enough warning that the car does
     * not brake for a waypoint it is not stopping at — ten seconds at motorway
     * speed on 600 m spacing — and never so much that a pin is skipped.
     */
    val waypointLeadGapFraction: Double = 0.5,
    /** Speed assumed when the fix carries none. */
    val assumedSpeedMetersPerSec: Double = 25.0,
    /**
     * The speed the waypoint lead is computed from — **not** the car's current
     * one.
     *
     * **Deliberately static, and asked for in those words:** "waypoints are
     * triggered way earlier than it shows on the map and doesn't really get
     * triggered sometimes… let's make waypoint triggers static based on
     * expected speed."
     *
     * A lead that tracked the speedometer meant every pin's trigger point moved
     * continuously — so the marks drawn on the map described a moment that had
     * already passed by the time the driver looked up, and a burst of speed
     * before a pin fired the advance hundreds of metres earlier than the map
     * had ever shown. Nothing about that is calibratable, which is the whole
     * purpose the marks were added for.
     *
     * Held against the gap the pins were actually placed at, which is what
     * still makes a lead in town shorter than one on open road: spacing already
     * tightens with density, so the geometry carries the information the
     * speedometer was standing in for — and it does so *before* the drive
     * rather than during it.
     */
    val expectedSpeedMetersPerSec: Double = 31.0,
    /** Within this of the destination counts as arrived. */
    val arrivalRadiusMeters: Double = 60.0,
    /**
     * How far behind the direction of travel a waypoint has to be before it
     * counts as one the car has driven past. See
     * [DriveMonitorEngine.strandedOn].
     */
    val passedBehindDegrees: Double = 100.0,
    /**
     * How many consecutive fixes a waypoint must sit behind the car and get
     * further away before the monitor gives up on it.
     *
     * At a fix a second that is a quarter of a minute of driving away from a
     * point the car was supposed to reach — long past any GPS wobble, and well
     * short of the minutes the driver used to spend restarting navigation.
     */
    val passedFixes: Int = 15,
    /**
     * A bend sharper than this counts as a turn the car has to commit to before
     * the waypoint beyond it may be advanced past. See
     * [DriveMonitorEngine.commitPointFor].
     */
    val turnCommitDegrees: Double = 35.0,
    /**
     * How far back from a waypoint to look for that turn.
     *
     * **Must exceed `WaypointRefiner.PAST_FORK_METERS`**, which is where the
     * refiner puts a pin relative to the fork it is holding. It did not: the
     * lookback was 500 m against an open-road fork distance of 600 m, so on a
     * fast road the gate could not see the turn its own pin existed for and
     * never fired. That is the failure this gate was added to prevent, present
     * in the gate itself. `PinSpacingMatchesMonitorTest` holds the relationship
     * now rather than the numbers.
     */
    val turnCommitLookbackMeters: Double = 800.0,
    /**
     * Distance either side of a vertex used to measure how sharply the route
     * bends there. Wide enough that the wobble in a dense polyline does not read
     * as a turn, narrow enough that a real junction does.
     */
    val turnMeasureSpanMeters: Double = 40.0,
    /** First (early) camera warning tier. */
    val cameraWarnMeters: Double = 400.0,
    /** Second (escalated) camera warning tier. */
    val cameraImminentMeters: Double = 150.0,
    /**
     * Farther than this from the planned line counts as off it. Generous: a
     * car's GPS routinely wanders 20 m, and a divided highway or a parallel
     * frontage road can read as tens of metres off without being a wrong turn.
     */
    val offRouteMeters: Double = 80.0,
    /** Within this of the line again counts as rejoined (hysteresis). */
    val backOnRouteMeters: Double = 45.0,
    /**
     * Consecutive fixes beyond [offRouteMeters] before raising it — a single
     * bad fix under a bridge or beside a building must not cry wolf.
     */
    val offRouteConsecutiveFixes: Int = 3,
)

/**
 * A loud, local alert. Every one of these fires haptics + a notification;
 * none require connectivity. This is the whole point of the fallback — on a
 * 2am rural drive with no signal, the camera and failure warnings still land.
 */
sealed interface Alert {
    val severity: Severity

    data class CameraApproaching(
        val camera: Camera,
        val distanceMeters: Double,
        val side: Side?,
        val imminent: Boolean,
    ) : Alert {
        override val severity get() = if (imminent) Severity.URGENT else Severity.WARNING
    }

    /**
     * Left the planned route. Urgent because the camera avoidance was computed
     * for the path we're no longer on: cameras ahead may be unknown to us.
     * [replanning] is true when a replacement route is already being worked out.
     */
    data class OffRoute(val metersOffRoute: Double, val replanning: Boolean) : Alert {
        override val severity get() = Severity.URGENT
    }

    /** A replacement route was found and is now in force. */
    data class Replanned(val camerasOnNewRoute: Int) : Alert {
        override val severity get() = Severity.WARNING
    }

    /** Rejoined the planned route; its camera avoidance applies again. */
    data object BackOnRoute : Alert {
        override val severity get() = Severity.INFO
    }

    /** Arrived at an intermediate stop the driver added. */
    data class ReachedStop(val remainingStops: Int) : Alert {
        override val severity get() = Severity.INFO
    }

    /**
     * We are off the planned route and could not work out a new one, so no
     * camera avoidance is in force at all until the driver rejoins.
     */
    data class ReplanFailed(val reason: String) : Alert {
        override val severity get() = Severity.URGENT
    }

    /**
     * Shunt has stopped commanding the car and handed it back to the driver.
     *
     * Raised when re-planning keeps happening in quick succession, which means
     * the route and the road disagree — a closure, a turn the car won't take,
     * or a driver who has decided to go their own way. Whatever the cause, the
     * loop it produces is the same: Shunt re-plans, pushes the new route, the
     * car turns back towards a road the driver is refusing, the driver leaves
     * it again. A driver trying to take over on the car's own screen loses that
     * fight every time, and the only way out was to cancel navigation in the
     * app.
     *
     * The driver wins. Camera warnings continue, because they cost the car
     * nothing and are the half of Shunt that still works here; nothing further
     * is sent to the vehicle.
     */
    data object StoodDown : Alert {
        override val severity get() = Severity.URGENT
    }

    /**
     * The car inserted a charging stop of its own and Shunt is now driving a
     * camera-aware route to it. Worth saying out loud: the trip the driver
     * asked for has quietly grown a leg they didn't plan.
     */
    data class ChargeStopAhead(val name: String, val camerasOnLeg: Int) : Alert {
        override val severity get() = Severity.WARNING
    }

    /** Reached the car's charging stop; the trip continues after charging. */
    data class ReachedChargeStop(val name: String) : Alert {
        override val severity get() = Severity.INFO
    }

    /** Charging done (or no longer needed) — back on the way to the destination. */
    data class ResumingToDestination(val camerasOnLeg: Int) : Alert {
        override val severity get() = Severity.WARNING
    }

    /**
     * The car is detouring to a charger we could not plan a route to, so it is
     * driving there its own way with no camera avoidance at all.
     */
    data class ChargeStopUnroutable(val name: String) : Alert {
        override val severity get() = Severity.URGENT
    }

    /** Shunt could not verify or restore the car's target during a charging probe. */
    data class ChargingUpdateFailed(val reason: String, val retryable: Boolean) : Alert {
        override val severity get() = Severity.URGENT
    }

    /** advanceTo failed mid-drive — the car may still stop at the passed waypoint. */
    data class AdvanceFailed(
        val remaining: List<GeoPoint>,
        val reason: String,
        val retryable: Boolean,
    ) : Alert {
        override val severity get() = Severity.URGENT
    }

    /**
     * The car is aimed at the right waypoint again after a failed update.
     *
     * The failure is announced urgently and promises a retry, so the recovery
     * has to be announced too — otherwise the last thing the driver was told is
     * that route updates are failing, and they have no way to know it stopped.
     */
    data object AimRestored : Alert {
        override val severity get() = Severity.INFO
    }

    data object Arrived : Alert {
        override val severity get() = Severity.INFO
    }

    /**
     * The end of a leg, with the next one not planned yet.
     *
     * Should be close to unreachable — a boundary is at least an hour's driving
     * from where the trip started and the next leg plans in seconds — but the
     * alternative if it happens is announcing arrival somewhere the driver was
     * not going, which is worse than admitting the app is behind.
     */
    data object LegBoundaryReached : Alert {
        override val severity get() = Severity.WARNING
    }

    enum class Severity { INFO, WARNING, URGENT }
}

/** Sink for local alerts (haptics + notifications). Faked in tests. */
fun interface Alerter {
    fun alert(alert: Alert)
}

/** Coarse drive lifecycle for the UI to reflect. */
/**
 * What Shunt is doing with the car *right now*, for the driver to see.
 *
 * Reported from a real drive: *"it's impossible right now to tell what the app
 * is doing."* Everything on this list already happened — waypoints went out,
 * the car got probed about charging, re-plans fired — but all of it was silent
 * unless something went wrong. That is a bad property for an app that steers a
 * vehicle: a driver who cannot see it working cannot tell a quiet moment from a
 * broken one, and cannot describe what it did afterwards either.
 *
 * Deliberately coarse. This is glanceable text on a screen in a moving car, not
 * a log.
 */
sealed interface DriveActivity {
    /** Nothing in flight; just following the route and watching for cameras. */
    data object Watching : DriveActivity

    /** Moving the car's aim on to the next shaping pin. */
    data class SendingWaypoint(val number: Int, val total: Int) : DriveActivity

    /**
     * The car could not be reached, and Shunt is still trying.
     *
     * Distinct from [SendingWaypoint] because it means something different to a
     * driver: not "a command is in flight" but "the car does not have the right
     * waypoint yet". Out of reception that state can last minutes.
     */
    data class RetryingWaypoint(val number: Int, val total: Int) : DriveActivity

    /** Asking the car whether it has inserted a charging stop. */
    data object CheckingCharging : DriveActivity

    /** Working out a new route because the car left the planned one. */
    data object Replanning : DriveActivity

    /** Shunt has given the car back and is only warning about cameras (§6.1). */
    data object StoodDown : DriveActivity
}

sealed interface DriveStatus {
    data object Idle : DriveStatus

    /**
     * [chargingVia] names the charging stop the car inserted on its own, when
     * it has — the trip is still to [destinationTitle], just not directly.
     */
    data class Driving(val destinationTitle: String, val chargingVia: String? = null) : DriveStatus

    data object Arrived : DriveStatus
}
