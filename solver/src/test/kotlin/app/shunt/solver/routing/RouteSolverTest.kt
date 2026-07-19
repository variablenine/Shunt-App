package app.shunt.solver.routing

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.geo.BoundingBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Policy tests against a scripted RoutingApi. Routes run west→east around
 * lat 45; cameras sit directly on specific corridors.
 */
class RouteSolverTest {

    private val origin = GeoPoint(45.0, -88.2)
    private val destination = GeoPoint(45.0, -87.8)

    /** A straight corridor at the given latitude. */
    private fun corridor(lat: Double, durationSeconds: Int, lengthMeters: Int = 30_000): Route =
        Route(
            polyline = (0..40).map { GeoPoint(lat, -88.2 + it * 0.01) },
            durationSeconds = durationSeconds,
            lengthMeters = lengthMeters,
        )

    private fun cameraAt(id: Long, lat: Double, lon: Double, tags: Map<String, String> = emptyMap()) =
        Camera(id, GeoPoint(lat, lon), tags)

    /** Scripted backend: responses[i] answers the i-th call; last response repeats. */
    private class ScriptedApi(private val responses: List<List<Route>>) : RoutingApi {
        val calls = mutableListOf<List<BoundingBox>>()
        override suspend fun routes(
            origin: GeoPoint, destination: GeoPoint,
            avoidAreas: List<BoundingBox>, alternatives: Int,
        ): List<Route> {
            calls += avoidAreas
            return responses[minOf(calls.size - 1, responses.lastIndex)]
        }
    }

    private fun solver(api: RoutingApi, cameras: List<Camera>, config: SolverConfig = SolverConfig()) =
        RouteSolver(api, { bbox -> cameras.filter { bbox.contains(it.location) } }, config)

    @Test
    fun `clean on first try`() = runTest {
        val fastest = corridor(45.0, 600)
        val api = ScriptedApi(listOf(listOf(fastest)))
        val result = solver(api, cameras = emptyList()).solve(origin, destination)
        val clean = assertIs<SolveResult.Clean>(result)
        assertEquals(0, clean.addedSecondsVsFastest)
        assertTrue(clean.waypoints.isEmpty(), "no divergence, no waypoints")
    }

    @Test
    fun `camera on fastest route is excluded and clean alternative wins`() = runTest {
        val dirty = corridor(45.0, 600)
        val clean = corridor(45.05, 800) // parallel corridor 5.5 km north
        val camera = cameraAt(7, 45.0, -88.0) // sits on the dirty corridor
        val api = ScriptedApi(listOf(listOf(dirty), listOf(clean)))
        val result = solver(api, listOf(camera)).solve(origin, destination)
        val outcome = assertIs<SolveResult.Clean>(result)
        assertEquals(800, outcome.route.durationSeconds)
        assertEquals(200, outcome.addedSecondsVsFastest)
        // The second call must have carried the camera's avoidance box.
        assertEquals(1, api.calls[1].size)
        assertTrue(api.calls[1][0].contains(camera.location))
        assertTrue(outcome.waypoints.isNotEmpty(), "divergent route must be pinned")
        assertTrue(outcome.waypoints.size <= 5)
    }

    @Test
    fun `no clean route falls back to minimum exposure with explicit cameras`() = runTest {
        // Both corridors pass cameras; south passes 1, north passes 2.
        val south = corridor(45.0, 900)
        val north = corridor(45.05, 600)
        val cams = listOf(
            cameraAt(1, 45.0, -88.0),
            cameraAt(2, 45.05, -88.1), cameraAt(3, 45.05, -87.9),
        )
        // Every exclusion attempt keeps returning the same two corridors.
        val api = ScriptedApi(listOf(listOf(north, south)))
        val result = solver(api, cams, SolverConfig(maxExclusionRounds = 3)).solve(origin, destination)
        val fallback = assertIs<SolveResult.MinimumExposure>(result)
        assertEquals(900, fallback.route.durationSeconds, "fewest cameras wins over faster")
        assertEquals(listOf(1L), fallback.passedCameras.map { it.id })
        assertEquals(300, fallback.addedSecondsVsFastest)
    }

    @Test
    fun `minimum exposure ties broken by duration`() = runTest {
        val south = corridor(45.0, 900)
        val north = corridor(45.05, 600)
        val cams = listOf(cameraAt(1, 45.0, -88.0), cameraAt(2, 45.05, -88.1))
        val api = ScriptedApi(listOf(listOf(north, south)))
        val result = solver(api, cams, SolverConfig(maxExclusionRounds = 2)).solve(origin, destination)
        val fallback = assertIs<SolveResult.MinimumExposure>(result)
        assertEquals(600, fallback.route.durationSeconds, "same count: faster route wins")
        assertEquals(listOf(2L), fallback.passedCameras.map { it.id })
    }

    @Test
    fun `exclusion becoming unroutable falls back instead of failing`() = runTest {
        val only = corridor(45.0, 600)
        val cams = listOf(cameraAt(1, 45.0, -88.0))
        // First call returns the corridor; the avoidance call returns nothing.
        val api = ScriptedApi(listOf(listOf(only), emptyList()))
        val result = solver(api, cams).solve(origin, destination)
        val fallback = assertIs<SolveResult.MinimumExposure>(result)
        assertEquals(listOf(1L), fallback.passedCameras.map { it.id })
    }

    @Test
    fun `avoid areas never exceed backend cap`() = runTest {
        val corridorRoute = corridor(45.0, 600)
        // 30 cameras strung along the corridor — more than the 20-box cap.
        val cams = (1..30).map { cameraAt(it.toLong(), 45.0, -88.2 + it * 0.012) }
        val api = ScriptedApi(listOf(listOf(corridorRoute)))
        solver(api, cams, SolverConfig(maxExclusionRounds = 2)).solve(origin, destination)
        api.calls.forEach { assertTrue(it.size <= RoutingApi.MAX_AVOID_AREAS, "got ${it.size} areas") }
        assertTrue(api.calls.any { it.size == RoutingApi.MAX_AVOID_AREAS })
    }

    @Test
    fun `no route at all fails explicitly`() = runTest {
        val api = ScriptedApi(listOf(emptyList()))
        val result = solver(api, emptyList()).solve(origin, destination)
        val failed = assertIs<SolveResult.Failed>(result)
        assertTrue(failed.reason.contains("no route"))
    }

    @Test
    fun `strict direction ignores camera facing away from travel`() = runTest {
        val route = corridor(45.0, 600) // travels east, heading ~90
        val awayCamera = cameraAt(1, 45.0, -88.0, mapOf("direction" to "270"))
        val api = ScriptedApi(listOf(listOf(route)))
        val strict = solver(api, listOf(awayCamera), SolverConfig(strictDirection = true))
        assertIs<SolveResult.Clean>(strict.solve(origin, destination))

        // Same camera facing the direction of travel blocks.
        val facingCamera = cameraAt(1, 45.0, -88.0, mapOf("direction" to "90"))
        val api2 = ScriptedApi(listOf(listOf(route)))
        val strict2 = solver(api2, listOf(facingCamera), SolverConfig(strictDirection = true, maxExclusionRounds = 1))
        assertIs<SolveResult.MinimumExposure>(strict2.solve(origin, destination))
    }

    @Test
    fun `default config ignores direction tags entirely`() = runTest {
        val route = corridor(45.0, 600)
        val awayCamera = cameraAt(1, 45.0, -88.0, mapOf("direction" to "270"))
        val api = ScriptedApi(listOf(listOf(route)))
        val result = solver(api, listOf(awayCamera), SolverConfig(maxExclusionRounds = 1))
            .solve(origin, destination)
        assertIs<SolveResult.MinimumExposure>(result)
    }
}
