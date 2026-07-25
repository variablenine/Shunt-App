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
    /** Speed assumed when the fix carries none, for the lead computation. */
    val assumedSpeedMetersPerSec: Double = 25.0,
    /** Within this of the destination counts as arrived. */
    val arrivalRadiusMeters: Double = 60.0,
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

    /** advanceTo failed mid-drive — the car may still stop at the passed waypoint. */
    data class AdvanceFailed(
        val remaining: List<GeoPoint>,
        val reason: String,
        val retryable: Boolean,
    ) : Alert {
        override val severity get() = Severity.URGENT
    }

    data object Arrived : Alert {
        override val severity get() = Severity.INFO
    }

    enum class Severity { INFO, WARNING, URGENT }
}

/** Sink for local alerts (haptics + notifications). Faked in tests. */
fun interface Alerter {
    fun alert(alert: Alert)
}

/** Coarse drive lifecycle for the UI to reflect. */
sealed interface DriveStatus {
    data object Idle : DriveStatus
    data class Driving(val destinationTitle: String) : DriveStatus
    data object Arrived : DriveStatus
}
