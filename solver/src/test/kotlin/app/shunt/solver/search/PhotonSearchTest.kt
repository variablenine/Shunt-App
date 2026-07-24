package app.shunt.solver.search

import app.shunt.core.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotonSearchTest {

    private fun at(title: String, lat: Double, lon: Double) =
        Suggestion(title, GeoPoint(lat, lon), "place")

    @Test
    fun `a nearby match is promoted above a far-away namesake`() {
        val user = GeoPoint(45.82, -88.07) // Upper Michigan
        // Photon relevance order puts the famous distant El Capitan first.
        val photonOrder = listOf(
            at("El Capitan, California", 37.73, -119.64), // ~1600 mi
            at("El Capitan Supper Club", 45.90, -88.00), // ~6 mi, local
        )
        val ranked = PhotonSearch.rankByProximity(photonOrder, user)
        assertEquals("El Capitan Supper Club", ranked.first().title)
    }

    @Test
    fun `an all-distant result set keeps Photon's relevance order`() {
        val user = GeoPoint(45.82, -88.07)
        val photonOrder = listOf(
            at("Portland, Oregon", 45.52, -122.68),
            at("Portland, Maine", 43.66, -70.26),
        )
        // No local match to promote — order is untouched.
        assertEquals(photonOrder, PhotonSearch.rankByProximity(photonOrder, user))
    }

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
