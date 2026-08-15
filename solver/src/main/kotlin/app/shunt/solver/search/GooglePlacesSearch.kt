package app.shunt.solver.search

import app.shunt.core.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Destination search through Google Places, for people who would rather have the
 * coverage than the principle.
 *
 * ## Why this exists, against the rule in CLAUDE.md §3
 *
 * The keyless constraint is a real product decision and it is not being
 * repealed. But it has a real cost, and after enough of it the maintainer said:
 *
 * > Rather than lazily calculating […] I'm getting sick of missing locations.
 * > For the search mechanism can we just go with Google for right now I'm like
 * > that fed up.
 *
 * That is a fair call on the evidence. Measured against small-town POIs, Photon
 * and Nominatim both find and correctly rank anything that is *in* OpenStreetMap
 * — so the composition is not at fault and there is no tuning left to do. What
 * is missing is missing from OSM, and no keyless service fixes that.
 *
 * ## How it stays compatible with the constraint
 *
 * **Opt-in, with the user's own key, exactly like the Tessie integration.** The
 * app ships with no key and behaves precisely as it did; someone who wants
 * Google coverage puts their own key in settings.
 *
 * That structure is not squeamishness, it is the only shape that ships:
 *
 * - A key bundled into an open-source APK is extractable in minutes, and Places
 *   requires a billing account — so the bundled version means strangers'
 *   searches billed to the maintainer's card.
 * - §3's deeper point still holds. A service that can issue credentials can
 *   revoke them, and this project assumes it may face pressure from surveillance
 *   vendors. A Shunt that *cannot work* without Google is a Shunt with an off
 *   switch someone else owns. Keyless stays the default so that switch is never
 *   load-bearing.
 * - And there is the obvious tension worth stating once: this is an app for not
 *   being tracked, and every query here tells Google where somebody is thinking
 *   of going. That is the user's call to make for themselves — which is what
 *   opt-in means — but it should be made knowingly.
 *
 * Uses the Places API (New) `searchText`, which takes the key in a header rather
 * than the query string and lets the response be trimmed by field mask — both of
 * which keep the request smaller and the key out of logs and referrer headers.
 */
class GooglePlacesSearch(
    private val http: OkHttpClient,
    /** The user's own key. Blank disables this entirely. */
    private val apiKey: () -> String,
    private val baseUrl: String = "https://places.googleapis.com/v1/places:searchText",
) {
    /** Whether a key has been supplied; without one nothing here is attempted. */
    val configured: Boolean get() = apiKey().isNotBlank()

    /**
     * Places matching [query], biased to a circle around [at].
     *
     * Returns an empty list on any failure rather than throwing, so a bad key or
     * an exhausted quota degrades to the keyless geocoders instead of breaking
     * search. That is the whole reason the keyless path is kept underneath.
     */
    suspend fun suggest(query: String, at: GeoPoint): List<Suggestion> {
        val key = apiKey()
        if (key.isBlank() || query.isBlank()) return emptyList()

        val body = buildJsonObject {
            put("textQuery", query)
            put("maxResultCount", MAX_RESULTS)
            put(
                "locationBias",
                buildJsonObject {
                    put(
                        "circle",
                        buildJsonObject {
                            put(
                                "center",
                                buildJsonObject {
                                    put("latitude", at.lat)
                                    put("longitude", at.lon)
                                },
                            )
                            put("radius", BIAS_RADIUS_METERS)
                        },
                    )
                },
            )
        }.toString()

        val request = Request.Builder()
            .url(baseUrl)
            .header("X-Goog-Api-Key", key)
            // Only the three fields actually used. Places bills by field mask,
            // so asking for everything would cost the user money for data that
            // is thrown away on arrival.
            .header("X-Goog-FieldMask", "places.displayName,places.formattedAddress,places.location")
            .post(body.toRequestBody(JSON))
            .build()

        val text = runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }
        }.getOrNull() ?: return emptyList()

        return runCatching { parse(text) }.getOrDefault(emptyList())
    }

    private fun parse(body: String): List<Suggestion> {
        val places = Json.parseToJsonElement(body).jsonObject["places"]?.jsonArray ?: return emptyList()
        return places.mapNotNull { element ->
            val place = element.jsonObject
            val name = place["displayName"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: return@mapNotNull null
            val location = place["location"]?.jsonObject ?: return@mapNotNull null
            val lat = location["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = location["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            Suggestion(
                title = name,
                location = GeoPoint(lat, lon),
                resultType = place["formattedAddress"]?.jsonPrimitive?.content ?: "Google",
            )
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()

        const val MAX_RESULTS = 8

        /**
         * How far around the driver to bias results.
         *
         * A bias, not a filter: a deliberately distant destination must still be
         * findable, and Places treats this as a preference rather than a bound.
         */
        const val BIAS_RADIUS_METERS = 50_000.0
    }
}
