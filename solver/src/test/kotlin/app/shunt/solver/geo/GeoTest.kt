package app.shunt.solver.geo

import app.shunt.core.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    @Test
    fun `haversine matches known distance`() {
        // Green Bay to Milwaukee, ~158 km
        val d = haversineMeters(GeoPoint(44.5133, -88.0133), GeoPoint(43.0389, -87.9065))
        assertTrue(abs(d - 164_000) < 5_000, "got $d")
    }

    @Test
    fun `point on segment has zero distance`() {
        val a = GeoPoint(45.0, -88.0)
        val b = GeoPoint(45.0, -87.9)
        val mid = GeoPoint(45.0, -87.95)
        assertTrue(pointToSegmentMeters(mid, a, b) < 1.0)
    }

    @Test
    fun `point beside segment measures perpendicular offset`() {
        val a = GeoPoint(45.0, -88.0)
        val b = GeoPoint(45.0, -87.9)
        // ~44.5 m north of the line's midpoint (0.0004° lat)
        val p = GeoPoint(45.0004, -87.95)
        val d = pointToSegmentMeters(p, a, b)
        assertTrue(abs(d - 44.5) < 1.0, "got $d")
    }

    @Test
    fun `point past segment end clamps to endpoint`() {
        val a = GeoPoint(45.0, -88.0)
        val b = GeoPoint(45.0, -87.99)
        val p = GeoPoint(45.0, -87.9)
        val expected = haversineMeters(p, b)
        assertTrue(abs(pointToSegmentMeters(p, a, b) - expected) < expected * 0.01)
    }

    @Test
    fun `bearing cardinal directions`() {
        val o = GeoPoint(45.0, -88.0)
        assertTrue(abs(bearingDegrees(o, GeoPoint(46.0, -88.0)) - 0.0) < 1.0)
        assertTrue(abs(bearingDegrees(o, GeoPoint(45.0, -87.0)) - 90.0) < 1.0)
        assertTrue(abs(bearingDegrees(o, GeoPoint(44.0, -88.0)) - 180.0) < 1.0)
    }

    @Test
    fun `bearing difference wraps`() {
        assertEquals(20.0, bearingDifference(350.0, 10.0), 1e-9)
        assertEquals(180.0, bearingDifference(0.0, 180.0), 1e-9)
        assertEquals(0.0, bearingDifference(90.0, 90.0), 1e-9)
    }

    @Test
    fun `bbox expand grows all edges`() {
        val box = BoundingBox(45.0, -88.0, 45.1, -87.9).expand(1000.0)
        assertTrue(box.minLat < 45.0 && box.maxLat > 45.1)
        assertTrue(box.minLon < -88.0 && box.maxLon > -87.9)
    }

    @Test
    fun `floorTo handles negatives`() {
        assertEquals(40, floorTo(45.8, 20))
        assertEquals(-100, floorTo(-88.1, 20))
        assertEquals(-100, floorTo(-100.0, 20))
        assertEquals(0, floorTo(0.0, 20))
        assertEquals(-20, floorTo(-0.1, 20))
    }
}
