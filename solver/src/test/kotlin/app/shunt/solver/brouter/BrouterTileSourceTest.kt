package app.shunt.solver.brouter

import app.shunt.solver.geo.BoundingBox
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class BrouterTileSourceTest {

    private val server = MockWebServer()

    @AfterTest fun tearDown() = server.shutdown()

    @Test
    fun `tile names follow BRouter's SW-corner convention`() {
        // Western hemisphere, northern latitude (neutral test coordinates).
        assertEquals("W100_N35.rd5", TileId.containing(39.0, -98.0).fileName)
        // Eastern hemisphere, southern latitude.
        assertEquals("E5_S35.rd5", TileId.containing(-31.0, 6.0).fileName)
        // On a 5-degree boundary stays in the cell it opens.
        assertEquals("E5_N45.rd5", TileId.containing(45.0, 5.0).fileName)
    }

    @Test
    fun `a small bbox needs exactly one tile`() {
        val src = source()
        val bbox = BoundingBox(38.98, -98.10, 39.02, -98.00)
        assertEquals(listOf(TileId(-100, 35)), src.requiredTiles(bbox))
    }

    @Test
    fun `missingTiles reflects what's on disk`() = runTest {
        val src = source()
        val bbox = BoundingBox(38.98, -98.10, 39.02, -98.00)
        assertEquals(1, src.missingTiles(bbox).size)

        server.enqueue(MockResponse().setBody("rd5-bytes"))
        assertTrue(src.download(TileId(-100, 35)))
        assertTrue(src.missingTiles(bbox).isEmpty())
        assertTrue(src.isPresent(TileId(-100, 35)))
    }

    @Test
    fun `a failed download leaves no partial tile present`() = runTest {
        val src = source()
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(src.download(TileId(-100, 35)))
        assertFalse(src.isPresent(TileId(-100, 35)))
    }

    @Test
    fun `pruneUnusedSince removes stale tiles but keeps fresh ones`() = runTest {
        val src = source()
        repeat(2) { server.enqueue(MockResponse().setBody("rd5")) }
        assertTrue(src.download(TileId(-100, 35)))
        assertTrue(src.download(TileId(-105, 35)))
        val cutoff = System.currentTimeMillis() - 1_000
        File(src.segmentDir, TileId(-100, 35).fileName).setLastModified(cutoff - 5_000) // stale
        File(src.segmentDir, TileId(-105, 35).fileName).setLastModified(cutoff + 500)   // fresh
        assertEquals(1, src.pruneUnusedSince(cutoff))
        assertFalse(src.isPresent(TileId(-100, 35)))
        assertTrue(src.isPresent(TileId(-105, 35)))
    }

    @Test
    fun `markUsed protects a tile from pruning`() = runTest {
        val src = source()
        server.enqueue(MockResponse().setBody("rd5"))
        assertTrue(src.download(TileId(-100, 35)))
        File(src.segmentDir, TileId(-100, 35).fileName).setLastModified(1_000L) // ancient
        src.markUsed(BoundingBox(38.98, -98.10, 39.02, -98.00))
        assertEquals(0, src.pruneUnusedSince(System.currentTimeMillis() - 1_000))
        assertTrue(src.isPresent(TileId(-100, 35)))
    }

    private fun source(): BrouterTileSource {
        val dir = Files.createTempDirectory("brouter-tiles").toFile().apply { deleteOnExit() }
        return BrouterTileSource(OkHttpClient(), dir, baseUrl = server.url("/segments/").toString())
    }
}
