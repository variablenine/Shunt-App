package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.geo.BoundingBox
import app.shunt.solver.geo.destinationPoint
import app.shunt.solver.geo.haversineMeters
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
            route = { _ -> routed = true; emptyList() },
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
            route = { (points, _, heading) ->
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
            route = { (_, cams) ->
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
            route = { _ ->
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
            route = { _ ->
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
            route = { _ ->
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
            route = { (_, cams) ->
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
    fun `cameras are drawn from a corridor along the road, not the whole box around the trip`() = runTest {
        // The single biggest lever on planning time: what makes routing slow is
        // checking every expanded link against every zone, so the area cameras
        // are taken from *is* the cost. A 489 km trip at the old 60 km half-width
        // drew from about 59,000 km² — three metro areas' worth, most of it
        // beside roads no route would consider.
        val boxes = mutableListOf<BoundingBox>()
        val line = listOf(origin, destinationPoint(origin, 90.0, 100_000.0))

        val planner = BrouterPlanner(
            route = { _ ->
                listOf(BrouterRoute(RouteChoice.FASTEST, line, 100_000, 4_000, 0, 0))
            },
            missingTiles = { emptyList() },
            camerasIn = { bbox -> boxes += bbox; emptyList() },
            corridorMeters = 5_000.0,
        )

        planner.plan(origin, line.last())

        val drawn = boxes.first()
        val tall = (drawn.maxLat - drawn.minLat) * 111_320.0
        assertTrue(
            tall < 30_000.0,
            "the camera area must hug the road, not the trip's bounding box: ${tall.toInt()} m tall",
        )
    }

    @Test
    fun `a camera data failure refuses to label rather than claiming camera-free`() = runTest {
        // Regression: a thrown camera lookup became an empty list, so every route
        // was confidently labeled "camera-free" — the worst possible failure mode.
        val planner = BrouterPlanner(
            route = { _ ->
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
            route = { _ -> emptyList() },
            missingTiles = { emptyList() },
            camerasIn = { emptyList() },
        )
        assertIs<PlanOutcome.Failed>(planner.plan(origin, destination))
    }

    @Test
    fun `a widen that runs out of time keeps the earlier round's options`() = runTest {
        // The failure this exists to prevent, measured on a real last leg into a
        // dense metro: round one produced a balanced route, that route left the
        // camera corridor, round two ran out of time, and the driver was handed
        // the plain fastest road past 126 cameras. A widen is the whole chooser
        // run a second time out of one budget, and losing it must not cost the
        // options that were already found.
        val far = destinationPoint(origin, 90.0, 90_000.0)
        val detour = listOf(origin, destinationPoint(origin, 0.0, 80_000.0), far)
        var round = 0
        val planner = BrouterPlanner(
            route = { (points, _, _) ->
                round++
                when (round) {
                    // The spine: the cheap no-camera pass the corridor is drawn
                    // around, before the chooser runs at all.
                    1 -> listOf(BrouterRoute(RouteChoice.FASTEST, points, 90_000, 3_600, 0, 0))
                    // Round one of the chooser: fastest, plus an avoidance option
                    // that leaves the corridor and so forces the widen.
                    2 -> listOf(
                        BrouterRoute(RouteChoice.FASTEST, points, 90_000, 3_600, 0, 0),
                        BrouterRoute(RouteChoice.BALANCED, detour, 120_000, 5_400, 0, 0),
                    )
                    // The widen round: everything but the cheap fastest pass ran
                    // out of time, which is exactly what the breakdown showed.
                    else -> listOf(BrouterRoute(RouteChoice.FASTEST, points, 90_000, 3_600, 0, 0))
                }
            },
            missingTiles = { emptyList() },
            camerasIn = { emptyList() },
        )

        val outcome = planner.plan(origin, far)

        assertIs<PlanOutcome.Routes>(outcome)
        assertTrue(
            outcome.options.any { it.choice != RouteChoice.FASTEST },
            "the avoidance option found in the first round must survive: " +
                outcome.options.map { it.choice },
        )
        assertTrue(
            outcome.carriedForward,
            "and the driver must be told the search did not finish",
        )
    }

    @Test
    fun `a search that finishes cleanly is never marked as carried forward`() = runTest {
        // The flag drives a warning on the result sheet, and a warning that
        // fires on ordinary trips is one people learn to ignore.
        val planner = BrouterPlanner(
            route = { (points, _, _) ->
                listOf(BrouterRoute(RouteChoice.FASTEST, points, 2_000, 180, 0, 0))
            },
            missingTiles = { emptyList() },
            camerasIn = { emptyList() },
        )

        val outcome = planner.plan(origin, destination)

        assertIs<PlanOutcome.Routes>(outcome)
        assertTrue(!outcome.carriedForward, "nothing was lost, so nothing was carried")
    }

    @Test
    fun `a continental trip does not route the whole direct road first`() = runTest {
        // The spine runs over the WHOLE trip while everything after it works on
        // one leg, so on a cross-country route it is the expensive search. Given
        // the destination it took 64 s of a 71 s budget on a real 3,598 km trip,
        // every later pass got the 1 ms floor, and the driver was told no route
        // exists. It only has to reach far enough to choose a cut.
        val faraway = destinationPoint(origin, 90.0, 3_000_000.0)
        val asked = mutableListOf<List<GeoPoint>>()
        val planner = BrouterPlanner(
            route = { (pts, _, _) ->
                asked += pts
                listOf(BrouterRoute(RouteChoice.FASTEST, pts, 3_000_000, 108_000, 0, 0))
            },
            missingTiles = { emptyList() },
            camerasIn = { emptyList() },
        )

        planner.plan(listOf(origin, faraway), maxLegMeters = LegSplitter.MAX_LEG_METERS)

        val spineEnd = asked.first().last()
        assertTrue(
            haversineMeters(origin, spineEnd) < 1_000_000.0,
            "the spine must stop short of a destination 3,000 km away, not route to it",
        )
        assertTrue(
            haversineMeters(origin, spineEnd) > LegSplitter.MAX_LEG_METERS,
            "...but still reach past the leg window, or there is nothing to cut on",
        )
    }

    @Test
    fun `an ordinary long trip still routes the real direct road`() = runTest {
        // The probe aims along the great circle, and the road out of a town
        // rarely leaves along it — so a probe spine chooses its cut from a road
        // the trip would not have taken. Measured on a 1,165 km benchmark that
        // cost 88 km of extra driving for identical exposure. The trade is only
        // worth making where the alternative is failing outright.
        val far = destinationPoint(origin, 90.0, 900_000.0)
        val asked = mutableListOf<List<GeoPoint>>()
        val planner = BrouterPlanner(
            route = { (pts, _, _) ->
                asked += pts
                listOf(BrouterRoute(RouteChoice.FASTEST, pts, 900_000, 32_400, 0, 0))
            },
            missingTiles = { emptyList() },
            camerasIn = { emptyList() },
        )

        planner.plan(listOf(origin, far), maxLegMeters = LegSplitter.MAX_LEG_METERS)

        assertEquals(far, asked.first().last(), "a 900 km trip must still route its real direct road")
    }

    @Test
    fun `a trip whose spine fails is still cut into legs`() = runTest {
        // The failure that reached a driver as "No route found". When the spine
        // pass came back empty the fallback was the two bare trip points — and
        // LegSplitter has nothing between MIN_LEG and MAX_LEG to choose from on
        // two points, so it cut nothing, and a three-thousand-kilometre trip was
        // then planned in one go out of whatever budget was left. A trip whose
        // spine failed is exactly the trip that most needs splitting.
        val faraway = destinationPoint(origin, 90.0, 3_000_000.0)
        var first = true
        val planner = BrouterPlanner(
            route = { (pts, _, _) ->
                if (first) {
                    first = false
                    emptyList() // the spine pass finds nothing
                } else {
                    listOf(BrouterRoute(RouteChoice.FASTEST, pts, 250_000, 9_000, 0, 0))
                }
            },
            missingTiles = { emptyList() },
            camerasIn = { emptyList() },
        )

        val outcome = planner.plan(listOf(origin, faraway), maxLegMeters = LegSplitter.MAX_LEG_METERS)

        assertIs<PlanOutcome.Routes>(outcome)
        assertTrue(
            outcome.isPartial,
            "the trip must still be cut into legs when the spine could not be routed",
        )
    }
}
