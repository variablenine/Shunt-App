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
