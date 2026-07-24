package app.shunt.solver.camera

import java.util.zip.GZIPInputStream

/**
 * Offline fallback: a snapshot of the full DeFlock dataset bundled as
 * resources (gzipped tiles + the index that produced them). Used only when
 * both the network and the disk cache have failed. Refresh with
 * tools/update-snapshot.sh; the data is ODbL — see LICENSE-DATA.md.
 */
class BundledSnapshot(private val resourceRoot: String = "/deflock-snapshot") {

    fun index(): DeFlockIndex? = resource("$resourceRoot/index.json")
        ?.readBytes()?.decodeToString()?.let { parseDeFlockIndex(it) }

    fun tile(key: TileKey): List<DeFlockRecord>? =
        resource("$resourceRoot/tiles/${key.fileName}.json.gz")?.let { stream ->
            GZIPInputStream(stream).use { parseDeFlockTile(it.readBytes().decodeToString()) }
        }

    private fun resource(path: String) = javaClass.getResourceAsStream(path)
}
