package app.shunt.tesla

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach

/**
 * The production client must satisfy the very same contract the fake does.
 * Each state is backed by a scripted MockWebServer — never a live vehicle.
 */
class TessieVehicleNavClientContractTest : VehicleNavClientContract() {

    private val servers = mutableListOf<MockWebServer>()

    @AfterEach
    fun shutdownServers() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    private fun clientFor(response: (RecordedRequest) -> MockResponse): VehicleNavClient {
        val server = MockWebServer().also { servers += it }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = response(request)
        }
        return TessieVehicleNavClient(
            http = OkHttpClient(),
            bearerToken = "test-token",
            vin = "5YJ3TESTVIN",
            baseUrl = server.url("").toString().trimEnd('/'),
        )
    }

    override fun healthyClient(): VehicleNavClient =
        clientFor { MockResponse().setBody("""{"response":{"result":true,"reason":""}}""") }

    override fun retryablyFailingClient(): VehicleNavClient =
        clientFor { MockResponse().setResponseCode(503) } // server error — transient

    override fun permanentlyFailingClient(): VehicleNavClient =
        clientFor { MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}""") }
}
