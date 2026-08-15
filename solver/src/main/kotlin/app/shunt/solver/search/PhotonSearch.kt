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
    /**
     * Places matching [query], near [at] first.
     *
     * **Asked twice: once inside a box around the driver, then — only if that
     * finds nothing — worldwide.** `location_bias_scale` alone is a preference,
     * and measured against real queries it loses badly to raw OSM "importance":
     *
     * | typed | biased only | inside the box |
     * |---|---|---|
     * | "Concordia Public Library" | a library in Hong Kong | the library in Concordia |
     * | "brown grand theatre" | a theatre in Warsaw | the Brown Grand Opera House |
     * | "Main Street Concordia" | a school in Tomball, Texas | streets in Concordia |
     *
     * A bias cannot fix that, because the local answer is not in the response to
     * re-rank — `rankByProximity` can only sort what it was given, and what it
     * was given was Hong Kong. The box is what puts the right result in the list
     * at all, and it is the single largest improvement search has had.
     *
     * The widen matters just as much in the other direction: bounded-only would
     * make a deliberately distant destination unfindable, which is the mistake
     * [NominatimSearch] documents avoiding for the same reason.
     */
    suspend fun suggest(query: String, at: GeoPoint, limit: Int = 10): List<Suggestion> {
        if (query.isBlank()) return emptyList()
        // Null means the request itself failed, which is *not* a reason to
        // widen: a 429 is the service asking us to slow down, and answering it
        // with a second request is the opposite. Only a search that genuinely
        // came back empty is worth asking again, wider.
        val near = search(query, at, limit, boxDegrees = NEAR_BOX_DEGREES) ?: return emptyList()
        if (near.isNotEmpty()) return near
        return search(query, at, limit, boxDegrees = null).orEmpty()
    }

    private suspend fun search(
        query: String,
        at: GeoPoint,
        limit: Int,
        boxDegrees: Double?,
    ): List<Suggestion>? {
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
            .apply {
                boxDegrees?.let {
                    // minLon,minLat,maxLon,maxLat — a filter, not a preference.
                    addQueryParameter(
                        "bbox",
                        "${at.lon - it},${at.lat - it},${at.lon + it},${at.lat + it}",
                    )
                }
            }
            .build()
        val body = fetch(url) ?: return null
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
        val body = fetch(url) ?: return emptyList()
        return parse(body).sortedBy { haversineMeters(at, it.location) }
    }

    /**
     * The response body, or null when the host asked us to slow down.
     *
     * **Being throttled is not a broken search**, and treating it as one is what
     * made typing look like a connectivity problem: the geocoder answers a
     * burst of keystrokes with a 429, Shunt threw, and the UI announced
     * "Couldn't reach search — check your connection". `NominatimSearch` has
     * always handled it this way; Photon simply never had the same care applied.
     *
     * Returning nothing lets [PlaceSearch] try the other geocoder, which is
     * exactly what a caller wants when this one is temporarily unwilling.
     */
    private suspend fun fetch(url: okhttp3.HttpUrl): String? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Shunt").build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code in THROTTLED_CODES) return@use null
            if (!resp.isSuccessful) throw IOException("Photon HTTP ${resp.code}: ${text.take(200)}")
            text
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Rate-limited / over capacity: no answer this time, not a failure. */
        val THROTTLED_CODES = setOf(429, 503)

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

        /**
         * Half-width of the box searched first, in degrees — about 165 km.
         *
         * Wide enough to hold everywhere a driver plausibly means when they type
         * a name without a town, and narrow enough that the local answer is not
         * competing with a famous namesake on another continent.
         */
        const val NEAR_BOX_DEGREES = 1.5

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
