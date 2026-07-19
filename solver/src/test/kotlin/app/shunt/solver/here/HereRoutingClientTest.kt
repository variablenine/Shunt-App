package app.shunt.solver.here

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.BoundingBox
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class HereRoutingClientTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun client() = HereRoutingClient(
        http = OkHttpClient(),
        apiKey = "test-key",
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/here/$name")!!.readBytes().decodeToString()

    /**
     * Response fixture recorded from the live v8 endpoint 2026-07-19
     * (Green Bay → Marinette, alternatives=2).
     */
    @Test
    fun `parses live routes fixture`() {
        val routes = HereRoutingClient.parseRoutes(fixture("routes-v8.json"))
        assertEquals(3, routes.size)
        val fastest = routes.minBy { it.durationSeconds }
        assertEquals(3285, fastest.durationSeconds)
        assertEquals(87076, fastest.lengthMeters)
        // Polyline decodes to a plausible corridor: dense, and within the
        // Green Bay → Marinette bounding box.
        assertTrue(fastest.polyline.size > 100)
        assertTrue(fastest.polyline.all { it.lat in 44.4..45.2 && it.lon in -88.2..-87.5 })
        // Endpooints near the requested origin/destination.
        assertTrue(fastest.polyline.first().lat in 44.50..44.53)
        assertTrue(fastest.polyline.last().lat in 45.08..45.10)
    }

    /** Geocode fixture recorded live 2026-07-19 ("Lambeau Field, Green Bay, WI"). */
    @Test
    fun `parses live geocode fixture`() {
        val point = HereGeocoder.parse(fixture("geocode-v1.json"))
        assertEquals(GeoPoint(44.50099, -88.0613), point)
    }

    /**
     * Synthetic response in HERE's documented v8 shape; the polyline is the
     * reference vector from the flexible-polyline spec.
     */
    private val documentedShapeBody = """
        {"routes": [{"id": "r1", "sections": [{
            "id": "s1", "type": "vehicle",
            "polyline": "BFoz5xJ67i1B1B7PzIhaxL7Y",
            "summary": {"duration": 543, "length": 8000, "baseDuration": 500}
        }]}]}
    """.trimIndent()

    @Test
    fun `parses documented v8 response shape`() = runTest {
        server.enqueue(MockResponse().setBody(documentedShapeBody))
        val routes = client().routes(GeoPoint(50.1, 8.69), GeoPoint(50.09, 8.68))
        assertEquals(1, routes.size)
        assertEquals(543, routes[0].durationSeconds)
        assertEquals(8000, routes[0].lengthMeters)
        assertEquals(4, routes[0].polyline.size)
    }

    @Test
    fun `request carries avoid areas in bbox format and alternatives`() = runTest {
        server.enqueue(MockResponse().setBody("""{"routes": []}"""))
        client().routes(
            GeoPoint(45.0, -88.0), GeoPoint(45.1, -87.9),
            avoidAreas = listOf(
                BoundingBox(45.01, -88.01, 45.02, -88.0),
                BoundingBox(45.03, -87.95, 45.04, -87.94),
            ),
            alternatives = 3,
        )
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("car", url.queryParameter("transportMode"))
        assertEquals("3", url.queryParameter("alternatives"))
        // bbox:west,south,east,north — |-separated (verified live; `!` 400s)
        assertEquals(
            "bbox:-88.01,45.01,-88.0,45.02|bbox:-87.95,45.03,-87.94,45.04",
            url.queryParameter("avoid[areas]"),
        )
    }

    @Test
    fun `rejects more than 20 avoid areas locally`() = runTest {
        val boxes = (0 until 21).map {
            BoundingBox(45.0 + it * 0.01, -88.0, 45.005 + it * 0.01, -87.99)
        }
        assertFailsWith<IllegalArgumentException> {
            client().routes(GeoPoint(45.0, -88.0), GeoPoint(46.0, -87.0), avoidAreas = boxes)
        }
        assertEquals(0, server.requestCount, "must fail before any HTTP call")
    }

    @Test
    fun `http error surfaces loudly`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Too Many Requests"}"""))
        val e = assertFailsWith<java.io.IOException> {
            client().routes(GeoPoint(45.0, -88.0), GeoPoint(45.1, -87.9))
        }
        assertTrue("429" in e.message.orEmpty())
    }

    @Test
    fun `multi-section routes are concatenated and summed`() {
        val body = """
            {"routes": [{"sections": [
                {"polyline": "BFoz5xJ67i1B1B7PzIhaxL7Y", "summary": {"duration": 100, "length": 1000}},
                {"polyline": "BFoz5xJ67i1B1B7PzIhaxL7Y", "summary": {"duration": 50, "length": 500}}
            ]}]}
        """.trimIndent()
        val routes = HereRoutingClient.parseRoutes(body)
        assertEquals(1, routes.size)
        assertEquals(150, routes[0].durationSeconds)
        assertEquals(1500, routes[0].lengthMeters)
        assertEquals(8, routes[0].polyline.size)
    }

    @Test
    fun `geocoder parses documented v1 shape`() {
        val point = HereGeocoder.parse(
            """{"items":[{"title":"X","position":{"lat":44.51,"lng":-88.01}}]}"""
        )
        assertEquals(GeoPoint(44.51, -88.01), point)
        assertEquals(null, HereGeocoder.parse("""{"items":[]}"""))
    }
}
