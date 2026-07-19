package app.shunt.solver.here

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlexiblePolylineTest {

    /** Reference vector from github.com/heremaps/flexible-polyline README. */
    @Test
    fun `decodes reference 2d polyline`() {
        val points = FlexiblePolyline.decode("BFoz5xJ67i1B1B7PzIhaxL7Y")
        val expected = listOf(
            50.10228 to 8.69821,
            50.10201 to 8.69567,
            50.10063 to 8.69150,
            50.09878 to 8.68752,
        )
        assertEquals(expected.size, points.size)
        expected.zip(points).forEach { (e, p) ->
            assertTrue(abs(e.first - p.lat) < 1e-5, "lat ${p.lat} != ${e.first}")
            assertTrue(abs(e.second - p.lon) < 1e-5, "lon ${p.lon} != ${e.second}")
        }
    }

    /** Reference vector with a third dimension (altitude), which we skip. */
    @Test
    fun `decodes polyline with third dimension`() {
        val points = FlexiblePolyline.decode("BlBoz5xJ67i1BU1B7PUzIhaUxL7YU")
        assertEquals(4, points.size)
        assertTrue(abs(points[0].lat - 50.10228) < 1e-5)
        assertTrue(abs(points[0].lon - 8.69821) < 1e-5)
        assertTrue(abs(points[3].lat - 50.09878) < 1e-5)
    }

    @Test
    fun `rejects garbage`() {
        assertFailsWith<IllegalArgumentException> { FlexiblePolyline.decode("") }
        assertFailsWith<IllegalArgumentException> { FlexiblePolyline.decode("!!!") }
    }
}
