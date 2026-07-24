package app.shunt.app.plan

import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.PlanOutcome
import app.shunt.solver.camera.Freshness
import app.shunt.solver.search.Suggestion

/**
 * The small seams the plan layer depends on, so it can be driven by fakes in
 * unit tests without HTTP, Android, or a car. Concrete adapters over the
 * :solver / :tesla types are wired in AppContainer.
 */

/** Destination search (keyless, OpenStreetMap-based via Photon). */
fun interface SuggestionSearch {
    suspend fun suggest(query: String, at: GeoPoint): List<Suggestion>
}

/** Native, offline camera-aware routing (BRouter) — returns chooseable options. */
fun interface RoutePlanner {
    suspend fun plan(origin: GeoPoint, destination: GeoPoint): PlanOutcome
}

/**
 * Downloads the offline routing tiles a trip needs (full-replace: a missing
 * tile prompts a download rather than falling back). [onProgress] is 0f..1f;
 * returns true when every needed tile is present.
 */
fun interface TileDownloader {
    suspend fun download(
        origin: GeoPoint,
        destination: GeoPoint,
        onProgress: (Float) -> Unit,
    ): Boolean
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
