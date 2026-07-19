package app.shunt.solver.here

import app.shunt.core.GeoPoint
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * HERE Geocoding & Search v1 — used by the CLI to accept free-text
 * destinations. Same API key as routing. Response shape verified live
 * 2026-07-19; fixture at src/test/resources/fixtures/here/geocode-v1.json.
 */
class HereGeocoder(
    private val http: OkHttpClient,
    private val apiKey: () -> String,
    private val baseUrl: String = "https://geocode.search.hereapi.com",
) {
    suspend fun geocode(query: String): GeoPoint? {
        val url = "$baseUrl/v1/geocode".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "1")
            .addQueryParameter("apiKey", apiKey())
            .build()
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("HERE geocode HTTP ${resp.code}: ${text.take(300)}")
                }
                text
            }
        }
        return parse(body)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String): GeoPoint? {
            val response = json.decodeFromString<GeocodeResponse>(body)
            val position = response.items.firstOrNull()?.position ?: return null
            return GeoPoint(position.lat, position.lng)
        }
    }

    @Serializable
    internal data class GeocodeResponse(val items: List<Item> = emptyList())

    @Serializable
    internal data class Item(val position: Position? = null)

    @Serializable
    internal data class Position(val lat: Double, val lng: Double)
}
