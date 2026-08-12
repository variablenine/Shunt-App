package app.shunt.solver.search

import app.shunt.core.GeoPoint
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Destination search via [Nominatim](https://nominatim.openstreetmap.org) — the
 * official OpenStreetMap geocoder. Keyless, like Photon, but backed by a much
 * fresher and more complete index: places and house numbers that exist in OSM
 * are routinely missing from Photon's public index while Nominatim resolves
 * them exactly.
 *
 * **This is a fallback, not the typeahead.** Nominatim's usage policy caps the
 * public instance at one request per second and asks that it not be used for
 * autocomplete, so [PlaceSearch] only calls it when Photon returns nothing —
 * one request per settled query. [minIntervalMillis] enforces the spacing, and
 * a descriptive User-Agent is required by the same policy.
 */
class NominatimSearch(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://nominatim.openstreetmap.org",
    private val userAgent: String = USER_AGENT,
    private val minIntervalMillis: Long = 1_000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val throttle = Mutex()
    private var lastCallMillis = 0L

    /**
     * Search near the user first, and only widen to the world if that finds
     * nothing.
     *
     * **A `viewbox` alone does almost nothing here.** It expresses a preference,
     * and for anything with many namesakes the preference loses: measured
     * against the public instance from a point in Kansas, "starbucks" came back
     * as cafes 889, 920 and 1,869 km away — one of them in Japan — while the
     * ones a few blocks from the search point never appeared at all. No amount
     * of re-ordering rescues results that were never in the response.
     *
     * `bounded=1` fixes that completely (0 km, 5 km, 5 km on the same query) and
     * would be the obvious answer, except that it breaks the other half of what
     * this fallback is for. Same measurement: "Fontano's Subs Chicago" and
     * "Willis Tower", typed from Kansas, return **nothing** bounded and resolve
     * correctly unbounded at ~950 km. Someone planning a drive is often naming
     * somewhere far away on purpose — that is the whole point of the app.
     *
     * So: bounded first, and widen only on an empty result. The near search
     * answers "a real place down the road that Photon didn't have", the wide one
     * answers "the place in the city I'm driving to", and the second request is
     * only spent when the first came back empty.
     */
    suspend fun suggest(query: String, at: GeoPoint, limit: Int = 6): List<Suggestion> {
        if (query.isBlank()) return emptyList()
        val near = search(query, at, limit, bounded = true)
        if (near.isNotEmpty()) return rankByProximity(near, at)
        return rankByProximity(search(query, at, limit, bounded = false), at)
    }

    private suspend fun search(
        query: String,
        at: GeoPoint,
        limit: Int,
        bounded: Boolean,
    ): List<Suggestion> {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("addressdetails", "1")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("viewbox", viewboxAround(at))
            .apply { if (bounded) addQueryParameter("bounded", "1") }
            .build()

        awaitTurn()
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
                .execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    // Being throttled is not a broken search. Throwing here made
                    // the UI announce "search couldn't be reached" — while the
                    // typeahead's own results were sitting right there — so a
                    // burst of typing looked like the whole feature had failed.
                    if (resp.code in THROTTLED_CODES) return@use null
                    if (!resp.isSuccessful) throw IOException("Nominatim HTTP ${resp.code}: ${text.take(200)}")
                    text
                }
        } ?: return emptyList()
        return parse(body)
    }

    /**
     * The place at [point], for naming a spot the user picked straight off the
     * map. Returns null if nothing is known there (open country, mid-lake) or
     * the lookup fails — the caller should fall back to plain coordinates
     * rather than block on it.
     */
    suspend fun reverse(point: GeoPoint): Suggestion? {
        val url = "$baseUrl/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", point.lat.toString())
            .addQueryParameter("lon", point.lon.toString())
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("addressdetails", "1")
            // Street-level: a house number is rarely what was meant by a press.
            .addQueryParameter("zoom", "17")
            .build()

        awaitTurn()
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
                .execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IOException("Nominatim HTTP ${resp.code}: ${text.take(200)}")
                    text
                }
        }
        // /reverse returns a single object, not an array.
        val place = runCatching { json.decodeFromString<Place>(body) }.getOrNull() ?: return null
        val label = title(place).takeIf { it != "Unknown place" } ?: return null
        // Keep the pressed point: the named place's own centre can be a street
        // or suburb centroid some way from where the user actually pointed.
        return Suggestion(label, point, place.type ?: "place")
    }

    /** Hold each caller until the public instance's 1 req/s policy is satisfied. */
    private suspend fun awaitTurn() {
        val waitFor = throttle.withLock {
            val since = nowMillis() - lastCallMillis
            val wait = (minIntervalMillis - since).coerceAtLeast(0)
            lastCallMillis = nowMillis() + wait
            wait
        }
        if (waitFor > 0) delay(waitFor)
    }

    companion object {
        /** Nominatim's policy requires an identifying User-Agent. */
        const val USER_AGENT = "Shunt/1.0 (+https://github.com/variablenine/Shunt-App)"

        /** Rate-limited / over capacity: no answer this time, not a failure. */
        val THROTTLED_CODES = setOf(429, 503)

        /**
         * Half-width of the "near me" box, in degrees (~165 km of latitude).
         *
         * Comfortably wider than [LOCAL_RADIUS_METERS], so anything the ranking
         * would call local is inside it, and still small enough that a bounded
         * search means something. It is a box rather than a circle only because
         * that is the shape Nominatim's API takes.
         */
        const val NEAR_BOX_DEGREES = 1.5

        private fun viewboxAround(at: GeoPoint): String =
            "${at.lon - NEAR_BOX_DEGREES},${at.lat + NEAR_BOX_DEGREES}," +
                "${at.lon + NEAR_BOX_DEGREES},${at.lat - NEAR_BOX_DEGREES}"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String): List<Suggestion> =
            json.decodeFromString<List<Place>>(body).mapNotNull { place ->
                val lat = place.lat?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = place.lon?.toDoubleOrNull() ?: return@mapNotNull null
                Suggestion(
                    title = title(place),
                    location = runCatching { GeoPoint(lat, lon) }.getOrNull() ?: return@mapNotNull null,
                    resultType = place.type ?: place.category ?: "place",
                )
            }

        /**
         * "Prairie Diner, Rockton, KS" or "5260 Prairie Road, Smith County, KS"
         * — Nominatim's own display_name is a long comma-chain ending in the
         * country, too wide for a suggestion row.
         */
        private fun title(place: Place): String {
            val a = place.address ?: Address()
            val label = place.name?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(a.houseNumber, a.road).joinToString(" ").ifBlank { null }
            val place2 = a.city ?: a.town ?: a.village ?: a.hamlet ?: a.county
            val region = listOfNotNull(place2, a.state).distinct().joinToString(", ")
            return when {
                label != null && region.isNotBlank() -> "$label, $region"
                label != null -> label
                region.isNotBlank() -> region
                else -> place.displayName?.substringBefore(", United States") ?: "Unknown place"
            }
        }
    }

    @Serializable
    internal data class Place(
        val lat: String? = null,
        val lon: String? = null,
        val name: String? = null,
        val category: String? = null,
        val type: String? = null,
        @SerialName("display_name") val displayName: String? = null,
        val address: Address? = null,
    )

    @Serializable
    internal data class Address(
        @SerialName("house_number") val houseNumber: String? = null,
        val road: String? = null,
        val city: String? = null,
        val town: String? = null,
        val village: String? = null,
        val hamlet: String? = null,
        val county: String? = null,
        val state: String? = null,
    )
}
