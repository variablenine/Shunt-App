package app.shunt.solver.search

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.haversineMeters
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
    suspend fun suggest(query: String, at: GeoPoint, limit: Int = 10): List<Suggestion> {
        if (query.isBlank()) return emptyList()
        val url = "$baseUrl/api".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("lat", at.lat.toString())
            .addQueryParameter("lon", at.lon.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("lang", "en") // English labels
            // Photon ranks by OSM "importance" by default, which fills a
            // driving app's results with famous places hundreds of miles away.
            // Higher bias scale = weight the lat/lon above raw importance.
            // (Note the direction: a *low* value biases less, not more.)
            .addQueryParameter("location_bias_scale", LOCATION_BIAS_SCALE)
            .build()
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Shunt").build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Photon HTTP ${resp.code}: ${text.take(200)}")
                text
            }
        }
        return rankByProximity(parse(body), at)
    }

    /**
     * Places of a given *kind* near [at], nearest first — the answer to "coffee"
     * rather than to "Coffee County".
     *
     * Photon's reverse endpoint takes the same `osm_tag` filters as the search
     * endpoint but needs no query text, so it can be asked for cafes near a
     * point directly. That is the whole trick: the search endpoint always
     * matches names, and no amount of ranking turns a name search into a
     * category one. See [PlaceCategories].
     *
     * Sorted here rather than trusted from the response, because "nearest" is
     * the entire value of the answer to someone who wants coffee now.
     */
    suspend fun nearby(
        tags: List<String>,
        at: GeoPoint,
        radiusKm: Double = NEARBY_RADIUS_KM,
        limit: Int = 10,
    ): List<Suggestion> {
        if (tags.isEmpty()) return emptyList()
        val url = "$baseUrl/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", at.lat.toString())
            .addQueryParameter("lon", at.lon.toString())
            .addQueryParameter("radius", radiusKm.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("lang", "en")
            .apply { tags.forEach { addQueryParameter("osm_tag", it) } }
            .build()
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Shunt").build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Photon HTTP ${resp.code}: ${text.take(200)}")
                text
            }
        }
        return parse(body).sortedBy { haversineMeters(at, it.location) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * How hard to weight nearness over OSM "importance", 0..1. Nudged up
         * from Photon's 0.2 default because this is a driving app: the place
         * you can drive to beats the famous one you can't.
         */
        const val LOCATION_BIAS_SCALE = "0.6"

        /**
         * How far out to look for a *kind* of place (~30 mi).
         *
         * Wide enough to find a charger or a rest area from open road, tight
         * enough that "coffee" cannot answer with somewhere an hour away — which
         * would be a worse answer than none, since the whole point is that the
         * driver wants one now.
         */
        const val NEARBY_RADIUS_KM = 50.0

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

        /** "Central Library, Springfield, IL" or "1717 South Main Street, Lindsborg, KS". */
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
