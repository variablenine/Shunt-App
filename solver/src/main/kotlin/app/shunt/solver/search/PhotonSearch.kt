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

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * How near a result must be to count as "local" and get promoted above
         * far-away namesakes (~75 mi — a day-trip radius). Photon ranks by OSM
         * "importance," so a famous distant landmark outranks a nearby place of
         * the same name; a driving app wants the reachable one first.
         */
        const val LOCAL_RADIUS_METERS = 120_000.0

        /**
         * How hard to weight nearness over OSM "importance", 0..1. Nudged up
         * from Photon's 0.2 default because this is a driving app: the place
         * you can drive to beats the famous one you can't.
         */
        const val LOCATION_BIAS_SCALE = "0.6"

        /**
         * Results this much closer than another are ordered by distance;
         * within a bucket, Photon's own relevance order is kept. Sorting
         * strictly by distance would put a random hut ahead of the town you
         * actually typed.
         */
        const val PROXIMITY_BUCKET_METERS = 25_000.0

        /**
         * Order results the way someone planning a drive wants them: anything
         * within [LOCAL_RADIUS_METERS] first, then roughly nearest-first, with
         * Photon's relevance breaking ties inside each distance band.
         *
         * Both keys matter. The tier alone left a pile of far-flung namesakes
         * in importance order, so a search could fill up with places nowhere
         * near the driver; distance alone would throw away relevance entirely.
         */
        fun rankByProximity(results: List<Suggestion>, at: GeoPoint): List<Suggestion> =
            results.withIndex()
                .sortedWith(
                    compareBy<IndexedValue<Suggestion>> {
                        if (haversineMeters(at, it.value.location) <= LOCAL_RADIUS_METERS) 0 else 1
                    }
                        .thenBy { (haversineMeters(at, it.value.location) / PROXIMITY_BUCKET_METERS).toInt() }
                        .thenBy { it.index }, // stable: Photon's own relevance order
                )
                .map { it.value }

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
