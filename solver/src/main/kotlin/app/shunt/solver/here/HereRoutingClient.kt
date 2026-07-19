package app.shunt.solver.here

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.BoundingBox
import app.shunt.solver.routing.Route
import app.shunt.solver.routing.RoutingApi
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * HERE Routing API v8. Traffic-aware by default (v8 uses live traffic unless
 * told otherwise). `avoid[areas]` accepts up to 20 `!`-separated bounding
 * boxes; the cap is enforced here.
 *
 * NOTE: response parsing is written against HERE's documented v8 shape but
 * has NOT yet been verified against the live endpoint (no valid API key was
 * available when this was built). Verify + record a fixture before trusting
 * it — see the M1 notes in the README.
 */
class HereRoutingClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://router.hereapi.com",
) : RoutingApi {

    override suspend fun routes(
        origin: GeoPoint,
        destination: GeoPoint,
        avoidAreas: List<BoundingBox>,
        alternatives: Int,
    ): List<Route> {
        require(avoidAreas.size <= RoutingApi.MAX_AVOID_AREAS) {
            "HERE accepts at most ${RoutingApi.MAX_AVOID_AREAS} avoid areas, got ${avoidAreas.size}"
        }
        val url = "$baseUrl/v8/routes".toHttpUrl().newBuilder()
            .addQueryParameter("transportMode", "car")
            .addQueryParameter("origin", "${origin.lat},${origin.lon}")
            .addQueryParameter("destination", "${destination.lat},${destination.lon}")
            .addQueryParameter("return", "polyline,summary")
            .apply {
                if (alternatives > 0) addQueryParameter("alternatives", alternatives.toString())
                if (avoidAreas.isNotEmpty()) {
                    // bbox:west longitude,south latitude,east longitude,north latitude
                    val boxes = avoidAreas.joinToString("!") {
                        "bbox:${it.minLon},${it.minLat},${it.maxLon},${it.maxLat}"
                    }
                    addQueryParameter("avoid[areas]", boxes)
                }
                addQueryParameter("apiKey", apiKey)
            }
            .build()

        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("HERE routing HTTP ${resp.code}: ${text.take(300)}")
                }
                text
            }
        }
        return parseRoutes(body)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseRoutes(body: String): List<Route> {
            val response = json.decodeFromString<RoutesResponse>(body)
            return response.routes.mapNotNull { route ->
                if (route.sections.isEmpty()) return@mapNotNull null
                val polyline = route.sections.flatMap { FlexiblePolyline.decode(it.polyline) }
                Route(
                    polyline = polyline,
                    durationSeconds = route.sections.sumOf { it.summary.duration },
                    lengthMeters = route.sections.sumOf { it.summary.length },
                )
            }
        }
    }

    @Serializable
    internal data class RoutesResponse(val routes: List<HereRoute> = emptyList())

    @Serializable
    internal data class HereRoute(val sections: List<Section> = emptyList())

    @Serializable
    internal data class Section(val polyline: String, val summary: Summary)

    @Serializable
    internal data class Summary(val duration: Int, val length: Int)
}
