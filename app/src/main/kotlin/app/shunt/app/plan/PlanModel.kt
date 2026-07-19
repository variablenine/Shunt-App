package app.shunt.app.plan

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.camera.Freshness
import app.shunt.solver.here.Suggestion
import app.shunt.solver.routing.SolveResult

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
)

/**
 * The single screen state. [phase] drives what's shown; [query]/[suggestions]
 * and [favorites] are always available so the search bar and favorite chips
 * persist across phases.
 */
data class PlanUiState(
    val query: String = "",
    val suggestions: List<Suggestion> = emptyList(),
    val favorites: Favorites = Favorites(),
    val cameraDataFreshness: Freshness? = null,
    val phase: Phase = Phase.Browsing,
) {
    /** Camera data came only from the bundled offline snapshot. */
    val usingOfflineCameraData: Boolean get() = cameraDataFreshness == Freshness.BUNDLED
}

/** Where the plan flow is: browse → solve → result → push. */
sealed interface Phase {
    /** Entering a destination. */
    data object Browsing : Phase

    /** Solver running for [destination]. */
    data class Solving(val destination: Destination) : Phase

    /** Solver returned; the result card is showing. */
    data class Solved(val destination: Destination, val result: SolveResult) : Phase

    /** Go tapped; pushing the route to the vehicle. */
    data class Pushing(val destination: Destination, val result: SolveResult) : Phase

    /**
     * Route accepted by the vehicle and the drive monitor is running: GPS is
     * tracked, waypoints advance on approach, and cameras/failures alert
     * locally until arrival or cancel.
     */
    data class Driving(val destination: Destination, val plan: DrivePlan) : Phase

    /** The push failed. [retryable] mirrors PushResult so the UI can offer retry. */
    data class PushFailed(
        val destination: Destination,
        val result: SolveResult,
        val reason: String,
        val retryable: Boolean,
    ) : Phase

    /** Something upstream failed (no origin, backend error). */
    data class Error(val message: String) : Phase
}
