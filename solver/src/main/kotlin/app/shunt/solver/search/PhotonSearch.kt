package app.shunt.solver.search

import app.shunt.core.GeoPoint
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Destination search via [Photon](https://photon.komoot.io) — an open,
 * OpenStreetMap-based geocoder that needs **no API key and no account** (unlike
 * HERE, which requires entering card details to get a key). Results are biased
 * toward [at]. The public instance is the default; [baseUrl] can point at a
 * self-hosted Photon for full query privacy.
 */
class PhotonSearch(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://photon.komoot.io",
) {
    suspend fun suggest(query: String, at: GeoPoint, limit: Int = 6): List<Suggestion> {
        if (query.isBlank()) return emptyList()
        val url = "$baseUrl/api".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("lat", at.lat.toString())
            .addQueryParameter("lon", at.lon.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Shunt").build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Photon HTTP ${resp.code}: ${text.take(200)}")
                text
            }
        }
        return parse(body)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String): List<Suggestion> =
            json.decodeFromString<FeatureCollection>(body).features.mapNotNull { feature ->
                val coords = feature.geometry?.coordinates ?: return@mapNotNull null
                if (coords.size < 2) return@mapNotNull null
                val p = feature.properties ?: Properties()
                Suggestion(
                    title = title(p),
                    location = GeoPoint(lat = coords[1], lon = coords[0]), // GeoJSON is [lon, lat]
                    resultType = p.osmValue ?: p.type ?: "place",
                )
            }

        /** "Lambeau Field, Green Bay, WI" or "1717 North Shawano Street, New London, WI". */
        private fun title(p: Properties): String {
            val label = p.name?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(p.housenumber, p.street).joinToString(" ").ifBlank { null }
            val place = listOfNotNull(p.city ?: p.county, p.state).distinct().joinToString(", ")
            return when {
                label != null && place.isNotBlank() -> "$label, $place"
                label != null -> label
                place.isNotBlank() -> place
                else -> "Unknown place"
            }
        }
    }

    @Serializable
    internal data class FeatureCollection(val features: List<Feature> = emptyList())

    @Serializable
    internal data class Feature(val geometry: Geometry? = null, val properties: Properties? = null)

    @Serializable
    internal data class Geometry(val coordinates: List<Double> = emptyList())

    @Serializable
    internal data class Properties(
        val name: String? = null,
        val street: String? = null,
        val housenumber: String? = null,
        val city: String? = null,
        val county: String? = null,
        val state: String? = null,
        @SerialName("osm_value") val osmValue: String? = null,
        val type: String? = null,
    )
}
