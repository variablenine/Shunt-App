package app.shunt.solver.camera

import app.shunt.solver.geo.BoundingBox
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where a piece of camera data came from, best (fresh) to worst (bundled). */
enum class Freshness { NETWORK, CACHE, STALE_CACHE, BUNDLED }

data class CameraResult(
    val cameras: List<Camera>,
    /** Worst freshness across the tiles that served this query. */
    val freshness: Freshness,
    /**
     * How many tiles covering the query could not be loaded **at all** — no
     * network, no cache, and not in the bundled snapshot.
     *
     * **An empty tile and an unloadable one used to be the same value**, and on
     * this app that difference is the whole game. `loadTile` ended
     * `snapshot.tile(key) ?: emptyList()`, so a tile nothing could supply came
     * back as *no cameras here* — and a route planned through it is labelled
     * camera-free while never having been asked to avoid anything. That is the
     * one failure CLAUDE.md §5 names outright: a route must never be labelled
     * against cameras the router was not given a chance to avoid.
     *
     * Non-zero means the answer has a hole in it, and nothing downstream may
     * treat it as a camera set.
     */
    val missingTiles: Int = 0,
)

/**
 * ALPR locations from the DeFlock CDN, with a disk cache honoring the
 * index's expiration_utc, at most [MAX_CONCURRENT_FETCHES] tile fetches in
 * flight, and the bundled snapshot as a last-resort offline fallback.
 *
 * Cache layout under [cacheDir]:
 *   index.json                  — last good index
 *   tiles/{lat}_{lon}_{v}.json  — tile content, keyed by the index's ?v= version
 */
class DeFlockCameraSource(
    private val http: OkHttpClient,
    private val cacheDir: File,
    private val indexUrl: String = DEFAULT_INDEX_URL,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val snapshot: BundledSnapshot = BundledSnapshot(),
) {
    suspend fun camerasIn(bbox: BoundingBox): CameraResult {
        // No index at all: every tile the query wanted is missing, and saying
        // "no cameras" would be the same lie one level up.
        val (index, indexFreshness) = loadIndex()
            ?: return CameraResult(emptyList(), Freshness.BUNDLED, missingTiles = 1)
        val regionSet = index.regions.toSet()
        val keys = TileKey.covering(bbox, index.tileSizeDegrees)
            .filter { it.toString() in regionSet }

        val semaphore = Semaphore(MAX_CONCURRENT_FETCHES)
        val tiles = coroutineScope {
            keys.map { key ->
                async { semaphore.withPermit { loadTile(index, key) } }
            }.awaitAll()
        }

        val loaded = tiles.filterNotNull()
        val cameras = loaded.flatMap { it.first }
            .map { it.toCamera() }
            .filter { bbox.contains(it.location) }
        val worst = (loaded.map { it.second } + indexFreshness).maxBy { it.ordinal }
        return CameraResult(cameras, worst, missingTiles = tiles.count { it == null })
    }

    private suspend fun loadIndex(): Pair<DeFlockIndex, Freshness>? {
        val cacheFile = File(cacheDir, "index.json")
        val cached = runCatching { parseDeFlockIndex(cacheFile.readText()) }.getOrNull()
        if (cached != null && nowEpochSeconds() < cached.expirationUtc) {
            return cached to Freshness.CACHE
        }
        val fetched = runCatching { fetchText(indexUrl) }.getOrNull()
        if (fetched != null) {
            val parsed = runCatching { parseDeFlockIndex(fetched) }.getOrNull()
            if (parsed != null) {
                cacheDir.mkdirs()
                cacheFile.writeText(fetched)
                return parsed to Freshness.NETWORK
            }
        }
        if (cached != null) return cached to Freshness.STALE_CACHE
        return snapshot.index()?.let { it to Freshness.BUNDLED }
    }

    /**
     * One tile's records, or **null when nothing could supply it** — which is a
     * different answer from an empty list and must stay that way. See
     * [CameraResult.missingTiles].
     */
    private suspend fun loadTile(index: DeFlockIndex, key: TileKey): Pair<List<DeFlockRecord>, Freshness>? {
        val version = index.version ?: "none"
        val tilesDir = File(cacheDir, "tiles")
        val cacheFile = File(tilesDir, "${key.fileName}_$version.json")

        runCatching { parseDeFlockTile(cacheFile.readText()) }.getOrNull()
            ?.let { return it to Freshness.CACHE }

        val fetched = runCatching { fetchText(index.urlForTile(key)) }.getOrNull()
        if (fetched != null) {
            val parsed = runCatching { parseDeFlockTile(fetched) }.getOrNull()
            if (parsed != null) {
                tilesDir.mkdirs()
                // A new version supersedes older cached copies of this tile.
                tilesDir.listFiles { f -> f.name.startsWith("${key.fileName}_") }?.forEach { it.delete() }
                cacheFile.writeText(fetched)
                return parsed to Freshness.NETWORK
            }
        }

        val stale = tilesDir.listFiles { f -> f.name.startsWith("${key.fileName}_") }
            ?.mapNotNull { f -> runCatching { parseDeFlockTile(f.readText()) }.getOrNull() }
            ?.firstOrNull()
        if (stale != null) return stale to Freshness.STALE_CACHE

        // `snapshot.tile` returns null when the snapshot does not carry this
        // tile at all, which is not the same as carrying it and finding it
        // empty. Substituting an empty list here is what made a hole in the data
        // look like clear road.
        return snapshot.tile(key)?.let { it to Freshness.BUNDLED }
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            resp.body?.string() ?: throw IOException("empty body for $url")
        }
    }

    companion object {
        const val DEFAULT_INDEX_URL = "https://cdn.deflock.me/regions/index.json"
        const val MAX_CONCURRENT_FETCHES = 5
    }
}
