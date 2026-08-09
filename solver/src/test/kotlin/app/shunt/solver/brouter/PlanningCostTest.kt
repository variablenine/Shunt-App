package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.geo.pointToPolyline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * What planning *costs*, measured in routing passes.
 *
 * A five-hour route took about five minutes to plan on a real phone, which is
 * unusable — and worse than unusable mid-drive, where a re-plan that arrives
 * minutes later arrives after the junction it was needed for. Wall-clock can't
 * be asserted in a unit test and the phone's cost is dominated by one thing
 * anyway: how many times the whole road graph gets searched. So that is what
 * these pin down.
 *
 * The geometry is the same synthetic detour [app.shunt.solver.waypoints]
 * uses — a straight road east past a camera, and a loop north around it.
 */
class PlanningCostTest {

    private val origin = GeoPoint(39.0, -98.20)
    private val destination = GeoPoint(39.0, -97.80)
    private val cameraAt = GeoPoint(39.0, -98.05)
    private val camera = Camera(id = 1, location = cameraAt)

    private fun north(meters: Double) = meters / 111_320.0

    private val fastRoad: List<GeoPoint> = (0..40).map { GeoPoint(39.0, -98.20 + it * 0.01) }

    private val detour: List<GeoPoint> = buildList {
        for (i in 0..10) add(GeoPoint(39.0, -98.20 + i * 0.01))
        for (i in 1..4) add(GeoPoint(39.0 + north(i * 500.0), -98.10))
        for (i in 1..10) add(GeoPoint(39.0 + north(2000.0), -98.10 + i * 0.01))
        for (i in 3 downTo 0) add(GeoPoint(39.0 + north(i * 500.0), -98.00))
        for (i in 1..20) add(GeoPoint(39.0, -98.00 + i * 0.01))
    }

    private val northernLeg: List<GeoPoint> = detour.filter { it.lat > 39.0 + north(1500.0) }

    /** The car's own choice: drop back to the fast road unless already on the loop. */
    private fun carPath(from: GeoPoint, to: GeoPoint): List<GeoPoint> {
        val onDetour = { p: GeoPoint -> pointToPolyline(p, northernLeg).distanceMeters < 300.0 }
        if (onDetour(from) && onDetour(to)) return listOf(from, to)
        return listOf(from, GeoPoint(39.0, from.lon), GeoPoint(39.0, to.lon), to)
    }

    /**
     * Records every routing pass. A refinement probe is the recognisable one: it
     * asks how the *car* would drive a leg, so it carries no cameras at all.
     */
    private class Router(val options: List<BrouterRoute>, val carPath: (GeoPoint, GeoPoint) -> List<GeoPoint>) {
        val probes = mutableListOf<Pair<GeoPoint, GeoPoint>>()
        var planningPasses = 0

        suspend fun route(
            points: List<GeoPoint>,
            cameras: List<CameraVision>,
            @Suppress("UNUSED_PARAMETER") heading: Double?,
        ): List<BrouterRoute> {
            if (cameras.isEmpty() && points.size == 2) {
                probes += points[0] to points[1]
                val line = carPath(points[0], points[1])
                return listOf(BrouterRoute(RouteChoice.FASTEST, line, 1_000, 60, 0, 0))
            }
            planningPasses++
            return options
        }
    }

    private fun options(vararg choices: Pair<RouteChoice, List<GeoPoint>>): List<BrouterRoute> =
        choices.map { (choice, line) ->
            BrouterRoute(
                choice = choice,
                polyline = line,
                distanceMeters = 40_000,
                estimatedSeconds = 1_800,
                distinctCamerasPassed = if (line === fastRoad) 1 else 0,
                exposureMeters = 0,
            )
        }

    @Test
    fun `the fastest option is never probed - it is the road the car already picks`() = runTest {
        // Refining it cost a full pass over the graph to be told what is true by
        // construction: left alone, the car drives the fastest route.
        //
        // The second camera is what makes this bite. Refinement only looks at
        // cameras the route *avoids*, so with just the one on the fast road
        // there would be nothing to check and the pass would be skipped anyway.
        // This one sits off on the loop, so the fastest route does avoid it and
        // the old code had a reason to go probing.
        val offRoadCamera = Camera(id = 2, location = GeoPoint(39.0 + north(2000.0), -98.05))
        val router = Router(options(RouteChoice.FASTEST to fastRoad), ::carPath)
        val planner = BrouterPlanner(
            route = router::route,
            missingTiles = { emptyList() },
            camerasIn = { listOf(camera, offRoadCamera) },
        )

        val outcome = planner.plan(origin, destination)

        assertIs<PlanOutcome.Routes>(outcome)
        assertEquals(
            emptyList(),
            router.probes,
            "the fastest option must cost no refinement passes at all",
        )
        assertTrue(outcome.options.single().waypoints.isEmpty(), "and needs no pins")
    }

    @Test
    fun `a leg is routed once however many options share it`() = runTest {
        // The options run from the same origin to the same destination and share
        // their early pins, so the same leg was being searched once per option —
        // three full passes over the road graph for one answer.
        val router = Router(
            options(
                RouteChoice.FASTEST to fastRoad,
                RouteChoice.BALANCED to detour,
                RouteChoice.FEWEST_CAMERAS to detour,
            ),
            ::carPath,
        )
        val planner = BrouterPlanner(
            route = router::route,
            missingTiles = { emptyList() },
            camerasIn = { listOf(camera) },
        )

        val outcome = planner.plan(origin, destination)

        assertIs<PlanOutcome.Routes>(outcome)
        assertTrue(router.probes.isNotEmpty(), "the detour does need pinning, or this proves nothing")
        assertEquals(
            router.probes.distinct().size,
            router.probes.size,
            "no leg may be routed twice: ${router.probes.size} passes for " +
                "${router.probes.distinct().size} distinct legs",
        )
    }

    @Test
    fun `refinement gives up on its budget rather than planning forever`() = runTest {
        // Pins only steer a car that routes itself; a route with fewer of them is
        // still the route we planned, still labelled with the cameras it passes,
        // and still warned about while driving. A plan that never arrives is not.
        var clock = 0L
        val router = Router(
            options(RouteChoice.FASTEST to fastRoad, RouteChoice.FEWEST_CAMERAS to detour),
            ::carPath,
        )
        val planner = BrouterPlanner(
            route = { points, cameras, heading ->
                clock += 1_000 // every pass over the graph costs a second
                router.route(points, cameras, heading)
            },
            missingTiles = { emptyList() },
            camerasIn = { listOf(camera) },
            refineBudgetMillis = 2_000,
            nowMillis = { clock },
        )

        val outcome = planner.plan(origin, destination)

        assertIs<PlanOutcome.Routes>(outcome)
        assertTrue(
            router.probes.size <= 3,
            "must stop probing once the budget is spent, not run on: ${router.probes.size}",
        )
        assertEquals(2, outcome.options.size, "and must still return the routes it decided on")
    }

    @Test
    fun `a zero budget still yields usable routes`() = runTest {
        val router = Router(
            options(RouteChoice.FASTEST to fastRoad, RouteChoice.FEWEST_CAMERAS to detour),
            ::carPath,
        )
        val planner = BrouterPlanner(
            route = router::route,
            missingTiles = { emptyList() },
            camerasIn = { listOf(camera) },
            refineBudgetMillis = 0,
            nowMillis = { 0L },
        )

        val outcome = planner.plan(origin, destination)

        assertIs<PlanOutcome.Routes>(outcome)
        assertEquals(emptyList(), router.probes, "no budget means no probes")
        assertEquals(2, outcome.options.size)
        assertEquals(
            0,
            outcome.options.first { it.choice == RouteChoice.FEWEST_CAMERAS }.passedCameras.size,
            "the route is still the camera-free one it was planned to be",
        )
    }
}
