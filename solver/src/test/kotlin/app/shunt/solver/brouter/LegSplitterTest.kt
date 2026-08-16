package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.haversineMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a long trip gets cut into legs.
 *
 * The load-bearing property is not that a cut exists — it is *where*. A leg
 * boundary is a hard waypoint both legs must touch, so a boundary inside a dense
 * metro distorts both of them to reach it and drags the driver through turns to
 * hit a point they never asked for. Everything below is about that.
 */
class LegSplitterTest {

    private val metresNorth = 1.0 / 111_320.0

    /** A due-north spine [kilometres] long, one point every 5 km, as the planner samples. */
    private fun spine(kilometres: Int): List<GeoPoint> =
        (0..(kilometres / 5)).map { GeoPoint(39.0 + it * 5_000 * metresNorth, -98.0) }

    private fun camerasAt(vararg points: GeoPoint) =
        CameraIndex(points.map { CameraVision(it, directionDegrees = null) })

    private fun noCameras() = CameraIndex(emptyList())

    /** A dense blob of cameras around [centre], as a metro reads to this. */
    private fun metroCamerasAround(centre: GeoPoint, count: Int = 40): List<CameraVision> =
        (0 until count).map {
            CameraVision(GeoPoint(centre.lat + (it - count / 2) * 60 * metresNorth, centre.lon), null)
        }

    private fun metroAround(centre: GeoPoint, count: Int = 40): CameraIndex =
        CameraIndex(metroCamerasAround(centre, count))

    @Test
    fun `a trip short enough to plan whole is not cut`() {
        assertNull(
            LegSplitter.cut(spine(200), noCameras()),
            "a 200 km trip is inside the leg limit and splitting it spends a constraint for nothing",
        )
    }

    @Test
    fun `a long trip is cut inside the allowed window`() {
        val cut = assertNotNull(LegSplitter.cut(spine(600), noCameras()))
        assertTrue(
            cut.alongMeters >= LegSplitter.MIN_LEG_METERS,
            "cut at ${cut.alongMeters} m leaves too little driving to plan the next leg in",
        )
        assertTrue(
            cut.alongMeters <= LegSplitter.MAX_LEG_METERS,
            "cut at ${cut.alongMeters} m makes a first leg too long to plan quickly",
        )
    }

    @Test
    fun `with nothing to avoid anywhere, the cut goes as late as it may`() {
        // Every boundary is a constraint the route did not ask for, so where
        // they are all equally free, fewer of them is better.
        val cut = assertNotNull(LegSplitter.cut(spine(600), noCameras()))
        assertTrue(
            cut.alongMeters > LegSplitter.MAX_LEG_METERS - 6_000,
            "an unconstrained cut should sit at the far end of the window, got ${cut.alongMeters} m",
        )
    }

    @Test
    fun `the cut finds the gap between two metros`() {
        // The reported worry, in miniature: a long trip whose fastest line runs
        // through cities, and a boundary landing inside one would hold both legs
        // to a point in the one place the route most needs to be free.
        //
        // Cities at both ends of the allowed window and open country between
        // them, so the cut has to be *found* — no default position gets it
        // right. Taking the latest allowed cut lands in the second city; taking
        // the earliest lands in the first.
        val line = spine(600)
        val early = GeoPoint(39.0 + 125_000 * metresNorth, -98.0)
        val late = GeoPoint(39.0 + 248_000 * metresNorth, -98.0)
        val cameras = CameraIndex(
            metroCamerasAround(early) + metroCamerasAround(late),
        )

        val cut = assertNotNull(LegSplitter.cut(line, cameras))

        assertEquals(0, cut.camerasNearby, "the cut landed somewhere with cameras around it: $cut")
        assertTrue(
            haversineMeters(cut.point, early) > LegSplitter.QUIET_RADIUS_METERS &&
                haversineMeters(cut.point, late) > LegSplitter.QUIET_RADIUS_METERS,
            "the cut landed inside one of the metros it exists to avoid: ${cut.alongMeters} m along",
        )
    }

    @Test
    fun `a metro at the far end of the window pushes the cut earlier`() {
        // The quiet stretch is now before the city rather than after it, so the
        // preference for a late cut has to give way. If it doesn't, the boundary
        // sits in the metro and the whole exercise is pointless.
        val line = spine(600)
        val metroCentre = GeoPoint(39.0 + 245_000 * metresNorth, -98.0)

        val cut = assertNotNull(LegSplitter.cut(line, metroAround(metroCentre)))

        assertEquals(0, cut.camerasNearby, "the cut landed in the metro: $cut")
        assertTrue(
            cut.alongMeters < 235_000,
            "the cut should have moved back before the metro, got ${cut.alongMeters} m",
        )
    }

    @Test
    fun `where the whole window is built up it takes the quietest part of it`() {
        // A trip whose entire acceptable window is metro — the corridor between
        // two cities that never really ends. There is no free cut here, so the
        // rule degrades to "least bad" rather than refusing to split, because
        // refusing means handing the driver the two-minute plan this exists to
        // prevent.
        val line = spine(600)
        val dense = CameraIndex(
            (120..250 step 2).flatMap { km ->
                val here = GeoPoint(39.0 + km * 1_000 * metresNorth, -98.0)
                // A thinner patch around 200 km: still watched, least so.
                val count = if (km in 196..204) 1 else 6
                (0 until count).map { CameraVision(GeoPoint(here.lat + it * 90 * metresNorth, here.lon), null) }
            },
        )

        val cut = assertNotNull(LegSplitter.cut(line, dense))
        assertTrue(cut.camerasNearby > 0, "this fixture must have no free cut, or it proves nothing")
        assertTrue(
            haversineMeters(cut.point, GeoPoint(39.0 + 200_000 * metresNorth, -98.0)) < 15_000,
            "expected the thinnest part of the corridor, got ${cut.alongMeters} m with ${cut.camerasNearby} cameras",
        )
    }

    @Test
    fun `a cut is never made so late that the tail is pointless`() {
        // 265 km: past the leg limit, but only just. A boundary 5 km from the
        // destination would cost a constraint and save no planning at all.
        val cut = LegSplitter.cut(spine(265), noCameras())
        if (cut != null) {
            assertTrue(
                265_000 - cut.alongMeters >= 20_000,
                "cut at ${cut.alongMeters} m leaves a pointless stub of a second leg",
            )
        }
    }

    @Test
    fun `splitting keeps the driver's own stops, in order, on the right side`() {
        val origin = GeoPoint(39.0, -98.0)
        val nearStop = GeoPoint(39.0 + 60_000 * metresNorth, -98.0)
        val farStop = GeoPoint(39.0 + 400_000 * metresNorth, -98.0)
        val destination = GeoPoint(39.0 + 600_000 * metresNorth, -98.0)
        val cut = GeoPoint(39.0 + 200_000 * metresNorth, -98.0)

        val (first, rest) = LegSplitter.split(listOf(origin, nearStop, farStop, destination), cut)

        assertEquals(listOf(origin, cut), first, "the first leg runs from the origin to the boundary")
        assertEquals(
            listOf(cut, farStop, destination),
            rest,
            "the rest of the trip starts at the boundary and keeps every stop still ahead",
        )
    }

    @Test
    fun `a trip with no stops splits into two plain runs`() {
        val origin = GeoPoint(39.0, -98.0)
        val destination = GeoPoint(39.0 + 600_000 * metresNorth, -98.0)
        val cut = GeoPoint(39.0 + 200_000 * metresNorth, -98.0)

        val (first, rest) = LegSplitter.split(listOf(origin, destination), cut)

        assertEquals(listOf(origin, cut), first)
        assertEquals(listOf(cut, destination), rest)
    }

    @Test
    fun `the cut is a point on the road, not a point near it`() {
        // It becomes a waypoint the car is steered to, so it has to be somewhere
        // the car can actually be.
        val line = spine(600)
        val cut = assertNotNull(LegSplitter.cut(line, camerasAt(GeoPoint(39.5, -98.0))))
        assertTrue(cut.point in line, "the cut must be a point of the spine it came from")
        assertEquals(line[cut.index], cut.point, "the reported index must locate the cut in the spine")
    }

    @Test
    fun `a stop the driver added is never cut in front of`() {
        // Reported from a real plan: a Supercharger added to a long trip showed
        // on the map but the route did not go there. It had fallen past the leg
        // boundary, so the chooser's route ignored it entirely.
        //
        // A stop is the best boundary available — a point the route must pass
        // through, chosen by the person driving — so nothing invented may come
        // before it.
        val line = spine(600)
        val charger = GeoPoint(39.0 + 190_000 * metresNorth, -98.0)

        assertNull(
            LegSplitter.cut(line, noCameras(), stops = listOf(charger)),
            "a leg boundary was placed before somewhere the driver asked to be",
        )
    }

    @Test
    fun `a stop far beyond the leg window still allows a cut`() {
        // The stop is most of a day's driving away; refusing to split would hand
        // back the two-minute plan splitting exists to prevent, and the driver
        // reaches the stop on a later leg exactly as intended.
        val line = spine(600)
        val farStop = GeoPoint(39.0 + 500_000 * metresNorth, -98.0)

        val cut = assertNotNull(LegSplitter.cut(line, noCameras(), stops = listOf(farStop)))
        assertTrue(cut.alongMeters <= LegSplitter.MAX_LEG_METERS)
    }
}
