package app.shunt.solver.brouter

import app.shunt.solver.geo.BoundingBox
import java.io.File
import java.io.IOException
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A BRouter segment tile: a 5°×5° cell named by its south-west corner, e.g.
 * `W100_N35.rd5` covers longitude −100..−95, latitude 35..40.
 */
data class TileId(val lon5: Int, val lat5: Int) {
    val fileName: String
        get() {
            val lon = if (lon5 < 0) "W${-lon5}" else "E$lon5"
            val lat = if (lat5 < 0) "S${-lat5}" else "N$lat5"
            return "${lon}_$lat.rd5"
        }

    companion object {
        fun containing(lat: Double, lon: Double): TileId =
            TileId((floor(lon / 5.0) * 5).toInt(), (floor(lat / 5.0) * 5).toInt())
    }
}

/**
 * Hybrid provider of BRouter's offline routing tiles: lazily downloads the
 * 5°×5° `.rd5` cells a trip needs from the BRouter CDN and caches them on disk
 * (never auto-evicted — a few tens of MB), with [pinHome] to pre-fetch the home
 * region for guaranteed-offline routing. BRouter reads the tiles straight from
 * [segmentDir].
 *
 * Because we do a *full replace* of HERE routing, a trip whose tile isn't
 * present yet surfaces as [missingTiles] so the UI can prompt a download rather
 * than silently falling back.
 */
class BrouterTileSource(
    private val http: OkHttpClient,
    val segmentDir: File,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    /** Every tile that covers [bbox] (usually one; more only near a 5° seam). */
    fun requiredTiles(bbox: BoundingBox): List<TileId> {
        val tiles = LinkedHashSet<TileId>()
        var lat = floor(bbox.minLat / 5.0) * 5
        while (lat <= bbox.maxLat) {
            var lon = floor(bbox.minLon / 5.0) * 5
            while (lon <= bbox.maxLon) {
                tiles += TileId(lon.toInt(), lat.toInt())
                lon += 5
            }
            lat += 5
        }
        // A zero-area bbox (origin == destination) still needs its own tile.
        if (tiles.isEmpty()) tiles += TileId.containing(bbox.minLat, bbox.minLon)
        return tiles.toList()
    }

    fun isPresent(tile: TileId): Boolean =
        File(segmentDir, tile.fileName).let { it.exists() && it.length() > 0 }

    /** Tiles covering [bbox] that aren't on disk yet. Empty ⇒ ready to route. */
    fun missingTiles(bbox: BoundingBox): List<TileId> =
        requiredTiles(bbox).filterNot { isPresent(it) }

    /**
     * Download [tile] to [segmentDir], atomically (via a `.part` file so a
     * half-written tile is never seen as present). [onProgress] receives
     * (bytesSoFar, totalBytes); totalBytes is −1 when the server omits a
     * Content-Length. Returns true on success; present tiles short-circuit true.
     */
    suspend fun download(tile: TileId, onProgress: (Long, Long) -> Unit = { _, _ -> }): Boolean =
        withContext(Dispatchers.IO) {
            if (isPresent(tile)) return@withContext true
            segmentDir.mkdirs()
            val dest = File(segmentDir, tile.fileName)
            val part = File(segmentDir, "${tile.fileName}.part")
            runCatching {
                http.newCall(Request.Builder().url("$baseUrl${tile.fileName}").build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${tile.fileName}")
                    val body = resp.body ?: throw IOException("empty body for ${tile.fileName}")
                    val total = body.contentLength()
                    var soFar = 0L
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        body.byteStream().use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                soFar += n
                                onProgress(soFar, total)
                            }
                        }
                    }
                }
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true); part.delete()
                }
                true
            }.getOrElse { part.delete(); false }
        }

    /** Pre-fetch the tile covering [home] so routing there works fully offline. */
    suspend fun pinHome(homeLat: Double, homeLon: Double): Boolean =
        download(TileId.containing(homeLat, homeLon))

    /**
     * Mark the tiles covering [bbox] as used now (bumps their file mtime), so
     * tiles you actually route through survive eviction. Call after a
     * successful route.
     */
    fun markUsed(bbox: BoundingBox, now: Long = System.currentTimeMillis()) {
        requiredTiles(bbox).forEach { tile ->
            File(segmentDir, tile.fileName).takeIf { it.exists() }?.setLastModified(now)
        }
    }

    /**
     * Delete cached tiles not used since [cutoffMillis] (mtime older than the
     * cutoff). Returns how many were removed. Keeps storage from growing without
     * bound while leaving the areas you actually drive intact.
     */
    fun pruneUnusedSince(cutoffMillis: Long): Int {
        val files = segmentDir.listFiles { f -> f.name.endsWith(".rd5") } ?: return 0
        var removed = 0
        for (f in files) if (f.lastModified() < cutoffMillis && f.delete()) removed++
        return removed
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://brouter.de/brouter/segments4/"
    }
}
