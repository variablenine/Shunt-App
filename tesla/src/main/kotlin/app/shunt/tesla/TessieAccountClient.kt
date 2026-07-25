package app.shunt.tesla

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** A vehicle on the user's account, from the read-only listing. */
data class VehicleSummary(val vin: String, val displayName: String, val state: String) {
    /** Asleep cars still accept routes — they wake — but it's worth showing. */
    val isAwake: Boolean get() = state.equals("online", ignoreCase = true)
}

/**
 * What the car currently thinks it is doing, read from cached state.
 *
 * The open question this exists to answer: when Tesla's own planner inserts a
 * charging stop, does the active route report the *supercharger* or the *final
 * destination*? That determines whether Shunt can route to the car's chosen
 * charger, and no documentation settles it — a real vehicle does.
 */
data class ActiveRoute(
    val destinationName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val milesToArrival: Double?,
    val minutesToArrival: Double?,
    /** Battery % the car predicts on arrival — negative/low means it must charge. */
    val energyAtArrival: Double?,
    val batteryLevel: Int?,
    val estimatedRangeMiles: Double?,
) {
    val isNavigating: Boolean get() = latitude != null && longitude != null
}

/** Outcome of checking the credentials without touching the car. */
sealed interface ConnectionCheck {
    data class Ok(val vehicles: List<VehicleSummary>) : ConnectionCheck

    /** Reached the service, but it refused the token. */
    data class BadToken(val detail: String) : ConnectionCheck

    /** Couldn't reach the service at all. */
    data class Unreachable(val detail: String) : ConnectionCheck
}

/**
 * Read-only account queries against Tessie's Fleet API proxy.
 *
 * Deliberately separate from [VehicleNavClient], which only ever *commands* the
 * car. Listing vehicles sends no command and does not wake anything, so it is
 * the safe way to answer "are my credentials actually working?" without the car
 * doing something in a car park somewhere.
 */
class TessieAccountClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://api.tessie.com",
) {
    /**
     * Check [token] by listing the vehicles it can see. Never throws: every
     * failure comes back as a [ConnectionCheck] the UI can state plainly.
     */
    suspend fun check(token: String): ConnectionCheck {
        if (token.isBlank()) return ConnectionCheck.BadToken("no token entered")
        val request = Request.Builder()
            .url("$baseUrl/api/1/vehicles")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        val (code, text) = runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            }
        }.getOrElse { e ->
            return ConnectionCheck.Unreachable(e.message ?: e.toString())
        }

        return when {
            code == 401 || code == 403 ->
                ConnectionCheck.BadToken("the service rejected this token (HTTP $code)")
            code !in 200..299 ->
                ConnectionCheck.Unreachable("HTTP $code${text.take(160).let { if (it.isBlank()) "" else ": $it" }}")
            else -> runCatching { ConnectionCheck.Ok(parse(text)) }
                .getOrElse { ConnectionCheck.Unreachable("couldn't read the vehicle list") }
        }
    }

    /**
     * Read the car's current state from Tessie's cache. `use_cache=true` means
     * this never wakes the vehicle — it is safe to call while it sleeps.
     */
    suspend fun activeRoute(token: String, vin: String): ActiveRoute? {
        if (token.isBlank() || vin.isBlank()) return null
        val request = Request.Builder()
            .url("$baseUrl/$vin/state?use_cache=true")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val text = runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }
        }.getOrNull() ?: return null
        return runCatching { parseState(text) }.getOrNull()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseState(body: String): ActiveRoute {
            val state = json.decodeFromString<VehicleState>(body)
            return ActiveRoute(
                destinationName = state.driveState?.activeRouteDestination,
                latitude = state.driveState?.activeRouteLatitude,
                longitude = state.driveState?.activeRouteLongitude,
                milesToArrival = state.driveState?.activeRouteMilesToArrival,
                minutesToArrival = state.driveState?.activeRouteMinutesToArrival,
                energyAtArrival = state.driveState?.activeRouteEnergyAtArrival,
                batteryLevel = state.chargeState?.batteryLevel,
                estimatedRangeMiles = state.chargeState?.estBatteryRange,
            )
        }

        fun parse(body: String): List<VehicleSummary> =
            json.decodeFromString<VehicleListResponse>(body).response.mapNotNull { v ->
                val vin = v.vin?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                VehicleSummary(
                    vin = vin,
                    displayName = v.displayName?.takeIf { it.isNotBlank() } ?: vin,
                    state = v.state.orEmpty(),
                )
            }
    }

    @Serializable
    private data class VehicleState(
        @kotlinx.serialization.SerialName("drive_state") val driveState: DriveState? = null,
        @kotlinx.serialization.SerialName("charge_state") val chargeState: ChargeState? = null,
    )

    @Serializable
    private data class DriveState(
        @kotlinx.serialization.SerialName("active_route_destination")
        val activeRouteDestination: String? = null,
        @kotlinx.serialization.SerialName("active_route_latitude")
        val activeRouteLatitude: Double? = null,
        @kotlinx.serialization.SerialName("active_route_longitude")
        val activeRouteLongitude: Double? = null,
        @kotlinx.serialization.SerialName("active_route_miles_to_arrival")
        val activeRouteMilesToArrival: Double? = null,
        @kotlinx.serialization.SerialName("active_route_minutes_to_arrival")
        val activeRouteMinutesToArrival: Double? = null,
        @kotlinx.serialization.SerialName("active_route_energy_at_arrival")
        val activeRouteEnergyAtArrival: Double? = null,
    )

    @Serializable
    private data class ChargeState(
        @kotlinx.serialization.SerialName("battery_level") val batteryLevel: Int? = null,
        @kotlinx.serialization.SerialName("est_battery_range") val estBatteryRange: Double? = null,
    )

    @Serializable
    private data class VehicleListResponse(val response: List<VehicleEntry> = emptyList())

    @Serializable
    private data class VehicleEntry(
        val vin: String? = null,
        @kotlinx.serialization.SerialName("display_name") val displayName: String? = null,
        val state: String? = null,
    )
}
