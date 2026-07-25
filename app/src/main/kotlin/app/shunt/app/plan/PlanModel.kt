package app.shunt.app.plan

import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.PlannedRoute
import app.shunt.solver.camera.Camera
import app.shunt.solver.camera.Freshness
import app.shunt.solver.search.Suggestion

/** A place the user can route to: a search result or a saved favorite. */
data class Destination(val title: String, val location: GeoPoint) {
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
)

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
    val cameraDataFreshness: Freshness? = null,
    val phase: Phase = Phase.Browsing,
) {
    /** Camera data came only from the bundled offline snapshot. */
    val usingOfflineCameraData: Boolean get() = cameraDataFreshness == Freshness.BUNDLED
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
