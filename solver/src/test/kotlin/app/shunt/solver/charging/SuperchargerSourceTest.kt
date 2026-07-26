package app.shunt.solver.charging

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.BoundingBox
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SuperchargerSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: SuperchargerSource

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        source = SuperchargerSource(OkHttpClient(), server.url("/api/interpreter").toString())
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private val box = BoundingBox(38.5, -98.5, 39.5, -97.5)

    @Test
    fun `nodes and ways both yield a usable coordinate`() {
        val body = """
            {"elements":[
              {"type":"node","id":1,"lat":39.1,"lon":-98.1,
               "tags":{"amenity":"charging_station","brand":"Tesla","name":"Supercharger North"}},
              {"type":"way","id":2,"center":{"lat":39.2,"lon":-98.2},
               "tags":{"amenity":"charging_station","operator":"Tesla, Inc."}}
            ]}
        """.trimIndent()

        val parsed = SuperchargerSource.parse(body)

        assertEquals(2, parsed.size)
        assertEquals(GeoPoint(39.1, -98.1), parsed[0].location)
        assertEquals("Supercharger North", parsed[0].name)
        // A way is mapped as an area; its center is the only point it has.
        assertEquals(GeoPoint(39.2, -98.2), parsed[1].location)
        assertEquals("Tesla, Inc.", parsed[1].name, "falls back to the operator when unnamed")
    }

    @Test
    fun `an element with no coordinate at all is dropped, not guessed`() {
        val parsed = SuperchargerSource.parse("""{"elements":[{"type":"way","id":3,"tags":{}}]}""")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `the query covers every way Tesla sites are tagged in OSM`() {
        val query = SuperchargerSource.overpassQuery(box)
        for (tag in listOf("brand", "operator", "network", "socket:tesla_supercharger")) {
            assertTrue(tag in query, "missing $tag: sites tagged only that way would be invisible")
        }
        assertTrue("38.5,-98.5,39.5,-97.5" in query)
    }

    @Test
    fun `a failing service yields no chargers rather than an exception`() = runTest {
        // This runs alongside routing the user actually asked for; it must
        // degrade the suggestion, never break the plan.
        server.enqueue(MockResponse().setResponseCode(504))
        assertTrue(source.inBox(box).isEmpty())
    }

    @Test
    fun `nonsense in the response body yields no chargers`() = runTest {
        server.enqueue(MockResponse().setBody("<html>rate limited</html>"))
        assertTrue(source.inBox(box).isEmpty())
    }

    @Test
    fun `the query is posted, keylessly, with a contactable user agent`() = runTest {
        server.enqueue(MockResponse().setBody("""{"elements":[]}"""))
        source.inBox(box)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue("charging_station" in request.body.readUtf8())
        assertEquals(SuperchargerSource.USER_AGENT, request.getHeader("User-Agent"))
        assertNull(request.getHeader("Authorization"), "this source must need no account")
    }

    // ---- Choosing which one to stop at -----------------------------------

    /** A straight eastbound line, roughly 87 km end to end. */
    private val route = listOf(GeoPoint(39.0, -98.5), GeoPoint(39.0, -97.5))

    private fun charger(id: Long, lat: Double, lon: Double) =
        Supercharger(id, "Charger $id", GeoPoint(lat, lon))

    @Test
    fun `picks the reachable charger farthest along the route`() {
        val near = charger(1, 39.0, -98.4)
        val far = charger(2, 39.0, -97.9)
        val tooFar = charger(3, 39.0, -97.55)

        val chosen = chooseChargeStop(route, listOf(near, far, tooFar), reachableMeters = 60_000.0)

        assertEquals(
            far,
            chosen,
            "stopping at the first one you pass wastes the charge already in the battery",
        )
    }

    @Test
    fun `a charger off in the countryside is not on the way`() {
        val offRoute = charger(1, 39.4, -98.0) // ~45 km north of the line
        assertNull(chooseChargeStop(route, listOf(offRoute), reachableMeters = 80_000.0))
    }

    @Test
    fun `nothing within reach yields nothing rather than a guess`() {
        val far = charger(1, 39.0, -97.6)
        assertNull(chooseChargeStop(route, listOf(far), reachableMeters = 5_000.0))
    }

    @Test
    fun `the hop off the route counts against what is reachable`() {
        // 6 km off the line, at a point ~35 km along: reachable at 45 km of
        // range, not at 38 km, even though the along-route distance fits both.
        val sideOfRoad = charger(1, 39.054, -98.095)
        assertEquals(sideOfRoad, chooseChargeStop(route, listOf(sideOfRoad), reachableMeters = 45_000.0))
        assertNull(chooseChargeStop(route, listOf(sideOfRoad), reachableMeters = 38_000.0))
    }

    @Test
    fun `no candidates and degenerate routes are handled`() {
        assertNull(chooseChargeStop(route, emptyList(), reachableMeters = 80_000.0))
        assertNull(chooseChargeStop(listOf(GeoPoint(39.0, -98.5)), listOf(charger(1, 39.0, -98.4)), 80_000.0))
    }
}
