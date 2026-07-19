package app.shunt.solver.routing

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.geo.BoundingBox

/** A drivable route candidate, decoded to a polyline. */
data class Route(
    val polyline: List<GeoPoint>,
    val durationSeconds: Int,
    val lengthMeters: Int,
)

/**
 * The routing backend seam. Production is HERE Routing v8
 * ([app.shunt.solver.here.HereRoutingClient]); tests script this directly.
 */
interface RoutingApi {
    /**
     * Request routes from [origin] to [destination]. [avoidAreas] must not
     * exceed [MAX_AVOID_AREAS] (HERE's `avoid[areas]` cap). [alternatives]
     * is the number of extra routes requested beyond the first.
     */
    suspend fun routes(
        origin: GeoPoint,
        destination: GeoPoint,
        avoidAreas: List<BoundingBox> = emptyList(),
        alternatives: Int = 0,
    ): List<Route>

    companion object {
        /** HERE Routing v8 accepts up to 20 `!`-separated bounding boxes. */
        const val MAX_AVOID_AREAS = 20
    }
}

/**
 * The outcome of a solve. MinimumExposure is an expected, navigable result —
 * not an error — and the UI must present it as such: no camera-free route
 * exists, and this is the least-bad option, stated plainly.
 */
sealed interface SolveResult {
    /** Fastest route that passes zero cameras. */
    data class Clean(
        val route: Route,
        val addedSecondsVsFastest: Int,
        val waypoints: List<GeoPoint>,
    ) : SolveResult

    /**
     * No camera-free route was found. This route passes the fewest distinct
     * cameras (ties broken by travel time); every camera it passes is listed.
     */
    data class MinimumExposure(
        val route: Route,
        val passedCameras: List<Camera>,
        val addedSecondsVsFastest: Int,
        val waypoints: List<GeoPoint>,
    ) : SolveResult

    /** No route at all (unroutable pair, backend failure, …). */
    data class Failed(val reason: String) : SolveResult
}
