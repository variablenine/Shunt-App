package app.shunt.solver.search

import app.shunt.core.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class NominatimSearchTest {

    @Test
    fun `a house number result becomes a readable street address`() {
        // The shape Nominatim returns for a plain address: no POI name, the
        // detail lives in `address`. This is the case Photon misses entirely.
        val body = """
            [{"lat":"39.7417547","lon":"-98.4290689","name":"","category":"place",
              "type":"house",
              "display_name":"5260, Prairie Road, Rockton, Smith County, Kansas, 66967, United States",
              "address":{"house_number":"5260","road":"Prairie Road","village":"Rockton",
                         "county":"Smith County","state":"Kansas"}}]
        """.trimIndent()
        val first = NominatimSearch.parse(body).single()
        assertEquals("5260 Prairie Road, Rockton, Kansas", first.title)
        assertEquals(39.7417547, first.location.lat, 1e-6)
        assertEquals(-98.4290689, first.location.lon, 1e-6)
    }

    @Test
    fun `a named POI keeps its name and place`() {
        val body = """
            [{"lat":"39.8758417","lon":"-98.0894523","name":"Prairie Diner",
              "category":"amenity","type":"restaurant",
              "display_name":"Prairie Diner, Highway 9, Smith County, Kansas, United States",
              "address":{"county":"Smith County","state":"Kansas"}}]
        """.trimIndent()
        val first = NominatimSearch.parse(body).single()
        assertEquals("Prairie Diner, Smith County, Kansas", first.title)
        assertEquals("restaurant", first.resultType)
    }

    @Test
    fun `an empty array yields no suggestions`() {
        assertTrue(NominatimSearch.parse("[]").isEmpty())
    }

    // ---- Near me first, the world only if that finds nothing ---------------
    //
    // Measured against the public instance from a point in Kansas: a viewbox
    // alone is only a preference, and for a name with many namesakes it loses —
    // "starbucks" came back as cafes 889 to 11,500 km away while the ones a few
    // blocks off never appeared. `bounded=1` fixes that outright, but on its own
    // it breaks the other half of the job: "Fontano's Subs Chicago" typed from
    // Kansas returns nothing bounded and resolves fine unbounded.

    private val at = GeoPoint(39.0, -98.0)

    private fun serverWith(vararg bodies: String): Pair<MockWebServer, NominatimSearch> {
        val server = MockWebServer().apply { start() }
        bodies.forEach { server.enqueue(MockResponse().setBody(it)) }
        val search = NominatimSearch(
            http = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
            minIntervalMillis = 0,
        )
        return server to search
    }

    private fun place(name: String, lat: Double, lon: Double) =
        """{"lat":"$lat","lon":"$lon","name":"$name","category":"amenity","type":"cafe",
            "display_name":"$name","address":{"state":"Kansas"}}"""

    @Test
    fun `a local hit costs one request and never widens`() = runTest {
        val (server, search) = serverWith("[${place("Corner Cafe", 39.05, -98.05)}]")
        try {
            val results = search.suggest("corner cafe", at)
            assertEquals(listOf("Corner Cafe, Kansas"), results.map { it.title })
            assertEquals(1, server.requestCount, "widening after a local hit spends a rate-limited call for nothing")
            val asked = server.takeRequest().requestUrl!!
            assertEquals("1", asked.queryParameter("bounded"), "the near search must actually be bounded")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `nothing nearby widens to the world`() = runTest {
        // The trip-planning case: somewhere named on purpose, hundreds of km off.
        val (server, search) = serverWith("[]", "[${place("Far Landmark", 41.9, -87.6)}]")
        try {
            val results = search.suggest("far landmark", at)
            assertEquals(listOf("Far Landmark, Kansas"), results.map { it.title })
            assertEquals(2, server.requestCount)
            assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("bounded"))
            assertEquals(
                null,
                server.takeRequest().requestUrl!!.queryParameter("bounded"),
                "the second pass has to be unbounded or a distant destination can never resolve",
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `results come back nearest-first`() = runTest {
        // Nominatim orders by its own notion of importance, which for a driving
        // app is the wrong end of the list.
        val (server, search) = serverWith(
            "[${place("Far", 41.9, -87.6)},${place("Near", 39.02, -98.02)}]",
        )
        try {
            assertEquals(listOf("Near, Kansas", "Far, Kansas"), search.suggest("cafe", at).map { it.title })
        } finally {
            server.shutdown()
        }
    }
}
