package app.shunt.solver.search

import app.shunt.core.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.OkHttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhotonSearchTest {

    @Test
    fun `being throttled is not a failed search`() = runTest {
        // A burst of keystrokes can earn a 429. Throwing there is how the UI
        // came to announce "Couldn't reach search — check your connection"
        // against a geocoder that was merely asking us to slow down; the
        // caller can then try the other one instead.
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(429))
            val search = PhotonSearch(OkHttpClient(), server.url("/").toString().trimEnd('/'))
            assertEquals(emptyList(), search.suggest("civic", GeoPoint(39.0, -98.0)))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a real server error still surfaces`() = runTest {
        // Throttling is temporary and expected; a 500 is neither, and hiding it
        // would leave the user staring at an empty list with no explanation.
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            val search = PhotonSearch(OkHttpClient(), server.url("/").toString().trimEnd('/'))
            assertFailsWith<java.io.IOException> { search.suggest("civic", GeoPoint(39.0, -98.0)) }
        } finally {
            server.shutdown()
        }
    }

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
        val ranked = rankByProximity(photonOrder, user)
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
        val ranked = rankByProximity(photonOrder, user)
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
        val ranked = rankByProximity(photonOrder, user)
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

    @Test
    fun `a name search looks near the driver before it looks at the world`() = runTest {
        // Measured, and the largest single improvement search has had.
        // `location_bias_scale` is a preference and loses to raw OSM importance:
        // "Concordia Public Library" returned a library in Hong Kong, "brown
        // grand theatre" one in Warsaw. No amount of re-ranking fixes that,
        // because the local answer is not in the response to re-rank.
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(ONE_RESULT))
            val search = PhotonSearch(OkHttpClient(), server.url("/").toString().trimEnd('/'))
            search.suggest("library", GeoPoint(39.0, -98.0))

            val asked = server.takeRequest().requestUrl!!
            val bbox = asked.queryParameter("bbox")
            assertTrue(bbox != null, "the first search must be bounded to the driver's area")
            val parts = bbox.split(",").map { it.toDouble() }
            assertTrue(
                parts[0] < -98.0 && parts[2] > -98.0 && parts[1] < 39.0 && parts[3] > 39.0,
                "the box must contain the driver: $bbox",
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `nothing nearby widens to the whole world`() = runTest {
        // The other half. Bounded-only would make a deliberately distant
        // destination unfindable, which is the mistake NominatimSearch documents
        // avoiding for exactly the same reason.
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(NO_RESULTS))
            server.enqueue(MockResponse().setBody(ONE_RESULT))
            val search = PhotonSearch(OkHttpClient(), server.url("/").toString().trimEnd('/'))

            assertEquals(1, search.suggest("somewhere far", GeoPoint(39.0, -98.0)).size)
            assertTrue(server.takeRequest().requestUrl!!.queryParameter("bbox") != null)
            assertTrue(
                server.takeRequest().requestUrl!!.queryParameter("bbox") == null,
                "the widened search must drop the box, not merely enlarge it",
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a throttled search is not retried wider`() = runTest {
        // A 429 is the service asking us to slow down. Answering it with a
        // second request is the opposite, and the caller has another geocoder
        // to try anyway.
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(429))
            val search = PhotonSearch(OkHttpClient(), server.url("/").toString().trimEnd('/'))
            assertEquals(emptyList(), search.suggest("civic", GeoPoint(39.0, -98.0)))
            assertEquals(1, server.requestCount, "a throttled search was retried")
        } finally {
            server.shutdown()
        }
    }

    private companion object {
        const val NO_RESULTS = """{"features":[]}"""
        const val ONE_RESULT =
            """{"features":[{"geometry":{"coordinates":[-98.01,39.01]},""" +
                """"properties":{"name":"Civic Center","city":"Anytown","state":"KS"}}]}"""
    }
}
