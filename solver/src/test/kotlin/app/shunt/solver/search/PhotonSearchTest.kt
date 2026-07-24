package app.shunt.solver.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotonSearchTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/photon/$name")) { "missing $name" }
            .bufferedReader().readText()

    @Test
    fun `parses Photon results into titled suggestions with coordinates`() {
        val suggestions = PhotonSearch.parse(fixture("search.json"))
        assertTrue(suggestions.isNotEmpty())

        val first = suggestions.first()
        assertTrue(first.title.startsWith("Walmart"), "title was '${first.title}'")
        assertTrue("Wisconsin" in first.title || "WI" in first.title, "title should carry the place")
        // Coordinates decode from GeoJSON [lon, lat] order.
        assertEquals(45.1631199, first.location.lat, 1e-6)
        assertEquals(-89.1434382, first.location.lon, 1e-6)
    }

    @Test
    fun `an address without a name uses house number and street`() {
        // Photon returns plain addresses with no POI name — fall back to number + street.
        val body = """
            {"type":"FeatureCollection","features":[{"type":"Feature",
             "properties":{"housenumber":"1717","street":"North Shawano Street",
              "city":"New London","state":"WI","osm_value":"house"},
             "geometry":{"type":"Point","coordinates":[-88.7439875,44.411585]}}]}
        """.trimIndent()
        val first = PhotonSearch.parse(body).single()
        assertEquals("1717 North Shawano Street, New London, WI", first.title)
    }

    @Test
    fun `malformed or empty bodies yield no suggestions rather than throwing`() {
        assertTrue(PhotonSearch.parse("""{"type":"FeatureCollection","features":[]}""").isEmpty())
    }
}
