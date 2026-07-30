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
        val user = GeoPoint(39.0, -98.0)
        // Photon relevance order puts the famous distant landmark first.
        val photonOrder = listOf(
            at("Summit Peak", 44.0, -110.0), // far
            at("Summit Diner", 39.2, -98.1), // ~24 km, local
        )
        val ranked = PhotonSearch.rankByProximity(photonOrder, user)
        assertEquals("Summit Diner", ranked.first().title)
    }

    @Test
    fun `distant results are ordered nearest-first too`() {
        val user = GeoPoint(39.0, -98.0)
        // Photon ranks by OSM importance, so a long-distance search fills up
        // with famous namesakes in no useful order. Nothing here is local, but
        // the reachable one should still lead.
        val photonOrder = listOf(
            at("Springfield, Massachusetts", 42.10, -72.59),
            at("Springfield, Missouri", 37.21, -93.29),
        )
        val ranked = PhotonSearch.rankByProximity(photonOrder, user)
        assertEquals("Springfield, Missouri", ranked.first().title)
    }

    @Test
    fun `nearby results are ordered nearest-first, relevance breaking ties`() {
        val user = GeoPoint(39.0, -98.0)
        val photonOrder = listOf(
            at("Far Cafe", 39.6, -98.0), // ~67 km
            at("Near Cafe", 39.05, -98.0), // ~6 km
            // Same distance band as Near Cafe: Photon's order must survive.
            at("Also Near Cafe", 39.06, -98.0),
        )
        val ranked = PhotonSearch.rankByProximity(photonOrder, user)
        assertEquals(
            listOf("Near Cafe", "Also Near Cafe", "Far Cafe"),
            ranked.map { it.title },
        )
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
        assertTrue("Kansas" in first.title || "KS" in first.title, "title should carry the place")
        // Coordinates decode from GeoJSON [lon, lat] order.
        assertEquals(38.8400000, first.location.lat, 1e-6)
        assertEquals(-97.6100000, first.location.lon, 1e-6)
    }

    @Test
    fun `an address without a name uses house number and street`() {
        // Photon returns plain addresses with no POI name — fall back to number + street.
        val body = """
            {"type":"FeatureCollection","features":[{"type":"Feature",
             "properties":{"housenumber":"1717","street":"South Main Street",
              "city":"Lindsborg","state":"KS","osm_value":"house"},
             "geometry":{"type":"Point","coordinates":[-97.6741,38.5736]}}]}
        """.trimIndent()
        val first = PhotonSearch.parse(body).single()
        assertEquals("1717 South Main Street, Lindsborg, KS", first.title)
    }

    @Test
    fun `malformed or empty bodies yield no suggestions rather than throwing`() {
        assertTrue(PhotonSearch.parse("""{"type":"FeatureCollection","features":[]}""").isEmpty())
    }
}
