package app.shunt.solver.camera

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.BoundingBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The practice camera field.
 *
 * Two properties carry all the weight. **Determinism**, because a test fixture
 * that moves between runs cannot tell a fix from a coincidence and cannot be
 * reproduced by anyone else. And **labelling**, because a driver who cannot tell
 * an invented camera from a real one has been misled about where they are
 * watched, which is the one mistake this app must never make.
 */
class PracticeCamerasTest {

    private fun box(south: Double, west: Double, north: Double, east: Double) =
        BoundingBox(minLat = south, minLon = west, maxLat = north, maxLon = east)

    @Test
    fun `the same area always gives the same cameras`() {
        val area = box(38.9, -98.2, 39.2, -97.8)
        val first = PracticeCameras.inBox(area)
        val second = PracticeCameras.inBox(area)

        assertTrue(first.isNotEmpty(), "a third of a degree of country must contain some")
        assertEquals(
            first.map { it.id to it.location },
            second.map { it.id to it.location },
            "practice cameras moved between two identical queries",
        )
    }

    @Test
    fun `overlapping queries agree about the cameras they share`() {
        // Two devices ask about different areas that overlap, or one device pans
        // the map. Where the boxes overlap they must describe the same world —
        // otherwise a route planned against one query is checked against another.
        val west = PracticeCameras.inBox(box(38.9, -98.4, 39.3, -98.0))
        val east = PracticeCameras.inBox(box(38.9, -98.2, 39.3, -97.8))
        val overlapWest = west.filter { it.location.lon >= -98.2 }
        val overlapEast = east.filter { it.location.lon <= -98.0 }

        assertTrue(overlapWest.isNotEmpty(), "the overlap must contain cameras, or this proves nothing")
        assertEquals(
            overlapWest.map { it.id }.sorted(),
            overlapEast.map { it.id }.sorted(),
            "the same ground produced different cameras depending on which way it was asked",
        )
    }

    @Test
    fun `every practice camera says it is not real`() {
        val cameras = PracticeCameras.inBox(box(38.9, -98.2, 39.2, -97.8))
        assertTrue(cameras.isNotEmpty())
        assertTrue(
            cameras.all { PracticeCameras.isPractice(it) },
            "a camera with no practice tag is indistinguishable from a real one downstream",
        )
        assertTrue(
            cameras.all { it.tags["manufacturer"]?.contains("not real") == true },
            "the detail sheet shows the manufacturer, and it has to give the game away",
        )
    }

    @Test
    fun `ids cannot collide with real OSM ids`() {
        // Real cameras carry positive OSM ids, and both sets end up in the same
        // maps keyed by id — including the one the drive monitor uses to
        // remember which cameras it has already announced.
        val cameras = PracticeCameras.inBox(box(38.5, -98.5, 39.5, -97.5))
        assertTrue(cameras.isNotEmpty())
        assertTrue(cameras.all { it.id < 0 }, "a practice camera took a positive id")
        assertEquals(cameras.size, cameras.map { it.id }.distinct().size, "duplicate ids in one area")
    }

    @Test
    fun `every camera lies inside the box it was asked for`() {
        val area = box(38.9, -98.2, 39.2, -97.8)
        assertTrue(
            PracticeCameras.inBox(area).all { area.contains(it.location) },
            "a query returned a camera outside the area asked about",
        )
    }

    // ---- On roads, and denser where the roads are -------------------------

    /**
     * A stand-in road network: one east-west road across the middle of the test
     * area, plus a denser patch of streets standing in for a town.
     *
     * Snapping is given as a lambda precisely so this can exist — the real one
     * needs map tiles, and the property being tested (candidates far from a road
     * are dropped, and the survivors sit on one) is about the rule, not about
     * BRouter.
     */
    private fun fakeRoads(): (List<GeoPoint>, Double) -> List<GeoPoint?> = { points, meters ->
        val metresLat = 1.0 / 111_320.0
        points.map { p ->
            val townLat = 39.05
            val inTown = kotlin.math.abs(p.lat - townLat) < 0.02 && kotlin.math.abs(p.lon + 98.0) < 0.02
            val highwayLat = 39.15
            when {
                // A grid of streets: everything in the town is near something.
                inTown -> GeoPoint(kotlin.math.round(p.lat / 0.004) * 0.004, p.lon)
                // One road: only candidates within the radius of it survive.
                kotlin.math.abs(p.lat - highwayLat) < meters * metresLat -> GeoPoint(highwayLat, p.lon)
                else -> null
            }
        }
    }

    @Test
    fun `every camera ends up on a road, and the rest are dropped`() {
        val area = box(38.9, -98.2, 39.3, -97.9)
        val unsnapped = PracticeCameras.inBox(area)
        val onRoads = PracticeCameras.inBox(area, fakeRoads())

        assertTrue(onRoads.isNotEmpty(), "snapping threw the whole field away")
        assertTrue(
            onRoads.size < unsnapped.size,
            "nothing was dropped, so candidates in open fields are still being used",
        )
        assertTrue(
            onRoads.all { it.location.lat == 39.15 || kotlin.math.abs(it.location.lat - 39.05) < 0.03 },
            "a camera came back somewhere the road network does not go",
        )
    }

    @Test
    fun `the field is denser where the roads are`() {
        // The whole reason snapping doubles as a density rule: a uniform grid of
        // candidates comes out concentrated wherever there is road to snap to,
        // which is where people are.
        val town = box(39.03, -98.02, 39.07, -97.98)
        val country = box(39.30, -98.02, 39.34, -97.98)
        val inTown = PracticeCameras.inBox(town, fakeRoads()).size
        val inCountry = PracticeCameras.inBox(country, fakeRoads()).size

        assertTrue(inTown > 0, "the town has no practice cameras at all")
        assertEquals(0, inCountry, "open country with no roads produced cameras anyway")
    }

    @Test
    fun `a snapper that fails leaves the field usable`() {
        // A phone with no tiles for the area cannot snap, and a practice mode
        // that silently produces nothing would look like the switch is broken.
        val area = box(38.9, -98.2, 39.2, -97.8)
        val exploding: (List<GeoPoint>, Double) -> List<GeoPoint?> = { _, _ -> error("no tiles") }
        assertEquals(
            PracticeCameras.inBox(area).size,
            PracticeCameras.inBox(area, exploding).size,
            "a failed snap must fall back to the unsnapped field, not to nothing",
        )
    }

    @Test
    fun `the field is sparse enough to route through`() {
        // A camera in every cell is a landscape where avoidance is hopeless, and
        // the interesting behaviour — the detour, the guard pins, the approach
        // warning — needs gaps to route through.
        val degrees = 1.0
        val cells = (degrees / PracticeCameras.CELL_DEGREES) * (degrees / PracticeCameras.CELL_DEGREES)
        val found = PracticeCameras.inBox(box(39.0, -98.0, 39.0 + degrees, -98.0 + degrees)).size

        assertTrue(found > cells * 0.2, "too sparse to exercise avoidance: $found in $cells cells")
        assertTrue(found < cells * 0.7, "too dense to route through: $found in $cells cells")
    }

    @Test
    fun `they carry a facing, like the real ones`() {
        // Camera vision is a 180 degree cone when the direction is known and a
        // full circle when it isn't, and those are meaningfully different to
        // route against. Practice cameras must exercise the common case.
        val cameras = PracticeCameras.inBox(box(38.9, -98.2, 39.2, -97.8))
        assertTrue(cameras.all { it.directionDegrees != null }, "a practice camera with no facing")
    }

    @Test
    fun `an absurd box is bounded rather than hanging`() {
        // The caller is a trip's bounding box already expanded by a corridor
        // margin, and nobody checks it twice.
        val whole = PracticeCameras.inBox(box(-85.0, -179.0, 85.0, 179.0))
        assertTrue(whole.size <= 20_000, "generated ${whole.size} cameras for a whole-world box")
    }

    @Test
    fun `a real camera is not mistaken for a practice one`() {
        val real = Camera(12345, GeoPoint(39.0, -98.0), mapOf("manufacturer" to "Flock Safety"))
        assertTrue(!PracticeCameras.isPractice(real))
    }
}
