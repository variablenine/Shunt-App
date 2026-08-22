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

    private val bbox = BoundingBox(32.9, -97.1, 33.1, -96.9) // inside tile 20/-100

    // Note: braces must stay literal — HttpUrl would percent-encode them.
    private fun tileUrlTemplate(v: String) = "${server.url("/regions/")}{lat}/{lon}.json?v=$v"

    private fun indexJson(expiration: Long, v: String = "77") = """
        {"expiration_utc": $expiration,
         "regions": ["20/-100", "20/-80"],
         "tile_url": "${tileUrlTemplate(v)}",
         "tile_size_degrees": 20}
    """.trimIndent()

    private val tileJson = """
        [{"id": 1, "lat": 33.0, "lon": -97.0, "tags": {"direction": "90"}},
         {"id": 2, "lat": 34.5, "lon": -97.0, "tags": {}}]
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
                    path.startsWith("/regions/20/-100.json") ->
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
        // The real snapshot ships in resources; this widened bbox covers a
        // dense metro area that has cameras in the recorded data.
        val wide = BoundingBox(32.4, -97.5, 33.5, -96.4)
        val wideResult = source().camerasIn(wide)
        assertTrue(wideResult.cameras.isNotEmpty(), "bundled snapshot should cover a dense metro")
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
    @Test
    fun `a tile nothing can supply is reported missing, not as no cameras`() = runTest {
        // **The most dangerous shape a bug can take in this app.** A tile with
        // no network, no cache and no bundled copy used to come back as an empty
        // list, which is exactly what a tile with genuinely no cameras returns.
        // A route planned through that hole is labelled camera-free having never
        // been asked to avoid anything — the one failure CLAUDE.md §5 names.
        //
        // The snapshot here is empty, so nothing can supply the tile.
        val noSnapshot = DeFlockCameraSource(
            http = OkHttpClient(),
            cacheDir = cacheDir,
            indexUrl = server.url("/regions/index.json").toString(),
            nowEpochSeconds = { now },
            snapshot = BundledSnapshot("/no-such-snapshot"),
        )
        serve(indexExpiration = now + 3600, failTiles = true)

        val result = noSnapshot.camerasIn(bbox)

        assertTrue(result.cameras.isEmpty(), "nothing could be loaded, so there is nothing to report")
        assertTrue(
            result.missingTiles > 0,
            "an unloadable tile must be distinguishable from an empty one",
        )
    }

    @Test
    fun `a tile that loads and is genuinely empty is not reported missing`() = runTest {
        // The other half: the distinction is only worth anything if an honest
        // empty answer stays honest. Refusing to plan whenever a region has no
        // cameras would make the app useless in most of the country.
        serve(indexExpiration = now + 3600)
        val result = source().camerasIn(BoundingBox(32.9, -79.1, 33.1, -78.9)) // tile 20/-80, served empty

        assertEquals(0, result.missingTiles, "an empty tile that loaded is not a hole")
    }

}
