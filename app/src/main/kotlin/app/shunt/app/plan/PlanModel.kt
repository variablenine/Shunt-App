package app.shunt.app.plan

import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.PlannedRoute
import app.shunt.solver.camera.Camera
import app.shunt.solver.camera.Freshness
import app.shunt.solver.charging.RangeCheck
import app.shunt.solver.search.Suggestion

/** A place the user can route to: a search result or a saved favorite. */
data class Destination(
    val title: String,
    val location: GeoPoint,
    /**
     * Whether this is somewhere the car will plug in.
     *
     * **It changes how early the car is told about it.** A Tesla preconditions
     * the battery on the way to a charger, and it can only do that if the
     * charger is the destination it is navigating to — which, while Shunt is
     * steering a single-destination car pin by pin, it never is. See
     * `DriveMonitor.handoverMetersFor`.
     */
    val charging: Boolean = false,
) {
    companion object {
        fun of(suggestion: Suggestion) = Destination(suggestion.title, suggestion.location)
    }
}

/** The two one-tap favorites; this app is used on the same handful of trips. */
data class Favorites(val home: Destination? = null, val work: Destination? = null)

enum class FavoriteSlot { HOME, WORK }

/** Everything the drive monitor needs to run a trip, handed over at Go. */
data class DrivePlan(
    val destination: Destination,
    /** Waypoint chain: intermediate pins followed by the destination (last). */
    val chain: List<GeoPoint>,
    /** Unavoidable cameras to warn about (empty for a clean route). */
    val cameras: List<Camera>,
    /** The route line, for the map / notification context. */
    val polyline: List<GeoPoint>,
    /**
     * Which chain entries are the driver's own stops rather than shaping pins.
     * Shaping pins must be dropped before the car reaches them (it treats every
     * waypoint as a stop); real stops must not be — the driver wants to stop.
     */
    val stopPoints: Set<GeoPoint> = emptySet(),
    /**
     * The car took only the destination, not the shaped chain. That is also the
     * only mode in which reading its active route means anything — with a full
     * chain pushed it reports our own waypoints back at us — so it gates the
     * charging-stop watch.
     */
    val destinationOnly: Boolean = false,
    /**
     * Steer the car pin by pin rather than handing it the destination.
     *
     * A car that only accepts one destination cannot be given a shape, so the
     * only way to make it follow one is to point it at the next pin and move
     * the pin as it goes. That is what makes the avoidance reach the car at
     * all — but it also means the car is aiming at somewhere a few miles away
     * and will not plan charging for the real trip, so it is only used when the
     * trip clearly doesn't need a charge.
     */
    val steerByWaypoints: Boolean = false,
    /**
     * The rest of the trip, when this plan is only its first leg: the points
     * from this plan's destination to the real one, still to be planned.
     *
     * A long trip is cut at a camera-free point on the direct road so the driver
     * can set off in ten seconds instead of two minutes (see `LegSplitter`), and
     * the remainder is planned while the car is already moving. Empty means this
     * plan reaches the destination.
     */
    val remaining: List<GeoPoint> = emptyList(),
) {
    /** Whether this plan stops short of where the driver is actually going. */
    val isPartial: Boolean get() = remaining.isNotEmpty()
}

/**
 * The single screen state. [phase] drives what's shown; [query]/[suggestions]
 * and [favorites] are always available so the search bar and favorite chips
 * persist across phases.
 */
data class PlanUiState(
    val query: String = "",
    /**
     * Stops to visit before the destination, in order. Empty for a direct trip.
     * These are places the driver actually wants to be — unlike the shaping
     * waypoints the router adds, they are never dropped or reordered.
     */
    val stops: List<Destination> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
    /** A search request is in flight (debounce elapsed, awaiting results). */
    val searching: Boolean = false,
    /** Destination search couldn't be reached (offline / service error). */
    val searchFailed: Boolean = false,
    val favorites: Favorites = Favorites(),
    /** Places routed to before, newest first. See [recentsShown]. */
    val recents: List<Destination> = emptyList(),
    val cameraDataFreshness: Freshness? = null,
    /**
     * How the route on the chooser compares with the car's remaining range.
     * Null whenever no claim can be made: no car connected, or unreadable.
     */
    val rangeCheck: RangeCheck? = null,
    /**
     * The car's range is being read right now.
     *
     * Distinct from a null [rangeCheck], and the distinction is the point: null
     * means "no claim can be made" — no car, or the read failed — whereas this
     * means "ask again in a moment". Collapsing the two is how Go came to set
     * off steering a trip that had not been checked for charging yet.
     */
    val checkingRange: Boolean = false,
    /** A charging stop is being looked for (the "charge on the way" tap). */
    val findingChargeStop: Boolean = false,
    /** The last charging-stop search came back with nothing usable. */
    val chargeStopSearchFailed: Boolean = false,
    /** Other reachable sites from the last lookup, for explicit manual choice. */
    val chargeStopAlternatives: List<Destination> = emptyList(),
    /**
     * Which of [stops] are charging stops rather than places the driver asked
     * for. The range check needs to know: a leg starting from a charger has a
     * charge to work with, one starting from a coffee stop does not.
     */
    val chargeStops: Set<GeoPoint> = emptySet(),
    /** Charging sites near the planned route, for picking one off the map. */
    val chargersOnRoute: List<Destination> = emptyList(),
    /**
     * Legs of a long trip planned after the first one, newest last.
     *
     * A long trip is cut into legs so the driver can set off quickly, and these
     * are the ones that landed afterwards. They are drawn on the map as they
     * arrive so the line visibly grows to the destination — from a standstill as
     * readily as while driving, because the phone plans them either way.
     */
    val laterLegs: List<PlannedRoute> = emptyList(),
    /**
     * Whether more legs are still on the way, which the map shows as a dashed
     * line running on to the destination. Distinct from `laterLegs` being
     * short: planning that has *stopped* short must not keep promising more.
     */
    val planningLaterLegs: Boolean = false,
    /**
     * The shown leg's line, shortened because the leg after it doubled back
     * over its tail. Null when no trim was needed, which is the usual case.
     * See `LegJoin`.
     */
    val trimmedLeadPolyline: List<GeoPoint>? = null,
    /** That leg's pins with any on the trimmed spur removed. See `LegJoin.pinsOn`. */
    val trimmedLeadWaypoints: List<GeoPoint>? = null,
    /**
     * The direct road onward from the end of the last leg planned, for the
     * dashed pending line. Empty until a later leg lands, when the chooser's own
     * `Phase.Solved.directAhead` still describes the same boundary.
     */
    val laterLegDirectAhead: List<GeoPoint> = emptyList(),
    /**
     * The pin the car is aiming at while a drive is running, for the map's
     * follow camera. Null when nothing is being driven.
     */
    val aimedAt: GeoPoint? = null,
    /** Where each waypoint ahead will be handed to the car. See RouteMap. */
    val waypointTriggers: List<GeoPoint> = emptyList(),
    val phase: Phase = Phase.Browsing,
) {
    /** Camera data came only from the bundled offline snapshot. */
    val usingOfflineCameraData: Boolean get() = cameraDataFreshness == Freshness.BUNDLED

    /**
     * The recent places worth offering right now: all of them before a key is
     * pressed, and the matching ones once there is something to match.
     *
     * Recents are the one search result that costs nothing and cannot be
     * missing. A keyless geocoder takes about a second and does not know the
     * local diner; somewhere the driver has already been is known instantly and
     * for certain. Since this app is used on the same handful of trips, that is
     * the fastest path to most destinations — and it keeps working with no
     * signal at all, which nothing else in search does.
     *
     * One list rather than two so the row a finger lands on is the row that gets
     * used: the UI shows this and the selection callback indexes the same thing.
     */
    val recentsShown: List<Destination>
        get() = if (query.isBlank()) recents else recents.filter { it.matches(query) }
}

/**
 * Whether this place is a plausible answer to [query] — every word typed
 * appears somewhere in its title.
 *
 * Word-wise and unordered on purpose. Someone looking for "Birch Street Diner"
 * types "diner birch" as readily as the full name, and a prefix match on the
 * whole string would find neither.
 */
private fun Destination.matches(query: String): Boolean {
    val words = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return false
    val haystack = title.lowercase()
    return words.all { it in haystack }
}

/** Where the plan flow is: browse → solve → choose → push. */
sealed interface Phase {
    /** Entering a destination. */
    data object Browsing : Phase

    /**
     * Routing running for [destination]. [progress] is 0f..1f and [step] names
     * the current stage, so a long cross-state plan shows real movement.
     */
    data class Solving(
        val destination: Destination,
        val progress: Float = 0f,
        val step: String = "Planning your route",
    ) : Phase

    /**
     * The offline map tile for this trip isn't downloaded yet. We route fully
     * on-device, so we prompt a download rather than silently going online.
     */
    data class NeedTile(
        val destination: Destination,
        val downloading: Boolean = false,
        val progress: Float = 0f,
        val failed: Boolean = false,
    ) : Phase

    /**
     * Routing returned options; the chooser is showing. [selected] indexes
     * [options] (fastest first) — the route drawn and the one Go will push.
     */
    data class Solved(
        val destination: Destination,
        val options: List<PlannedRoute>,
        val selected: Int = 0,
        /** Where planning time went. Temporary diagnostic — see [PlanTimings]. */
        val timings: app.shunt.solver.brouter.PlanTimings? = null,
        /**
         * The rest of the trip when these options are only its first leg, and
         * the trip's whole length by the direct road. Both null/empty for a trip
         * planned in one go. See `LegSplitter`.
         */
        val remaining: List<GeoPoint> = emptyList(),
        val wholeTripMeters: Int? = null,
        /**
         * These options came from an earlier, narrower round of the search
         * because the round after a corridor widen ran out of time. They are
         * labelled honestly against the full camera set; what is given up is the
         * claim that they are the best routes for it. See CLAUDE.md §7.10.
         */
        val carriedForward: Boolean = false,
        /** The direct road onward from the leg boundary, for the pending line. */
        val directAhead: List<GeoPoint> = emptyList(),
        /** How many leading points of [directAhead] are road rather than estimate. */
        val directAheadRoadPoints: Int = 0,
    ) : Phase {
        val chosen: PlannedRoute get() = options[selected.coerceIn(options.indices)]
    }

    /** Go tapped; pushing the chosen route to the vehicle. */
    data class Pushing(val destination: Destination, val option: PlannedRoute) : Phase

    /**
     * Route accepted by the vehicle and the drive monitor is running: GPS is
     * tracked, waypoints advance on approach, and cameras/failures alert
     * locally until arrival or cancel.
     */
    data class Driving(
        val destination: Destination,
        val plan: DrivePlan,
        /**
         * The car accepted only the destination, not the shaped route — so it
         * is navigating its own way and may pass cameras this route avoided.
         * Shunt's own warnings still follow the planned route.
         */
        val destinationOnly: Boolean = false,
    ) : Phase

    /** The push failed. [retryable] mirrors PushResult so the UI can offer retry. */
    data class PushFailed(
        val destination: Destination,
        val option: PlannedRoute,
        val reason: String,
        val retryable: Boolean,
    ) : Phase

    /** Something upstream failed (no origin, backend error). */
    data class Error(val message: String) : Phase
}
