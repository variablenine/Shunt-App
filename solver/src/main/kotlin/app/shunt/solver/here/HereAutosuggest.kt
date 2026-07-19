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

/** A destination suggestion with a resolved coordinate. */
data class Suggestion(
    val title: String,
    val location: GeoPoint,
    val resultType: String,
)

/**
 * HERE Geocoding & Search v1 autosuggest — the destination search box. Same
 * API key as routing (one vendor, one key). Suggestions are biased toward
 * [at]. Response shape verified live 2026-07-19; fixture at
 * src/test/resources/fixtures/here/autosuggest-v1.json.
 *
 * Only items carrying a coordinate (places, streets, addresses) are returned;
 * category/chain query suggestions without a position are dropped, since a
 * destination the solver can route to needs a point.
 */
class HereAutosuggest(
    private val http: OkHttpClient,
    private val apiKey: () -> String,
    private val baseUrl: String = "https://autosuggest.search.hereapi.com",
) {
    suspend fun suggest(query: String, at: GeoPoint, limit: Int = 5): List<Suggestion> {
        if (query.isBlank()) return emptyList()
        val url = "$baseUrl/v1/autosuggest".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("at", "${at.lat},${at.lon}")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("apiKey", apiKey())
            .build()
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("HERE autosuggest HTTP ${resp.code}: ${text.take(300)}")
                }
                text
            }
        }
        return parse(body)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String): List<Suggestion> =
            json.decodeFromString<AutosuggestResponse>(body).items.mapNotNull { item ->
                val pos = item.position ?: return@mapNotNull null
                Suggestion(item.title, GeoPoint(pos.lat, pos.lng), item.resultType ?: "unknown")
            }
    }

    @Serializable
    internal data class AutosuggestResponse(val items: List<Item> = emptyList())

    @Serializable
    internal data class Item(
        val title: String = "",
        val resultType: String? = null,
        val position: Position? = null,
    )

    @Serializable
    internal data class Position(val lat: Double, val lng: Double)
}
