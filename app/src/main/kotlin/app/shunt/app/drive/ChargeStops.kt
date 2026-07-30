package app.shunt.app.drive

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.haversineMeters
import app.shunt.tesla.ActiveRoute

/**
 * A charging stop the **car** chose, not one we asked for.
 *
 * On a long trip Tesla's own trip planner silently inserts a Supercharger and
 * navigates there first. Shunt can't stop that and shouldn't: the car knows its
 * own state of charge and we don't. What we can do is notice, and route the leg
 * to that charger ourselves so the camera avoidance still applies to the roads
 * actually driven.
 */
data class ChargeStop(
    val name: String,
    val at: GeoPoint,
    /** Battery % the car predicts on arrival, when it reports one. */
    val batteryPercentOnArrival: Double?,
)

/** What a read of the car's active route says about the trip it is driving. */
sealed interface ChargeProbe {
    /** The car is heading where we sent it — no charging stop in the way. */
    data object DirectToDestination : ChargeProbe

    /** The car is heading somewhere else first: it inserted a charging stop. */
    data class StopInserted(val stop: ChargeStop) : ChargeProbe

    /**
     * Nothing usable came back — offline, asleep, or not navigating. Explicitly
     * *not* "no charging stop": treating an unreadable car as a clean trip is
     * the same class of mistake as treating unavailable camera data as "no
     * cameras", so callers must leave the current leg alone.
     */
    data object Unknown : ChargeProbe
}

/**
 * Turns a raw [ActiveRoute] into a [ChargeProbe] by comparing where the car
 * says it is going with where we told it to go.
 *
 * The comparison is positional, never by name: a car reports its destination
 * however Tesla feels like labelling it, and a name match would be a guess.
 * Coordinates are unambiguous.
 */
object ChargeStopReading {

    /**
     * Within this of the final destination counts as "the same place". Tesla
     * snaps a shared destination to its own nearest address/POI, which moves it
     * by a block or two; a Supercharger detour is miles away, so this threshold
     * has an enormous margin on both sides.
     */
    const val SAME_PLACE_METERS = 800.0

    /** Used when the car reports a stop but no name for it. */
    const val UNNAMED_STOP = "a charging stop"

    fun classify(route: ActiveRoute?, finalDestination: GeoPoint): ChargeProbe {
        if (route == null || !route.isNavigating) return ChargeProbe.Unknown
        val lat = route.latitude ?: return ChargeProbe.Unknown
        val lon = route.longitude ?: return ChargeProbe.Unknown
        val heading = GeoPoint(lat, lon)
        if (haversineMeters(heading, finalDestination) <= SAME_PLACE_METERS) {
            return ChargeProbe.DirectToDestination
        }
        return ChargeProbe.StopInserted(
            ChargeStop(
                name = route.destinationName?.takeIf { it.isNotBlank() } ?: UNNAMED_STOP,
                at = heading,
                batteryPercentOnArrival = route.energyAtArrival,
            ),
        )
    }

    /** Two reported stops are the same charger if they're essentially co-located. */
    fun isSameStop(a: ChargeStop, b: ChargeStop): Boolean =
        haversineMeters(a.at, b.at) <= SAME_PLACE_METERS
}

/**
 * When it is safe to re-assert the final destination to the car.
 *
 * A probe is not free. To find out whether the car still intends to charge we
 * have to hand it the *final* destination for a few seconds, which is exactly
 * long enough for its screen to show a different route than the one Shunt is
 * steering. So probes only happen where a momentarily different instruction
 * can't do any harm: well clear of the next shaping waypoint (no imminent
 * manoeuvre to get wrong), well clear of any camera we're warning about, and on
 * the planned route in the first place.
 */
data class ProbeWindow(
    /** Never *redirect* the car more often than this while under way. */
    val minIntervalMillis: Long = 5 * 60_000,
    /**
     * Between push-free reads — when the car already holds the final
     * destination there is nothing to re-assert, so asking is nearly free and
     * can be frequent. It needs to be: a Tesla doesn't work out its charging
     * stops until the car is actually put into drive, so the answer at the
     * moment Go is tapped is always "no charging stop" and only becomes true a
     * little way into the journey.
     */
    val readIntervalMillis: Long = 45_000,
    /** ...and often once parked at a charger, waiting for the car to re-plan. */
    val parkedIntervalMillis: Long = 60_000,
    /** Metres of clear road needed before the next shaping waypoint. */
    val clearOfWaypointMeters: Double = 3_000.0,
    /** Metres of clear road needed from the nearest camera on the route. */
    val clearOfCameraMeters: Double = 1_500.0,
) {
    /**
     * [metersToNextWaypoint] and [metersToNearestCamera] are null when there is
     * none left — which is clear road, not a blocker.
     */
    fun isSafeUnderWay(
        millisSinceLastProbe: Long,
        metersToNextWaypoint: Double?,
        metersToNearestCamera: Double?,
        offRoute: Boolean,
    ): Boolean {
        if (offRoute) return false
        if (millisSinceLastProbe < minIntervalMillis) return false
        if (metersToNextWaypoint != null && metersToNextWaypoint < clearOfWaypointMeters) return false
        if (metersToNearestCamera != null && metersToNearestCamera < clearOfCameraMeters) return false
        return true
    }

    /**
     * Parked at a charger there is no manoeuvre to disrupt and nothing to drive
     * past, so only the interval applies — we're just waiting for the car to
     * decide what it's doing next.
     */
    fun isSafeParked(millisSinceLastProbe: Long): Boolean =
        millisSinceLastProbe >= parkedIntervalMillis

    /**
     * A push-free read redirects nothing, so there is no manoeuvre to disrupt
     * and the waypoint gate doesn't apply. It does still occupy the monitor for
     * the length of a network call, so it holds off next to a camera: a warning
     * the driver needs now outranks knowing about a charging stop a few seconds
     * sooner.
     */
    fun isSafeToRead(
        millisSinceLastProbe: Long,
        metersToNearestCamera: Double?,
        offRoute: Boolean,
    ): Boolean {
        if (offRoute) return false
        if (millisSinceLastProbe < readIntervalMillis) return false
        return metersToNearestCamera == null || metersToNearestCamera >= clearOfCameraMeters
    }
}
