package app.shunt.solver.camera

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.BoundingBox
import app.shunt.solver.geo.floorTo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement

/**
 * Shape of https://cdn.deflock.me/regions/index.json, verified against the
 * live endpoint 2026-07-19 (fixture: src/test/resources/fixtures/deflock/index.json).
 */
@Serializable
data class DeFlockIndex(
    @SerialName("expiration_utc") val expirationUtc: Long,
    @SerialName("tile_url") val tileUrl: String,
    @SerialName("tile_size_degrees") val tileSizeDegrees: Int,
    val regions: List<String>,
) {
    /** The cache-busting `?v=` value baked into [tileUrl], if present. */
    val version: String? get() = tileUrl.substringAfter("?v=", "").ifEmpty { null }

    fun urlForTile(key: TileKey): String =
        tileUrl.replace("{lat}", key.lat.toString()).replace("{lon}", key.lon.toString())
}

/** A "{lat}/{lon}" tile key — the south-west corner floored to the tile grid. */
data class TileKey(val lat: Int, val lon: Int) {
    override fun toString(): String = "$lat/$lon"

    /** Filesystem-safe form. */
    val fileName: String get() = "${lat}_${lon}"

    companion object {
        fun parse(s: String): TileKey {
            val (lat, lon) = s.split("/")
            return TileKey(lat.toInt(), lon.toInt())
        }

        /** All grid keys whose tile intersects [bbox]. */
        fun covering(bbox: BoundingBox, tileSizeDegrees: Int): List<TileKey> {
            val keys = mutableListOf<TileKey>()
            var lat = floorTo(bbox.minLat, tileSizeDegrees)
            while (lat <= floorTo(bbox.maxLat, tileSizeDegrees)) {
                var lon = floorTo(bbox.minLon, tileSizeDegrees)
                while (lon <= floorTo(bbox.maxLon, tileSizeDegrees)) {
                    keys += TileKey(lat, lon)
                    lon += tileSizeDegrees
                }
                lat += tileSizeDegrees
            }
            return keys
        }
    }
}

/**
 * One record in a tile. Tiles are JSON arrays of these; shape verified live
 * 2026-07-19. Tag values are almost always strings but we tolerate numbers.
 */
@Serializable
data class DeFlockRecord(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val tags: Map<String, JsonElement> = emptyMap(),
) {
    fun toCamera(): Camera = Camera(
        id = id,
        location = GeoPoint(lat, lon),
        tags = tags.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() },
    )
}

internal val deflockJson = Json { ignoreUnknownKeys = true }

fun parseDeFlockIndex(json: String): DeFlockIndex = deflockJson.decodeFromString(json)

fun parseDeFlockTile(json: String): List<DeFlockRecord> = deflockJson.decodeFromString(json)
