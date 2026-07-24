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
    fun `a camera along a detour outside the base box is fetched and counted`() = runTest {
        // A camera ~11 km north — outside bbox(origin, destination) + margin.
        val farCamera = Camera(id = 7, location = GeoPoint(39.1, -98.0))
        val fastLine = listOf(origin, destination)
        val detour = listOf(origin, farCamera.location, destination) // "fewest" wanders north

        val planner = BrouterPlanner(
            route = { _, _, _ ->
                listOf(
                    BrouterRoute(RouteChoice.FASTEST, fastLine, 2_000, 180, 0, 0),
                    BrouterRoute(RouteChoice.FEWEST_CAMERAS, detour, 24_000, 1_400, 0, 0),
                )
            },
            missingTiles = { emptyList() },
            // The camera is only "found" once the search box widens to the detour.
            camerasIn = { bbox -> if (bbox.contains(farCamera.location)) listOf(farCamera) else emptyList() },
        )
        val outcome = planner.plan(origin, destination)
        assertIs<PlanOutcome.Routes>(outcome)
        val (fast, fewest) = outcome.options
        assertEquals(0, fast.camerasPassed, "the straight route is nowhere near the far camera")
        assertEquals(1, fewest.camerasPassed, "the detour drives through it — must be caught")
    }

    @Test
    fun `a camera revealed only by the final re-route is still counted`() = runTest {
        // Regression: the refinement loop could exit on its pass cap immediately
        // after re-routing, leaving the camera set narrower than the routes it
        // labels. Counting against it printed "camera-free" over a route that
        // drives straight through a camera's field of view.
        //
        // Each re-plan sends "fewest cameras" a little further north, so the loop
        // always exits on its cap holding routes newer than the camera set. The
        // only camera sits on that final leg — invisible to every earlier fetch.
        val onFinalLeg = Camera(id = 7, location = GeoPoint(39.30, -98.0))
        val fastLine = listOf(origin, destination)
        var plans = 0

        val planner = BrouterPlanner(
            route = { _, _, _ ->
                plans++
                val wander = when (plans) {
                    1 -> GeoPoint(39.10, -98.0)
                    2 -> GeoPoint(39.20, -98.0)
                    else -> onFinalLeg.location // the final re-route reaches the camera
                }
                listOf(
                    BrouterRoute(RouteChoice.FASTEST, fastLine, 2_000, 180, 0, 0),
                    BrouterRoute(
                        RouteChoice.FEWEST_CAMERAS,
                        listOf(origin, wander, destination), 90_000, 5_000, 0, 0,
                    ),
                )
            },
            missingTiles = { emptyList() },
            camerasIn = { bbox -> listOf(onFinalLeg).filter { bbox.contains(it.location) } },
        )

        val outcome = planner.plan(origin, destination)
        assertIs<PlanOutcome.Routes>(outcome)
        val fewest = outcome.options.first { it.choice == RouteChoice.FEWEST_CAMERAS }
        assertEquals(
            1, fewest.camerasPassed,
            "the camera on the FINAL route must be counted — never labeled camera-free",
        )
    }

    @Test
    fun `a camera data failure refuses to label rather than claiming camera-free`() = runTest {
        // Regression: a thrown camera lookup became an empty list, so every route
        // was confidently labeled "camera-free" — the worst possible failure mode.
        val planner = BrouterPlanner(
            route = { _, _, _ ->
                listOf(BrouterRoute(RouteChoice.FASTEST, listOf(origin, destination), 2_000, 180, 0, 0))
            },
            missingTiles = { emptyList() },
            camerasIn = { throw java.io.IOException("camera CDN unreachable") },
        )
        val outcome = planner.plan(origin, destination)
        val failed = assertIs<PlanOutcome.Failed>(outcome, "must not present routes it cannot vet")
        assertTrue("camera data" in failed.reason.lowercase(), "reason was: ${failed.reason}")
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
