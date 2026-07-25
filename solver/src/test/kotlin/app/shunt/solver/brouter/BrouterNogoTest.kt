package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import btools.router.OsmNogoPolygon
import btools.router.RoutingParamCollector
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The nogo hand-off to BRouter. Real routing needs a 10 MB rd5 tile and isn't a
 * CI test, but *what we hand the engine* fully determines whether avoidance is
 * a hard block or a soft penalty — so it is pinned here.
 */
class BrouterNogoTest {

    private val router = BrouterRouter(segmentDir = File("/nonexistent"), profileDir = File("/nonexistent"))
    private val collector = RoutingParamCollector()

    private val directional = CameraVision(GeoPoint(39.0, -98.0), directionDegrees = 90.0)
    private val omni = CameraVision(GeoPoint(39.01, -98.01), directionDegrees = null)

    @Test
    fun `blocking avoidance marks every zone impassable, not weight zero`() {
        // Regression guard: NaN is BRouter's "impassable". Formatting the weight
        // as an Int turns NaN into 0 — a nogo that costs nothing and is silently
        // ignored, so "fewest cameras" would quietly stop avoiding anything.
        val nogos = router.buildNogos(listOf(directional, omni), Double.NaN, collector)
        assertEquals(2, nogos.size, "one nogo per camera")
        assertTrue(
            nogos.all { it.nogoWeight.isNaN() },
            "every zone must be impassable; weights were ${nogos.map { it.nogoWeight }}",
        )
    }

    @Test
    fun `weighted avoidance passes the finite per-metre penalty through`() {
        val nogos = router.buildNogos(listOf(directional, omni), 500.0, collector)
        assertEquals(2, nogos.size)
        assertTrue(nogos.all { it.nogoWeight == 500.0 }, "weights were ${nogos.map { it.nogoWeight }}")
    }

    @Test
    fun `a camera with a known facing becomes a closed sector, not a full circle`() {
        // A route passing behind a directional camera must stay legal, so the
        // blocked area has to be the sector it faces — not a disc around it.
        val nogos = router.buildNogos(listOf(directional), Double.NaN, collector)
        val polygon = nogos.single()
        assertTrue(polygon is OsmNogoPolygon, "directional cameras must be polygons")
        assertTrue((polygon as OsmNogoPolygon).isClosed, "the sector must be a closed area")

        // Points 100 m ahead (inside the 120 m range) and 100 m behind.
        val ahead = app.shunt.solver.geo.destinationPoint(directional.location, 90.0, 100.0)
        val behind = app.shunt.solver.geo.destinationPoint(directional.location, 270.0, 100.0)
        fun ilon(p: GeoPoint) = ((p.lon + 180.0) * 1_000_000.0 + 0.5).toLong()
        fun ilat(p: GeoPoint) = ((p.lat + 90.0) * 1_000_000.0 + 0.5).toLong()
        assertTrue(polygon.isWithin(ilon(ahead), ilat(ahead)), "in front of the camera must be blocked")
        assertTrue(!polygon.isWithin(ilon(behind), ilat(behind)), "behind the camera must stay open")
    }

    @Test
    fun `an unknown facing becomes an all-round zone of the full range`() {
        val nogos = router.buildNogos(listOf(omni), Double.NaN, collector)
        val circle = nogos.single()
        assertTrue(circle !is OsmNogoPolygon, "unknown facing must be a plain circle")

        // BRouter does not carry a circle's radius in a field: it round-trips it
        // through the node *name* ("nogo150"), and prepareNogoPoints parses it
        // back — silently defaulting to 20 m if that ever fails to parse. So an
        // all-round camera would quietly shrink to a 20 m zone. Production calls
        // prepareNogoPoints; this pins that the full range survives the trip.
        btools.router.RoutingContext.prepareNogoPoints(nogos)
        assertEquals(
            CameraVision.OMNI_RANGE_M.toInt(), circle.radius.toInt(),
            "the all-round zone must keep its full range, not fall back to 20 m",
        )
    }
}
