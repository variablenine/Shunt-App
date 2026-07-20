package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.geo.BoundingBox
import app.shunt.solver.geo.destinationPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class BrouterPlannerTest {

    private val origin = GeoPoint(39.0, -98.0)
    private val destination = destinationPoint(origin, 90.0, 2_000.0)

    @Test
    fun `missing tiles short-circuit to NeedsDownload before routing`() = runTest {
        var routed = false
        val planner = BrouterPlanner(
            route = { _, _, _ -> routed = true; emptyList() },
            missingTiles = { listOf(TileId(-90, 45)) },
            camerasIn = { emptyList() },
        )
        val outcome = planner.plan(origin, destination)
        assertIs<PlanOutcome.NeedsDownload>(outcome)
        assertEquals(listOf(TileId(-90, 45)), outcome.tiles)
        assertTrue(!routed, "must not route when tiles are missing")
    }

    @Test
    fun `options carry added time and the cameras they actually pass`() = runTest {
        // A straight fastest line through a camera; a detour that misses it.
        val fastLine = listOf(origin, destination)
        val detour = listOf(origin, destinationPoint(origin, 20.0, 2_100.0), destination)
        val onFast = Camera(id = 1, location = destinationPoint(origin, 90.0, 1_000.0))

        val planner = BrouterPlanner(
            route = { _, _, _ ->
                listOf(
                    BrouterRoute(RouteChoice.FASTEST, fastLine, 2_000, 180, 1, 60),
                    BrouterRoute(RouteChoice.FEWEST_CAMERAS, detour, 2_600, 300, 0, 0),
                )
            },
            missingTiles = { emptyList() },
            camerasIn = { listOf(onFast) },
        )
        val outcome = planner.plan(origin, destination)
        assertIs<PlanOutcome.Routes>(outcome)
        val (fast, fewest) = outcome.options

        assertEquals(0, fast.addedSecondsVsFastest)
        assertEquals(120, fewest.addedSecondsVsFastest) // 300 - 180
        assertEquals(1, fast.camerasPassed, "fastest passes the on-line camera")
        assertEquals(0, fewest.camerasPassed, "the detour misses it")
    }

    @Test
    fun `an empty route list is a failure, not an empty chooser`() = runTest {
        val planner = BrouterPlanner(
            route = { _, _, _ -> emptyList() },
            missingTiles = { emptyList() },
            camerasIn = { emptyList() },
        )
        assertIs<PlanOutcome.Failed>(planner.plan(origin, destination))
    }
}
