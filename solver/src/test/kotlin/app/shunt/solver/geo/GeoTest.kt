package app.shunt.solver.geo

import app.shunt.core.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    @Test
    fun `haversine matches known distance`() {
        // Two points ~1.47° of latitude apart, ~164 km.
        val d = haversineMeters(GeoPoint(39.5133, -98.0133), GeoPoint(38.0389, -97.9065))
        assertTrue(abs(d - 164_000) < 5_000, "got $d")
    }

    @Test
    fun `point on segment has zero distance`() {
        val a = GeoPoint(39.0, -98.0)
        val b = GeoPoint(39.0, -97.9)
        val mid = GeoPoint(39.0, -97.95)
        assertTrue(pointToSegmentMeters(mid, a, b) < 1.0)
    }

    @Test
    fun `point beside segment measures perpendicular offset`() {
        val a = GeoPoint(39.0, -98.0)
        val b = GeoPoint(39.0, -97.9)
        // ~44.5 m north of the line's midpoint (0.0004° lat)
        val p = GeoPoint(39.0004, -97.95)
        val d = pointToSegmentMeters(p, a, b)
        assertTrue(abs(d - 44.5) < 1.0, "got $d")
    }

    @Test
    fun `point past segment end clamps to endpoint`() {
        val a = GeoPoint(39.0, -98.0)
        val b = GeoPoint(39.0, -97.99)
        val p = GeoPoint(39.0, -97.9)
        val expected = haversineMeters(p, b)
        assertTrue(abs(pointToSegmentMeters(p, a, b) - expected) < expected * 0.01)
    }

    @Test
    fun `bearing cardinal directions`() {
        val o = GeoPoint(39.0, -98.0)
        assertTrue(abs(bearingDegrees(o, GeoPoint(40.0, -98.0)) - 0.0) < 1.0)
        assertTrue(abs(bearingDegrees(o, GeoPoint(39.0, -97.0)) - 90.0) < 1.0)
        assertTrue(abs(bearingDegrees(o, GeoPoint(38.0, -98.0)) - 180.0) < 1.0)
    }

    @Test
    fun `bearing difference wraps`() {
        assertEquals(20.0, bearingDifference(350.0, 10.0), 1e-9)
        assertEquals(180.0, bearingDifference(0.0, 180.0), 1e-9)
        assertEquals(0.0, bearingDifference(90.0, 90.0), 1e-9)
    }

    @Test
    fun `destinationPoint lands the right distance and bearing away`() {
        val origin = GeoPoint(39.0, -98.0)
        // 45 m due east: bearing back to origin should read ~270, distance ~45 m.
        val east = destinationPoint(origin, 90.0, 45.0)
        assertTrue(abs(haversineMeters(origin, east) - 45.0) < 0.5, "distance ${haversineMeters(origin, east)}")
        assertTrue(abs(bearingDegrees(origin, east) - 90.0) < 1.0, "bearing ${bearingDegrees(origin, east)}")
        // Due north keeps longitude fixed and raises latitude.
        val north = destinationPoint(origin, 0.0, 100.0)
        assertTrue(north.lat > origin.lat)
        assertTrue(abs(north.lon - origin.lon) < 1e-6)
    }

    @Test
    fun `bbox expand grows all edges`() {
        val box = BoundingBox(39.0, -98.0, 39.1, -97.9).expand(1000.0)
        assertTrue(box.minLat < 39.0 && box.maxLat > 39.1)
        assertTrue(box.minLon < -98.0 && box.maxLon > -97.9)
    }

    @Test
    fun `floorTo handles negatives`() {
        assertEquals(20, floorTo(39.8, 20))
        assertEquals(-100, floorTo(-98.1, 20))
        assertEquals(-100, floorTo(-100.0, 20))
        assertEquals(0, floorTo(0.0, 20))
        assertEquals(-20, floorTo(-0.1, 20))
    }
}
