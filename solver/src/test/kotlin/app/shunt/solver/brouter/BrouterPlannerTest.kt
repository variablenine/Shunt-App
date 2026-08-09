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
    fun `the direction of travel reaches the routing engine`() = runTest {
        // Without it a mid-drive re-plan can answer "turn round", which on a
        // road you have already committed to is not a route at all.
        val headings = mutableListOf<Double?>()
        val planner = BrouterPlanner(
            route = { points, _, heading ->
                headings += heading
                listOf(
                    BrouterRoute(
                        RouteChoice.FASTEST, points, 2_000, 180,
                        distinctCamerasPassed = 0, exposureMeters = 0,
                    ),
                )
            },
            missingTiles = { emptyList() },
            camerasIn = { emptyList() },
        )

        planner.plan(origin, destination, headingDegrees = 270.0)

        assertTrue(headings.isNotEmpty(), "the route should have been asked for")
        assertTrue(headings.all { it == 270.0 }, "every pass must set off the same way: $headings")
    }

    @Test
    fun `a compass bearing is normalised however it arrives`() = runTest {
        // Android reports 0..360; some sources use -180..180. Both are the
        // same direction, and BRouter takes whole degrees from 0.
        assertEquals(270, BrouterRouter.normalizedBearing(-90.0))
        assertEquals(10, BrouterRouter.normalizedBearing(370.0))
        assertEquals(0, BrouterRouter.normalizedBearing(360.0))
        assertEquals(89, BrouterRouter.normalizedBearing(89.9))
    }

    @Test
    fun `a route is never labelled against cameras it was not planned to avoid`() = runTest {
        // The bug this exists to prevent: a camera just outside the box drawn
        // around the trip is absent when the routing runs, so the avoidance is
        // never asked to dodge it — and then it turns up in the count, reported
        // as if the router had considered it and given up.
        val seenBoxes = mutableListOf<BoundingBox>()
        val far = destinationPoint(origin, 90.0, 40_000.0)
        val cameraOutThere = Camera(id = 7, location = destinationPoint(origin, 90.0, 30_000.0))
        val wanderingRoute = listOf(origin, far, destination)

        var routedWith = 0
        val planner = BrouterPlanner(
            route = { _, cams, _ ->
                routedWith = cams.size
                listOf(
                    BrouterRoute(
                        RouteChoice.FASTEST, wanderingRoute, 40_000, 1_800,
                        distinctCamerasPassed = 0, exposureMeters = 0,
                    ),
                )
            },
            missingTiles = { emptyList() },
            camerasIn = { bbox ->
                seenBoxes += bbox
                if (bbox.contains(cameraOutThere.location)) listOf(cameraOutThere) else emptyList()
            },
            // Deliberately tiny, so the first look misses the far camera and the
            // planner has to notice and widen before it labels anything.
            bboxMarginMeters = 100.0,
        )

        val outcome = planner.plan(origin, destination)

        assertIs<PlanOutcome.Routes>(outcome)
        assertTrue(seenBoxes.isNotEmpty(), "cameras must have been looked for at all")
        assertTrue(
            routedWith > 0,
            "the final routing pass must have been given the camera that gets counted",
        )
        assertEquals(
            listOf(cameraOutThere),
            outcome.options.single().passedCameras,
            "and the count must come from that same set",
        )
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
    fun `a route that leaves the corridor is re-planned against the cameras out there`() = runTest {
        // The camera area is drawn around the direct road, which is the right
        // shape for it — but an avoidance detour can leave that corridor
        // entirely. When it does, the set the router was given no longer covers
        // the route it produced, and labelling against it would print
        // "camera-free" over a road that drives past a camera.
        //
        // So the loop widens to what the routes actually did and plans again.
        // This is the case that has to keep working now the corridor is tight.
        val farNorth = GeoPoint(40.8, -98.0) // ~200 km north, well outside the corridor
        val farCamera = Camera(id = 7, location = farNorth)
        val fastLine = listOf(origin, destination)
        val detour = listOf(origin, farNorth, destination)
        val boxes = mutableListOf<BoundingBox>()

        val planner = BrouterPlanner(
            route = { _, _, _ ->
                listOf(
                    BrouterRoute(RouteChoice.FASTEST, fastLine, 2_000, 180, 0, 0),
                    BrouterRoute(RouteChoice.FEWEST_CAMERAS, detour, 400_000, 20_000, 0, 0),
                )
            },
            missingTiles = { emptyList() },
            camerasIn = { bbox ->
                boxes += bbox
                listOf(farCamera).filter { bbox.contains(it.location) }
            },
        )

        val outcome = assertIs<PlanOutcome.Routes>(planner.plan(origin, destination))

        assertTrue(
            boxes.size > 1,
            "a route this far off the direct road must force a second look for cameras",
        )
        assertEquals(
            1,
            outcome.options.first { it.choice == RouteChoice.FEWEST_CAMERAS }.camerasPassed,
            "the camera on the detour must be counted — never labelled camera-free",
        )
    }

    @Test
    fun `a camera beside a sparse stretch of the route is still found`() = runTest {
        // The camera area is drawn as a corridor around the route, measured from
        // points sampled along it. A route given as a few far-apart vertices —
        // a re-planned leg, a straight hop — must be walked, not just have its
        // corners kept, or the gaps between them drop every camera inside.
        //
        // Here the only camera sits midway along a single 40 km segment, nowhere
        // near either end of it.
        val farEnd = destinationPoint(origin, 90.0, 40_000.0)
        val midway = destinationPoint(origin, 90.0, 20_000.0)
        val sparseRoute = listOf(origin, farEnd, destination) // three points, 40 km apart
        val cameraMidSegment = Camera(id = 7, location = midway)

        var routedWith = 0
        val planner = BrouterPlanner(
            route = { _, cams, _ ->
                routedWith = maxOf(routedWith, cams.size)
                listOf(BrouterRoute(RouteChoice.FASTEST, sparseRoute, 80_000, 3_600, 0, 0))
            },
            missingTiles = { emptyList() },
            camerasIn = { bbox ->
                listOf(cameraMidSegment).filter { bbox.contains(it.location) }
            },
            // Tight, so the corridor filter is doing real work. At the default
            // 60 km it would sweep the camera in whatever the sampling did, and
            // this would prove nothing.
            bboxMarginMeters = 100.0,
        )

        val outcome = assertIs<PlanOutcome.Routes>(planner.plan(origin, destination))

        assertTrue(
            routedWith > 0,
            "the camera beside the middle of a long segment must reach the router",
        )
        assertEquals(
            1,
            outcome.options.single().camerasPassed,
            "and must be counted — a gap in the sampling would silently drop it",
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
