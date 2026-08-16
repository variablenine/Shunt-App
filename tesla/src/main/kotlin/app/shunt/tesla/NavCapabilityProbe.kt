package app.shunt.tesla

import app.shunt.core.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Finds out, against a real car, which navigation channels actually work.
 *
 * Everything Shunt does with the vehicle rests on one unproven belief: that a
 * car requiring signed commands can only be given a single destination, because
 * the signing proxy answers the chain commands with "command requires using the
 * REST API". That belief came from failures seen through one path — Tessie's
 * Fleet passthrough — and if it is wrong, the whole waypoint chain could be
 * pushed in one call and none of the pin-by-pin steering would be needed.
 *
 * So this asks the car instead of guessing. Each channel is tried in turn and
 * then, crucially, **the car's own active route is read back** and compared with
 * what was sent. An HTTP 200 proves only that a server accepted a request; the
 * read-back is what proves the car did something. Steps alternate between two
 * points so a change is visible both in the read-back and on the car's screen.
 *
 * This is a diagnostic that really does redirect the car's navigation. It must
 * only run on an explicit tap, with the driver told what it will do.
 */
class NavCapabilityProbe(
    private val http: OkHttpClient,
    private val bearerToken: String,
    private val vin: String,
    private val account: TessieAccountClient,
    private val baseUrl: String = "https://api.tessie.com",
    private val rateLimiter: CommandRateLimiter = CommandRateLimiter(),
    private val locale: String = "en-US",
    /** Time for the car to act before its state is read back. Virtual in tests. */
    private val settle: suspend () -> Unit = { kotlinx.coroutines.delay(SETTLE_MILLIS) },
) {
    /** What a single channel did when it was tried. */
    data class Step(
        /** The channel, e.g. "navigation_waypoints_request". */
        val channel: String,
        /** Exactly what was sent, so a result can be reproduced by hand. */
        val sent: String,
        val accepted: Boolean,
        /** Raw response, truncated — the wording is the useful part. */
        val response: String,
        /** Where the car said it was going afterwards, or null if unreadable. */
        val carAimedAt: GeoPoint? = null,
        val carDestinationName: String? = null,
        /** True when the car's own state moved to the point this step sent. */
        val landed: Boolean = false,
        /**
         * Why the read-back said nothing, when it said nothing.
         *
         * "Accepted, car state unreadable" was the whole result on a real car
         * and it is not an answer — it does not say whether the car ignored the
         * command, was asleep, or was simply never asked. A probe whose failure
         * mode is indistinguishable from its success mode is worth nothing, so
         * the reason is carried rather than collapsed.
         */
        val readProblem: String? = null,
    ) {
        val verdict: String get() = when {
            landed -> "WORKS — the car moved to this point"
            accepted && carAimedAt != null -> "accepted, but the car did not move to it"
            accepted -> "accepted, but ${readProblem ?: "car state unreadable"}"
            else -> "rejected"
        }
    }

    /**
     * Try every channel between [pointA] and [pointB] — two places a few miles
     * apart, near enough to be sane destinations and far enough apart that the
     * read-back can tell them apart.
     *
     * [onStep] is called as each result lands so a slow probe shows progress.
     */
    suspend fun run(
        pointA: GeoPoint,
        pointB: GeoPoint,
        onStep: (Step) -> Unit = {},
    ): List<Step> {
        val steps = mutableListOf<Step>()

        suspend fun record(channel: String, sent: String, target: GeoPoint, call: suspend () -> Pair<Boolean, String>) {
            val (accepted, body) = runCatching { call() }
                .getOrElse { e -> false to "threw: ${e.message}" }
            // Only wait and read when something was accepted; a rejected command
            // has nothing to have changed.
            // Uncached, and that is the difference between this probe working
            // and not. Tessie's cached state is whatever it last captured, so
            // reading it moments after issuing a command answers from *before*
            // the command — which is why every accepted step on a real car came
            // back "car state unreadable" and the probe proved nothing. Waking
            // the car is the correct cost here: this is an explicit, parked
            // diagnostic that already warns it will redirect the navigation.
            var readError: String? = null
            val active = if (accepted) {
                settle()
                runCatching { account.activeRoute(bearerToken, vin, fresh = true) }
                    .onFailure { readError = "couldn't read the car: ${it.message ?: it.toString()}" }
                    .getOrNull()
                    .also { if (it == null && readError == null) readError = "the car did not answer" }
            } else {
                null
            }
            val aimedAt = active?.let { r ->
                val lat = r.latitude
                val lon = r.longitude
                if (lat != null && lon != null) GeoPoint(lat, lon) else null
            }
            val step = Step(
                channel = channel,
                sent = sent,
                accepted = accepted,
                response = body.take(RESPONSE_CHARS),
                carAimedAt = aimedAt,
                carDestinationName = active?.destinationName,
                landed = aimedAt != null && isSamePlace(aimedAt, target),
                readProblem = when {
                    !accepted -> null
                    readError != null -> readError
                    // The car answered and said it is navigating nowhere. That
                    // is a real result, not a failed read: the command was
                    // accepted by the server and the car did not act on it.
                    active == null -> "the car did not answer"
                    aimedAt == null -> "the car reports no active route"
                    else -> null
                },
            )
            steps += step
            onStep(step)
        }

        // 1. The whole chain in one call. If this works, none of the pin-by-pin
        //    steering is necessary and the car can be handed the real route.
        val chain = "${pointA.lat},${pointA.lon}|${pointB.lat},${pointB.lon}"
        record("navigation_waypoints_request", "A|B: $chain", pointB) {
            command("navigation_waypoints_request", json.encodeToString(WaypointsBody(chain)))
        }

        // 2-3. The per-point chain: replace the trip, then append a stop. This
        //      is the documented multi-stop route, tried the way we'd use it.
        record("navigation_gps_request order=1", "A: ${pointA.lat},${pointA.lon}", pointA) {
            command("navigation_gps_request", json.encodeToString(GpsBody(pointA.lat, pointA.lon, 1)))
        }
        record("navigation_gps_request order=3", "B: ${pointB.lat},${pointB.lon}", pointB) {
            command("navigation_gps_request", json.encodeToString(GpsBody(pointB.lat, pointB.lon, 3)))
        }

        // 4+. The share channel — the one that currently works — given several
        //     forms of the same place. A link the car resolves itself would let
        //     each pin arrive as a named place rather than bare coordinates,
        //     and none of these involve Google.
        for ((index, format) in ShareFormat.entries.withIndex()) {
            val target = if (index % 2 == 0) pointA else pointB
            record("share ${format.label}", format.render(target), target) {
                share(format.render(target))
            }
        }

        return steps
    }

    /** The forms of a destination the share command might accept. */
    enum class ShareFormat(val label: String) {
        /** Bare coordinates — what Shunt sends today, the control. */
        COORDINATES("coordinates") {
            override fun render(p: GeoPoint) = "${p.lat},${p.lon}"
        },

        /** The RFC 5870 geo URI: a standard, no service behind it at all. */
        GEO_URI("geo: URI") {
            override fun render(p: GeoPoint) = "geo:${p.lat},${p.lon}"
        },

        /** OpenStreetMap — keyless, and the same data Shunt already routes on. */
        OPENSTREETMAP("OpenStreetMap link") {
            override fun render(p: GeoPoint) =
                "https://www.openstreetmap.org/?mlat=${p.lat}&mlon=${p.lon}#map=17/${p.lat}/${p.lon}"
        },

        /** Apple Maps — no account, and a link format the car may well know. */
        APPLE_MAPS("Apple Maps link") {
            override fun render(p: GeoPoint) = "https://maps.apple.com/?ll=${p.lat},${p.lon}&q=Waypoint"
        },

        /**
         * Google Maps, because the maintainer has had it work by hand:
         *
         * > add Google's in there too since I've had luck with that
         *
         * **This is not the Google API, and does not breach the keyless rule in
         * CLAUDE.md §3.** Nothing here calls Google. Shunt hands the *car* a URL
         * string through Tessie's share command, exactly as a person sharing a
         * link from their phone would, and the car resolves it with whatever
         * credentials Tesla already has. No account, no key, no card, and no
         * request from this app to any Google host — so there is nothing that
         * could be revoked out from under a user.
         *
         * The `api=1` search form rather than a short link: it carries the
         * coordinates literally, so what the car receives is inspectable, and
         * there is no redirect that could resolve to somewhere else. Which is
         * the whole subject of field note F-19 — a share string the car
         * re-geocodes is how 132 Birch St became 116.
         */
        GOOGLE_MAPS("Google Maps link") {
            override fun render(p: GeoPoint) =
                "https://www.google.com/maps/search/?api=1&query=${p.lat}%2C${p.lon}"
        },

        /**
         * The Google form a phone's share sheet actually produces, which is not
         * the one above and may well be the one the car knows best.
         */
        GOOGLE_MAPS_PLACE("Google Maps place link") {
            override fun render(p: GeoPoint) = "https://maps.google.com/?q=${p.lat},${p.lon}"
        },
        ;

        abstract fun render(p: GeoPoint): String
    }

    /** Fleet passthrough command. Returns accepted + the raw body. */
    private suspend fun command(name: String, body: String): Pair<Boolean, String> {
        rateLimiter.acquire()
        val request = Request.Builder()
            .url("$baseUrl/api/1/vehicles/$vin/command/$name")
            .header("Authorization", "Bearer $bearerToken")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val (code, text) = execute(request)
        val ok = code in 200..299 &&
            runCatching { json.decodeFromString<CommandEnvelope>(text).response?.result }
                .getOrNull() == true
        return ok to "HTTP $code $text"
    }

    /** Tessie's own share endpoint — the REST path that currently reaches the car. */
    private suspend fun share(value: String): Pair<Boolean, String> {
        rateLimiter.acquire()
        val url = "$baseUrl/$vin/command/share".toHttpUrl().newBuilder()
            .addQueryParameter("value", value)
            .addQueryParameter("locale", locale)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearerToken")
            .post(EMPTY_BODY)
            .build()
        val (code, text) = execute(request)
        val ok = code in 200..299 &&
            runCatching { json.decodeFromString<ShareEnvelope>(text).result }.getOrDefault(false)
        return ok to "HTTP $code $text"
    }

    private suspend fun execute(request: Request): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
        }

    @kotlinx.serialization.Serializable
    private data class GpsBody(val lat: Double, val lon: Double, val order: Int)

    @kotlinx.serialization.Serializable
    private data class WaypointsBody(val waypoints: String)

    @kotlinx.serialization.Serializable
    private data class CommandEnvelope(val response: Result? = null) {
        @kotlinx.serialization.Serializable
        data class Result(val result: Boolean = false, val reason: String = "")
    }

    @kotlinx.serialization.Serializable
    private data class ShareEnvelope(val result: Boolean = false)

    companion object {
        /** How far apart two coordinates can be and still be the same place. */
        const val SAME_PLACE_METERS = 500.0

        /** Long enough for the car to act and Tessie's state to catch up. */
        const val SETTLE_MILLIS = 6_000L

        private const val RESPONSE_CHARS = 300
        private val EMPTY_BODY = "".toRequestBody(null)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Whether the car ended up at the point a step sent. Deliberately loose:
         * the car reports the road position of its destination, not the exact
         * coordinate it was handed.
         */
        internal fun isSamePlace(a: GeoPoint, b: GeoPoint): Boolean {
            val meanLat = Math.toRadians((a.lat + b.lat) / 2)
            val dLat = (a.lat - b.lat) * 111_320.0
            val dLon = (a.lon - b.lon) * 111_320.0 * Math.cos(meanLat)
            return Math.hypot(dLat, dLon) <= SAME_PLACE_METERS
        }
    }
}
