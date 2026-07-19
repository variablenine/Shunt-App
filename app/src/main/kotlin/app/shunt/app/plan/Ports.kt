package app.shunt.app.plan

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Freshness
import app.shunt.solver.here.Suggestion
import app.shunt.solver.routing.SolveResult

/**
 * The small seams the plan layer depends on, so it can be driven by fakes in
 * unit tests without HTTP, Android, or a car. Concrete adapters over the
 * :solver / :tesla types are wired in AppContainer.
 */

/** Destination search (HERE autosuggest). */
fun interface SuggestionSearch {
    suspend fun suggest(query: String, at: GeoPoint): List<Suggestion>
}

/** The routing policy (RouteSolver). */
fun interface RoutePlanner {
    suspend fun solve(origin: GeoPoint, destination: GeoPoint): SolveResult
}

/** Where the trip starts from, and the autosuggest bias point. */
fun interface LocationProvider {
    /** Best available origin, or null if none is known yet. */
    suspend fun currentOrigin(): GeoPoint?
}

/** Warms and reports camera-data freshness for an area (called on app open). */
fun interface CameraGateway {
    suspend fun refresh(around: GeoPoint): Freshness
}

/** Persists the Home/Work favorites. */
interface FavoritesStore {
    fun load(): Favorites
    fun save(favorites: Favorites)
}
