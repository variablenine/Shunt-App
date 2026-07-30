package app.shunt.solver.charging

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.BoundingBox
import app.shunt.solver.geo.haversineMeters
import app.shunt.solver.geo.pointToPolylineProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

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
     * Tesla charging sites within [corridorMeters] of [route].
     *
     * A corridor, not a bounding box: a 400 km trip's box covers most of a
     * state, which is both a far larger Overpass query than needed (they time
     * out, and a timeout reads as "no chargers exist") and full of sites nowhere
     * near the road. Overpass's `around` filter takes a polyline directly, so
     * the query asks the question we actually mean.
     *
     * Returns an empty list on any failure — a missing charger list degrades
     * the suggestion, and must never block or fail the routing the user asked
     * for.
     */
    suspend fun alongRoute(route: List<GeoPoint>, corridorMeters: Double): List<Supercharger> {
        if (route.size < 2) return emptyList()
        return query(corridorQuery(route, corridorMeters))
    }

    /** Every Tesla charging site in [bbox]. */
    suspend fun inBox(bbox: BoundingBox): List<Supercharger> = query(boxQuery(bbox))

    private suspend fun query(query: String): List<Supercharger> {
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

        /** Cap the reply size; a long corridor can otherwise return hundreds. */
        private const val RESULT_LIMIT = 400

        /**
         * Thin the route before putting it in an `around` filter. A routed
         * polyline is tens of thousands of points; a query built from all of
         * them would be megabytes. One point every 2 km still describes the
         * corridor faithfully at the widths used here.
         */
        private const val CORRIDOR_SAMPLE_METERS = 2_000.0

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Ask for **every** charging station in the corridor and sort out which
         * are Tesla's on this side.
         *
         * Overpass tag filtering is exact-match-ish and OSM tags Tesla sites
         * every which way — `brand`, `operator`, `network`, socket types, or
         * only the name — so a server-side filter quietly drops real sites. It
         * is also one query instead of eight, which matters: the previous
         * version fanned four tag filters across two element types over a
         * whole-trip bounding box, and a timeout on that comes back looking
         * exactly like "there are no chargers here".
         */
        fun corridorQuery(route: List<GeoPoint>, corridorMeters: Double): String {
            val line = sampleAlong(route, CORRIDOR_SAMPLE_METERS)
                .joinToString(",") {
                    "${String.format(Locale.US, "%.5f", it.lat)}," +
                        String.format(Locale.US, "%.5f", it.lon)
                }
            val around = "around:${corridorMeters.toInt()},$line"
            return "[out:json][timeout:60];" +
                """nwr["amenity"="charging_station"]($around);""" +
                "out center $RESULT_LIMIT;"
        }

        fun boxQuery(bbox: BoundingBox): String {
            val box = "${bbox.minLat},${bbox.minLon},${bbox.maxLat},${bbox.maxLon}"
            return "[out:json][timeout:60];" +
                """nwr["amenity"="charging_station"]($box);""" +
                "out center $RESULT_LIMIT;"
        }

        /** Points along [route] roughly [every] metres apart, ends included. */
        fun sampleAlong(route: List<GeoPoint>, every: Double): List<GeoPoint> {
            if (route.size < 2) return route
            val out = mutableListOf(route.first())
            var since = 0.0
            for (i in 1 until route.size) {
                since += haversineMeters(route[i - 1], route[i])
                if (since >= every) {
                    out += route[i]
                    since = 0.0
                }
            }
            if (out.last() != route.last()) out += route.last()
            return out
        }

        fun parse(body: String): List<Supercharger> =
            json.decodeFromString<OverpassResponse>(body).elements
                .filter { it.isTesla() }
                .mapNotNull { it.toSupercharger() }
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
        /**
         * Whether this is a Tesla site, decided here rather than by the server.
         * OSM records the operator under whichever of these keys the mapper
         * reached for, so all of them are checked; the socket tag settles the
         * cases where none of the names mention Tesla at all.
         */
        fun isTesla(): Boolean {
            if (tags.keys.any { it.startsWith("socket:tesla") }) return true
            return TESLA_KEYS.any { key -> tags[key]?.contains("tesla", ignoreCase = true) == true }
        }

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

/** Tag keys OSM mappers use to record who runs a charging site. */
private val TESLA_KEYS = listOf("brand", "operator", "network", "name")

/** How far off the route a charging site may sit and still be worth the detour. */
const val CHARGER_CORRIDOR_METERS = 40_000.0

/**
 * Pick the charging stop to slot in before a long detour.
 *
 * Two competing pulls, scored against each other rather than ranked in turn:
 *
 *  - **Get as far as you can first.** Stopping at the first charger you pass
 *    wastes the charge already in the battery and adds a stop sooner than
 *    needed; the useful stop is the last one you can still reach.
 *  - **Don't drive miles out of your way.** Leaving the route costs the hop out
 *    *and* the hop back, so a site 30 km off the road has to buy a lot of
 *    progress to be worth more than one 5 km off it.
 *
 * So the score is progress along the route minus twice the excursion — plain
 * net progress, in metres. That is what lets [CHARGER_CORRIDOR_METERS] be
 * generous (chargers worth a detour are often a good way off a rural route)
 * without the widest corridor automatically winning.
 *
 * [reachableMeters] is how far the car can go before it must be plugged in —
 * already real-world derated and reserve-adjusted by the caller.
 */
fun chooseChargeStop(
    route: List<GeoPoint>,
    candidates: List<Supercharger>,
    reachableMeters: Double,
    corridorMeters: Double = CHARGER_CORRIDOR_METERS,
): Supercharger? {
    return rankChargeStops(route, candidates, reachableMeters, corridorMeters).firstOrNull()
}

/** All suitable reachable sites, ranked best-first for automatic or manual selection. */
fun rankChargeStops(
    route: List<GeoPoint>,
    candidates: List<Supercharger>,
    reachableMeters: Double,
    corridorMeters: Double = CHARGER_CORRIDOR_METERS,
): List<Supercharger> {
    if (route.size < 2 || candidates.isEmpty()) return emptyList()
    return candidates.mapNotNull { candidate ->
        val progress = pointToPolylineProgress(candidate.location, route)
        if (progress.distanceMeters > corridorMeters) return@mapNotNull null
        // Getting there costs the drive along the route plus the hop off it.
        if (progress.alongMeters + progress.distanceMeters > reachableMeters) return@mapNotNull null

        val score = progress.alongMeters - DETOUR_COST * progress.distanceMeters
        candidate to score
    }.sortedByDescending { it.second }.map { it.first }
}

/** Leaving the route is a round trip, so an excursion costs twice its length. */
private const val DETOUR_COST = 2.0
