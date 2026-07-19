package app.shunt.solver.camera

import app.shunt.solver.geo.BoundingBox
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class DeFlockCameraSourceTest {
    private val server = MockWebServer()
    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "shunt-test-${System.nanoTime()}")
    private var now = 1_000_000L

    private val bbox = BoundingBox(44.9, -88.1, 45.1, -87.9) // inside tile 40/-100

    // Note: braces must stay literal — HttpUrl would percent-encode them.
    private fun tileUrlTemplate(v: String) = "${server.url("/regions/")}{lat}/{lon}.json?v=$v"

    private fun indexJson(expiration: Long, v: String = "77") = """
        {"expiration_utc": $expiration,
         "regions": ["40/-100", "40/-80"],
         "tile_url": "${tileUrlTemplate(v)}",
         "tile_size_degrees": 20}
    """.trimIndent()

    private val tileJson = """
        [{"id": 1, "lat": 45.0, "lon": -88.0, "tags": {"direction": "90"}},
         {"id": 2, "lat": 46.5, "lon": -88.0, "tags": {}}]
    """.trimIndent()

    private fun source() = DeFlockCameraSource(
        http = OkHttpClient(),
        cacheDir = cacheDir,
        indexUrl = server.url("/regions/index.json").toString(),
        nowEpochSeconds = { now },
    )

    private fun serve(indexExpiration: Long, failTiles: Boolean = false, failIndex: Boolean = false) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/regions/index.json") ->
                        if (failIndex) MockResponse().setResponseCode(500)
                        else MockResponse().setBody(indexJson(indexExpiration))
                    path.startsWith("/regions/40/-100.json") ->
                        if (failTiles) MockResponse().setResponseCode(500)
                        else MockResponse().setBody(tileJson)
                    path.startsWith("/regions/") -> MockResponse().setBody("[]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `fetches filters and reports network freshness`() = runTest {
        serve(indexExpiration = now + 3600)
        val result = source().camerasIn(bbox)
        // Record 2 is outside the bbox and must be filtered out.
        assertEquals(listOf(1L), result.cameras.map { it.id })
        assertEquals(Freshness.NETWORK, result.freshness)
    }

    @Test
    fun `second query is served from disk cache`() = runTest {
        serve(indexExpiration = now + 3600)
        source().camerasIn(bbox)
        val before = server.requestCount
        val result = source().camerasIn(bbox)
        assertEquals(before, server.requestCount, "no further HTTP requests expected")
        assertEquals(Freshness.CACHE, result.freshness)
        assertEquals(listOf(1L), result.cameras.map { it.id })
    }

    @Test
    fun `expired index is refetched`() = runTest {
        serve(indexExpiration = now + 100)
        source().camerasIn(bbox)
        now += 200 // past expiration_utc
        serve(indexExpiration = now + 3600)
        val before = server.requestCount
        source().camerasIn(bbox)
        assertTrue(server.requestCount > before, "expiration must force a refetch")
    }

    @Test
    fun `tile fetch failure falls back to stale cache`() = runTest {
        serve(indexExpiration = now + 100)
        source().camerasIn(bbox) // warm cache, v=77
        now += 200
        // New index (new v) but tiles now failing: stale tile cache must serve.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/regions/index.json") ->
                        MockResponse().setBody(indexJson(now + 3600, v = "88"))
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        val result = source().camerasIn(bbox)
        assertEquals(listOf(1L), result.cameras.map { it.id })
        assertEquals(Freshness.STALE_CACHE, result.freshness)
    }

    @Test
    fun `total failure falls back to bundled snapshot`() = runTest {
        serve(indexExpiration = 0, failIndex = true)
        // Fresh cache dir, dead network: only the bundled snapshot remains.
        val result = source().camerasIn(bbox)
        assertEquals(Freshness.BUNDLED, result.freshness)
        // The real snapshot ships in resources; this bbox (Marinette WI area,
        // widened) contains cameras in the recorded data.
        val wide = BoundingBox(44.4, -88.3, 45.3, -87.4)
        val wideResult = source().camerasIn(wide)
        assertTrue(wideResult.cameras.isNotEmpty(), "bundled snapshot should cover NE Wisconsin")
    }

    @Test
    fun `tile concurrency never exceeds five`() = runTest {
        val active = java.util.concurrent.atomic.AtomicInteger(0)
        val peak = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/regions/index.json")) {
                    val regions = buildList {
                        for (lat in intArrayOf(20, 40)) for (lon in -160..-20 step 20) add("\"$lat/$lon\"")
                    }.joinToString(",")
                    return MockResponse().setBody(
                        """{"expiration_utc": ${now + 3600}, "regions": [$regions],
                            "tile_url": "${tileUrlTemplate("9")}",
                            "tile_size_degrees": 20}"""
                    )
                }
                val current = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, current) }
                Thread.sleep(50)
                active.decrementAndGet()
                return MockResponse().setBody("[]")
            }
        }
        source().camerasIn(BoundingBox(21.0, -159.0, 59.0, -21.0)) // 16 tiles
        assertTrue(peak.get() <= 5, "peak concurrency was ${peak.get()}")
    }
}
