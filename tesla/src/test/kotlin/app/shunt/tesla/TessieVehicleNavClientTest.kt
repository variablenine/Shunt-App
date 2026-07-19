package app.shunt.tesla

import app.shunt.core.GeoPoint
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class TessieVehicleNavClientTest {
    private val server = MockWebServer()

    private val chain = listOf(
        GeoPoint(44.5133, -88.0133),
        GeoPoint(44.7659, -88.0040),
        GeoPoint(45.0906, -87.6431),
    )

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun client() = TessieVehicleNavClient(
        http = OkHttpClient(),
        bearerToken = "test-token",
        vin = "5YJ3TESTVIN",
        baseUrl = server.url("").toString().trimEnd('/'),
    )

    private val ok = """{"response":{"result":true,"reason":""}}"""

    private fun dispatch(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    private fun RecordedRequest.command(): String = path!!.substringAfterLast("/command/")

    @Test
    fun `fast path used and cached when the vehicle supports waypoints_request`() = runTest {
        dispatch { MockResponse().setBody(ok) }
        val client = client()

        assertEquals(PushResult.Success, client.pushRoute(chain))
        val first = server.takeRequest()
        assertEquals("navigation_waypoints_request", first.command())
        assertEquals("Bearer test-token", first.getHeader("Authorization"))
        // Body is the |-separated lat,lon chain.
        val waypoints = Json.parseToJsonElement(first.body.readUtf8()).jsonObject["waypoints"]!!.jsonPrimitive.content
        assertEquals("44.5133,-88.0133|44.7659,-88.004|45.0906,-87.6431", waypoints)

        // A second call must reuse the fast path (one request, no gps chain).
        assertEquals(PushResult.Success, client.advanceTo(chain.drop(1)))
        assertEquals("navigation_waypoints_request", server.takeRequest().command())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `falls back to the gps chain when waypoints_request is unsupported, and caches it`() = runTest {
        dispatch { request ->
            if (request.command() == "navigation_waypoints_request") MockResponse().setResponseCode(404)
            else MockResponse().setBody(ok)
        }
        val client = client()

        assertEquals(PushResult.Success, client.pushRoute(chain))
        // 1 probe (404) + 3 gps points.
        assertEquals("navigation_waypoints_request", server.takeRequest().command())
        val orders = mutableListOf<Int>()
        repeat(3) {
            val req = server.takeRequest()
            assertEquals("navigation_gps_request", req.command())
            val obj = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            orders += obj["order"]!!.jsonPrimitive.content.toInt()
        }
        assertEquals(listOf(1, 3, 3), orders, "first replaces the trip, the rest append")

        // Second call skips the probe entirely — straight to gps. (The 4
        // requests above are already drained, so the next one is this call's.)
        assertEquals(PushResult.Success, client.advanceTo(chain.drop(1)))
        assertEquals("navigation_gps_request", server.takeRequest().command())
    }

    @Test
    fun `gps body carries exact lat lon and order`() = runTest {
        dispatch { request ->
            if (request.command() == "navigation_waypoints_request") MockResponse().setResponseCode(404)
            else MockResponse().setBody(ok)
        }
        val client = client()
        client.pushRoute(listOf(GeoPoint(45.0, -88.0)))
        server.takeRequest() // probe
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(45.0, body["lat"]!!.jsonPrimitive.content.toDouble())
        assertEquals(-88.0, body["lon"]!!.jsonPrimitive.content.toDouble())
        assertEquals(1, body["order"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `auth failure is permanent and does not fall back`() = runTest {
        dispatch { MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}""") }
        val client = client()
        val failed = assertIs<PushResult.Failed>(client.pushRoute(chain))
        assertTrue(!failed.retryable, "401 must be non-retryable")
        // Only the probe was attempted — no gps fallback for an auth error.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `server error is retryable`() = runTest {
        dispatch { MockResponse().setResponseCode(503) }
        val failed = assertIs<PushResult.Failed>(client().pushRoute(chain))
        assertTrue(failed.retryable, "503 must be retryable")
    }

    @Test
    fun `vehicle timeout while asleep is retryable`() = runTest {
        dispatch { MockResponse().setResponseCode(408) }
        val failed = assertIs<PushResult.Failed>(client().pushRoute(chain))
        assertTrue(failed.retryable)
    }

    @Test
    fun `result false in a 200 surfaces as failure, never a false success`() = runTest {
        dispatch { request ->
            if (request.command() == "navigation_waypoints_request") MockResponse().setResponseCode(404)
            else MockResponse().setBody("""{"response":{"result":false,"reason":"vehicle_unavailable"}}""")
        }
        val failed = assertIs<PushResult.Failed>(client().pushRoute(chain))
        assertEquals("vehicle_unavailable", failed.reason)
        assertTrue(failed.retryable, "vehicle_unavailable is transient")
    }

    @Test
    fun `mid-chain failure stops and reports that failure`() = runTest {
        var gpsCalls = 0
        dispatch { request ->
            when {
                request.command() == "navigation_waypoints_request" -> MockResponse().setResponseCode(404)
                else -> {
                    gpsCalls++
                    if (gpsCalls == 2) MockResponse().setResponseCode(500)
                    else MockResponse().setBody(ok)
                }
            }
        }
        val failed = assertIs<PushResult.Failed>(client().pushRoute(chain))
        assertTrue(failed.retryable)
        // Stopped after the failing 2nd point — the 3rd was never sent.
        assertEquals(2, gpsCalls)
    }

    @Test
    fun `empty chain fails without any network call`() = runTest {
        dispatch { MockResponse().setBody(ok) }
        val failed = assertIs<PushResult.Failed>(client().pushRoute(emptyList()))
        assertTrue(!failed.retryable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `network error is retryable`() = runTest {
        // A client pointed at a dead port surfaces a connectivity failure.
        val client = TessieVehicleNavClient(
            http = OkHttpClient(),
            bearerToken = "t",
            vin = "v",
            baseUrl = "http://127.0.0.1:1", // nothing listening
        )
        val failed = assertIs<PushResult.Failed>(client.pushRoute(chain))
        assertTrue(failed.retryable, "connectivity failures must be retryable")
    }
}
