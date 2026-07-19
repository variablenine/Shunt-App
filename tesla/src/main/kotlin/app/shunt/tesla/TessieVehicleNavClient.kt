package app.shunt.tesla

import app.shunt.core.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
) : VehicleNavClient {

    /** null = not yet probed, true = single-call works, false = use the chain. */
    @Volatile
    private var waypointsRequestWorks: Boolean? = null

    override suspend fun pushRoute(waypoints: List<GeoPoint>): PushResult = sendChain(waypoints)

    override suspend fun advanceTo(remaining: List<GeoPoint>): PushResult = sendChain(remaining)

    private suspend fun sendChain(chain: List<GeoPoint>): PushResult {
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

        return sendGpsChain(chain)
    }

    private suspend fun tryWaypointsRequest(chain: List<GeoPoint>): CommandOutcome {
        val encoded = chain.joinToString("|") { "${it.lat},${it.lon}" }
        return executeCommand("navigation_waypoints_request", json.encodeToString(WaypointsRequest(encoded)))
    }

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
    private data class CommandResponse(val response: CommandResult? = null, val error: String? = null)

    @Serializable
    private data class CommandResult(val result: Boolean = false, val reason: String = "")

    private companion object {
        const val ORDER_REPLACE_TRIP = 1
        const val ORDER_APPEND_STOP = 3
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        // A command response naming these is a permanent, don't-retry failure.
        val UNSUPPORTED_MARKERS = listOf("unsupported", "not supported", "not_supported", "unknown command", "no handler", "invalid_command")
        val PERMANENT_MARKERS = listOf("unauthorized", "forbidden", "invalid_token")
    }
}
