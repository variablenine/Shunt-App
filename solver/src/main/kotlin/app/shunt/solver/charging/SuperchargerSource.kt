package app.shunt.solver.charging

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.BoundingBox
import app.shunt.solver.geo.pointToPolylineProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** A Tesla charging site from OpenStreetMap. */
data class Supercharger(val id: Long, val name: String, val location: GeoPoint)

/**
 * Tesla charging sites, from OpenStreetMap via Overpass.
 *
 * Keyless and account-free like everything else here: Overpass serves OSM data
 * (ODbL) over a plain HTTP query with no key, no sign-up and no card, which is
 * the hard constraint this project is built around. The trade is OSM's usual
 * one — coverage is community-maintained, so a brand-new site may be missing.
 * That is acceptable for what this is used for: suggesting somewhere to charge
 * *before* a long detour, never as the sole means of reaching a charger.
 */
class SuperchargerSource(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://overpass-api.de/api/interpreter",
) {
    /**
     * Every Tesla charging site in [bbox]. Returns an empty list on any failure
     * — a missing charger list degrades the suggestion, and must never block or
     * fail the routing the user actually asked for.
     */
    suspend fun inBox(bbox: BoundingBox): List<Supercharger> {
        val query = overpassQuery(bbox)
        val request = Request.Builder()
            .url(baseUrl)
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().add("data", query).build())
            .build()
        val body = runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }
        }.getOrNull() ?: return emptyList()
        return runCatching { parse(body) }.getOrDefault(emptyList())
    }

    companion object {
        const val USER_AGENT = "Shunt/1.0 (+https://github.com/variablenine/Shunt-App)"

        /** Cap the reply size; a state-sized box can otherwise return thousands. */
        private const val RESULT_LIMIT = 400

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Tesla sites are tagged inconsistently across OSM — some carry
         * `brand`, some only `operator` or `network`, and some only the
         * Supercharger socket type — so all four are unioned rather than
         * trusting any one of them. `out center` gives ways (a mapped parking
         * area rather than a single node) a usable coordinate.
         */
        fun overpassQuery(bbox: BoundingBox): String {
            val box = "${bbox.minLat},${bbox.minLon},${bbox.maxLat},${bbox.maxLon}"
            return buildString {
                append("[out:json][timeout:25];(")
                for (kind in listOf("node", "way")) {
                    append("""$kind["amenity"="charging_station"]["brand"~"Tesla",i]($box);""")
                    append("""$kind["amenity"="charging_station"]["operator"~"Tesla",i]($box);""")
                    append("""$kind["amenity"="charging_station"]["network"~"Tesla",i]($box);""")
                    append("""$kind["amenity"="charging_station"]["socket:tesla_supercharger"]($box);""")
                }
                append(");out center $RESULT_LIMIT;")
            }
        }

        fun parse(body: String): List<Supercharger> =
            json.decodeFromString<OverpassResponse>(body).elements.mapNotNull { it.toSupercharger() }
    }

    @Serializable
    private data class OverpassResponse(val elements: List<Element> = emptyList())

    @Serializable
    private data class Element(
        val id: Long = 0,
        val lat: Double? = null,
        val lon: Double? = null,
        val center: Center? = null,
        val tags: Map<String, String> = emptyMap(),
    ) {
        fun toSupercharger(): Supercharger? {
            val latitude = lat ?: center?.lat ?: return null
            val longitude = lon ?: center?.lon ?: return null
            val name = tags["name"]?.takeIf { it.isNotBlank() }
                ?: tags["operator"]?.takeIf { it.isNotBlank() }
                ?: "Tesla charging"
            return Supercharger(id, name, GeoPoint(latitude, longitude))
        }
    }

    @Serializable
    private data class Center(val lat: Double, val lon: Double)
}

/**
 * Pick the charging stop to slot in before a long detour: the one that gets you
 * *farthest along* the planned route while still being comfortably reachable
 * and close to the road you were going to drive anyway.
 *
 * Farthest-along rather than nearest is deliberate. Stopping at the first
 * charger you pass wastes the charge already in the battery and adds a stop
 * earlier than needed; the useful stop is the last one you can still reach.
 *
 * [reachableMeters] is how far the car can go before it must be plugged in —
 * already derated, see [RangeEstimate.REACHABLE_FRACTION]. [corridorMeters] is
 * how far off the route a candidate may sit before it stops being "on the way".
 */
fun chooseChargeStop(
    route: List<GeoPoint>,
    candidates: List<Supercharger>,
    reachableMeters: Double,
    corridorMeters: Double = 8_000.0,
): Supercharger? {
    if (route.size < 2 || candidates.isEmpty()) return null

    var best: Supercharger? = null
    var bestAlong = -1.0
    var bestOffRoute = Double.MAX_VALUE

    for (candidate in candidates) {
        val progress = pointToPolylineProgress(candidate.location, route)
        if (progress.distanceMeters > corridorMeters) continue
        // Getting there costs the drive along the route plus the hop off it.
        if (progress.alongMeters + progress.distanceMeters > reachableMeters) continue

        val better = progress.alongMeters > bestAlong ||
            (progress.alongMeters == bestAlong && progress.distanceMeters < bestOffRoute)
        if (better) {
            best = candidate
            bestAlong = progress.alongMeters
            bestOffRoute = progress.distanceMeters
        }
    }
    return best
}
