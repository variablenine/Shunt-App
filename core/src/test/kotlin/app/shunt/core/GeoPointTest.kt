package app.shunt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeoPointTest {
    @Test
    fun `valid coordinates construct`() {
        val p = GeoPoint(39.5, -98.5)
        assertEquals(39.5, p.lat)
        assertEquals(-98.5, p.lon)
    }

    @Test
    fun `out-of-range latitude rejected`() {
        assertFailsWith<IllegalArgumentException> { GeoPoint(91.0, 0.0) }
    }

    @Test
    fun `out-of-range longitude rejected`() {
        assertFailsWith<IllegalArgumentException> { GeoPoint(0.0, -180.5) }
    }
}
