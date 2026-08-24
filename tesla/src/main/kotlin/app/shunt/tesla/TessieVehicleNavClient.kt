package app.shunt.tesla

import app.shunt.core.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Production [VehicleNavClient] backed by Tessie (https://api.tessie.com), a
 * paid drop-in proxy for Tesla's Fleet API. Tessie performs Vehicle Command
 * Protocol signing on the caller's behalf — which a 2021 Model 3 requires — so
 * there is no signing logic here, only authenticated HTTP against the user's
 * own vehicle with their own bearer token (from local.properties / env, never
 * committed).
 *
 * Chain building. Waypoints are pushed as a chain via
 * `POST /api/1/vehicles/{vin}/command/navigation_gps_request` with
 * `{ "lat", "lon", "order" }`, where order 1 = replace trip, 2 = prepend stop,
 * 3 = append stop. The first point of a chain is sent with 1 and every
 * subsequent point with 3. [pushRoute] sends the full chain; [advanceTo]
 * re-sends the remaining chain the same way.
 *
 * Fast path. Tesla's Fleet API also documents `navigation_waypoints_request`,
 * which sends the whole chain in one call — but Tesla's own vehicle-command
 * proxy has no handler for it, so it may not work through Tessie either. We do
 * not assume it works: it's attempted once, the outcome is cached, and on a
 * "not supported" response we fall back to the per-point chain from then on.
 *
 * Every failure maps to [PushResult.Failed] with an accurate [retryable] flag —
 * the drive monitor's alerting depends on that distinction and must never be
 * handed a false success. Nothing throws out of the public methods.
 */
class TessieVehicleNavClient(
    private val http: OkHttpClient,
    private val bearerToken: String,
    private val vin: String,
    private val baseUrl: String = "https://api.tessie.com",
    private val rateLimiter: CommandRateLimiter = CommandRateLimiter(),
    private val locale: String = "en-US",
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : VehicleNavClient {

    /** null = not yet probed, true = single-call works, false = use the chain. */
    @Volatile
    private var waypointsRequestWorks: Boolean? = null

    override suspend fun pushRoute(waypoints: List<GeoPoint>, label: String?): PushResult =
        sendChain(waypoints, label)

    override suspend fun advanceTo(remaining: List<GeoPoint>, label: String?): PushResult =
        sendChain(remaining, label)

    private suspend fun sendChain(chain: List<GeoPoint>, label: String? = null): PushResult {
        if (chain.isEmpty()) return PushResult.Failed("empty waypoint chain", retryable = false)

        if (waypointsRequestWorks != false) {
            when (val outcome = tryWaypointsRequest(chain)) {
                CommandOutcome.Ok -> {
                    waypointsRequestWorks = true
                    return PushResult.Success
                }
                is CommandOutcome.Rejected ->
                    if (outcome.unsupported) {
                        waypointsRequestWorks = false // documented fallback: use the chain
                    } else {
                        // Auth/transient failure — the chain would fail the same way.
                        return PushResult.Failed(outcome.reason, outcome.retryable)
                    }
            }
        }

        if (gpsChainWorks != false) {
            when (val outcome = sendGpsChain(chain)) {
                is PushResult.Failed ->
                    if (!outcome.reason.looksUnsupported()) return outcome
                    else gpsChainWorks = false
                else -> return outcome
            }
        }

        // Last resort: share just the destination. Tesla's signed-command proxy
        // implements navigation_request and none of the chain commands, so on a
        // car that requires signing this is the only thing that lands — at the
        // cost of the route shape, which the caller must surface.
        return shareDestination(chain.last(), label)
    }

    /**
     * Share a single destination via Tessie's own `share` command.
     *
     * Not the Fleet passthrough: Tesla's signed-command proxy answers
     * navigation_request with "command requires using the REST API" — it knows
     * the command but refuses to sign it. Tessie's native endpoint performs that
     * REST call itself, which is what actually reaches the car. It takes one
     * destination and no waypoints, so the route shape is lost.
     */
    private suspend fun shareDestination(point: GeoPoint, label: String? = null): PushResult {
        // **Two formats, most specific first.** See [shareMapUrl].
        when (val viaUrl = shareOnce(shareMapUrl(point, label))) {
            is ShareOutcome.Done -> return viaUrl.result
            ShareOutcome.ValueRejected -> Unit
        }
        return when (val plain = shareOnce(shareValue(point))) {
            is ShareOutcome.Done -> plain.result
            ShareOutcome.ValueRejected ->
                PushResult.Failed("the vehicle rejected the destination", retryable = true)
        }
    }

    /** What one `share` call did. [ValueRejected] means try a different format. */
    private sealed interface ShareOutcome {
        data class Done(val result: PushResult) : ShareOutcome
        /** The car took the command and would not use the value it was given. */
        data object ValueRejected : ShareOutcome
    }

    private suspend fun shareOnce(value: String): ShareOutcome {
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

        val (code, text) = runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            }
        }.getOrElse { e ->
            return ShareOutcome.Done(PushResult.Failed("network error: ${e.message}", retryable = true))
        }

        if (code !in 200..299) {
            // A refused *value* is worth trying again in another format; a
            // refused *request* is not, and re-sending it would only cost the
            // rate limiter another slot.
            if (code == 400 || code == 422) return ShareOutcome.ValueRejected
            return ShareOutcome.Done(
                PushResult.Failed(
                    "HTTP $code${text.take(200).let { if (it.isBlank()) "" else ": $it" }}",
                    retryable = statusRetryable(code),
                ),
            )
        }
        val ok = runCatching { json.decodeFromString<ShareResponse>(text).result }.getOrDefault(false)
        return if (ok) {
            ShareOutcome.Done(
                PushResult.DestinationOnly(
                    "this vehicle only accepts a single destination, not a waypoint route",
                ),
            )
        } else {
            ShareOutcome.ValueRejected
        }
    }

    /**
     * The point as a map link, which is the form the car resolves rather than
     * *searches for*.
     *
     * **Share is a search box, and that is the problem.** It is the one place
     * Shunt hands the car a string and lets the car decide what it means, and
     * given a bare coordinate pair the car runs it through the same place
     * lookup a typed query goes through. Two failures follow, both reported
     * from real drives: it can resolve to something near the coordinate rather
     * than the coordinate, and where the lookup finds more than one candidate
     * the car stops and asks the driver to pick — "sometimes the car doesn't
     * know what specific location is being referenced and will ask the user to
     * select which location the car is being sent to."
     *
     * A map link carries the coordinate as a coordinate, so there is nothing to
     * disambiguate. It costs no account and no key of ours — it is a string,
     * not a request Shunt makes — though the car may resolve it through the
     * link's own host, which is worth knowing before this is extended.
     *
     * [shareValue] stays as the fallback: it is the format that has been
     * observed to work, so a car that will not take the link is no worse off
     * than before.
     */
    private fun shareMapUrl(point: GeoPoint, label: String? = null): String {
        val q = shareValue(point)
        // The label rides *after* the coordinate, in the form map links use for
        // a named pin. The coordinate stays the authoritative part — this only
        // decides what the car's screen calls the place, which for the trip's
        // real destination should be the place the driver typed rather than a
        // pair of numbers. Parentheses and the characters that would end the
        // query are stripped so a name can never change what is being asked
        // for.
        val name = label?.filterNot { it in "()&#?" }?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
        return "https://maps.google.com/maps?q=" + q + if (name != null) "($name)" else ""
    }

    /**
     * The point as the share command wants it: plain decimal degrees, six
     * places, always with a `.` separator.
     *
     * Share is the one place Shunt hands the car a *string* and lets the car
     * work out what it means, which makes it the one place a formatting slip
     * turns into "the car drove somewhere else". Kotlin's default `Double`
     * rendering is the hazard: a coordinate small enough in magnitude comes out
     * in scientific notation (`9.5E-5`), which nothing downstream is obliged to
     * parse as a number — and a parser that gives up on the coordinates may
     * fall back to treating the whole string as a place name, which is exactly
     * the failure where a car ends up at the middle of a town.
     *
     * Six places is about 11 cm, far finer than anywhere a car can park.
     */
    private fun shareValue(point: GeoPoint): String =
        String.format(java.util.Locale.US, "%.6f,%.6f", point.lat, point.lon)

    private fun String.looksUnsupported(): Boolean {
        val r = lowercase()
        return "invalid_command" in r || "not_found" in r || "unsupported" in r ||
            "invalid command" in r
    }

    private suspend fun tryWaypointsRequest(chain: List<GeoPoint>): CommandOutcome {
        val encoded = chain.joinToString("|") { "${it.lat},${it.lon}" }
        return executeCommand("navigation_waypoints_request", json.encodeToString(WaypointsRequest(encoded)))
    }

    /** null = not yet probed, false = this car rejects the per-point chain too. */
    @Volatile
    private var gpsChainWorks: Boolean? = null

    private suspend fun sendGpsChain(chain: List<GeoPoint>): PushResult {
        chain.forEachIndexed { index, point ->
            // First point replaces the trip; the rest append as stops.
            val order = if (index == 0) ORDER_REPLACE_TRIP else ORDER_APPEND_STOP
            val body = json.encodeToString(GpsRequest(point.lat, point.lon, order))
            when (val outcome = executeCommand("navigation_gps_request", body)) {
                CommandOutcome.Ok -> Unit
                is CommandOutcome.Rejected -> return PushResult.Failed(outcome.reason, outcome.retryable)
            }
        }
        return PushResult.Success
    }

    private suspend fun executeCommand(command: String, body: String): CommandOutcome {
        rateLimiter.acquire()
        val request = Request.Builder()
            .url("$baseUrl/api/1/vehicles/$vin/command/$command")
            .header("Authorization", "Bearer $bearerToken")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            }
        }.getOrElse { e ->
            // Connectivity failure — transient by nature.
            return CommandOutcome.Rejected("network error: ${e.message}", retryable = true, unsupported = false)
        }

        val (code, text) = response
        if (code in 200..299) {
            val parsed = runCatching { json.decodeFromString<CommandResponse>(text) }.getOrNull()
            if (parsed?.response?.result == true) return CommandOutcome.Ok
            val reason = parsed?.response?.reason?.ifBlank { null }
                ?: parsed?.error?.ifBlank { null }
                ?: "command rejected"
            return CommandOutcome.Rejected(reason, retryable = reasonRetryable(reason), unsupported = reasonUnsupported(reason))
        }
        return CommandOutcome.Rejected(
            reason = "HTTP $code${text.take(200).let { if (it.isBlank()) "" else ": $it" }}",
            retryable = statusRetryable(code),
            unsupported = code == 404 || code == 400,
        )
    }

    private fun statusRetryable(code: Int): Boolean = when (code) {
        401, 403, 404, 400 -> false // auth / not-found / bad-request — permanent
        408, 425, 429 -> true // timeout (asleep) / too-early / rate-limited
        else -> code >= 500 // server errors — transient
    }

    private fun reasonUnsupported(reason: String): Boolean {
        val r = reason.lowercase()
        return UNSUPPORTED_MARKERS.any { it in r }
    }

    private fun reasonRetryable(reason: String): Boolean {
        val r = reason.lowercase()
        if (reasonUnsupported(r)) return false
        return PERMANENT_MARKERS.none { it in r }
    }

    private sealed interface CommandOutcome {
        data object Ok : CommandOutcome
        data class Rejected(val reason: String, val retryable: Boolean, val unsupported: Boolean) : CommandOutcome
    }

    @Serializable
    private data class GpsRequest(val lat: Double, val lon: Double, val order: Int)

    @Serializable
    private data class WaypointsRequest(val waypoints: String)

    @Serializable
    private data class ShareResponse(val result: Boolean = false)

    @Serializable
    private data class CommandResponse(val response: CommandResult? = null, val error: String? = null)

    @Serializable
    private data class CommandResult(val result: Boolean = false, val reason: String = "")

    private companion object {
        /** Tessie's share command takes its parameters in the query string. */
        val EMPTY_BODY = "".toRequestBody(null)

        const val ORDER_REPLACE_TRIP = 1
        const val ORDER_APPEND_STOP = 3
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        // A command response naming these is a permanent, don't-retry failure.
        val UNSUPPORTED_MARKERS = listOf("unsupported", "not supported", "not_supported", "unknown command", "no handler", "invalid_command")
        val PERMANENT_MARKERS = listOf("unauthorized", "forbidden", "invalid_token")
    }
}
