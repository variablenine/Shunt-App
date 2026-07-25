package app.shunt.tesla

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * The read-only credential check. It must never command the car — the whole
 * point is answering "are my credentials working?" without something happening
 * in a car park.
 */
class TessieAccountClientTest {

    private fun client(server: MockWebServer) =
        TessieAccountClient(OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))

    @Test
    fun `a working token lists the vehicles and never sends a command`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"response":[{"vin":"5YJ3E1EA0PF000001","display_name":"Car","state":"online"}]}""",
            ),
        )
        val result = client(server).check("tok")
        val ok = assertIs<ConnectionCheck.Ok>(result)
        assertEquals("5YJ3E1EA0PF000001", ok.vehicles.single().vin)
        assertTrue(ok.vehicles.single().isAwake)

        val request = server.takeRequest()
        assertEquals("GET", request.method, "checking must be read-only")
        assertTrue("/command/" !in request.path.orEmpty(), "must not hit a command endpoint")
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        server.shutdown()
    }

    @Test
    fun `a rejected token is reported as a bad token, not an outage`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        assertIs<ConnectionCheck.BadToken>(client(server).check("nope"))
        server.shutdown()
    }

    @Test
    fun `an unreachable service is not mistaken for a bad token`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        assertIs<ConnectionCheck.Unreachable>(client(server).check("tok"))
        server.shutdown()
    }

    @Test
    fun `a blank token never reaches the network`() = runTest {
        val server = MockWebServer()
        assertIs<ConnectionCheck.BadToken>(client(server).check("   "))
        assertEquals(0, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `reads the active route and battery without waking the car`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"drive_state":{"active_route_destination":"Prairie Supercharger",
                    "active_route_latitude":39.12,"active_route_longitude":-98.34,
                    "active_route_miles_to_arrival":42.5,
                    "active_route_energy_at_arrival":18.0},
                    "charge_state":{"battery_level":64,"est_battery_range":180.5}}""",
            ),
        )
        val route = client(server).activeRoute("tok", "VIN")!!
        assertEquals("Prairie Supercharger", route.destinationName)
        assertEquals(39.12, route.latitude!!, 1e-6)
        assertEquals(64, route.batteryLevel)
        assertTrue(route.isNavigating)

        val request = server.takeRequest()
        assertEquals("GET", request.method, "reading state must never command the car")
        // use_cache keeps a sleeping car asleep.
        assertTrue("use_cache=true" in request.path.orEmpty(), "path was ${request.path}")
        server.shutdown()
    }

    @Test
    fun `a car that is not navigating reports so rather than failing`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"charge_state":{"battery_level":80}}"""))
        val route = client(server).activeRoute("tok", "VIN")!!
        assertTrue(!route.isNavigating)
        assertEquals(80, route.batteryLevel)
        server.shutdown()
    }
}
