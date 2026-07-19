package app.shunt.solver.here

import app.shunt.core.GeoPoint
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class HereAutosuggestTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/here/$name")!!.readBytes().decodeToString()

    @Test
    fun `parses live autosuggest fixture`() {
        val suggestions = HereAutosuggest.parse(fixture("autosuggest-v1.json"))
        assertEquals(5, suggestions.size)
        assertEquals("Lambeau Field", suggestions[0].title)
        assertEquals(GeoPoint(44.50099, -88.0613), suggestions[0].location)
        assertTrue(suggestions.all { it.title.isNotBlank() })
    }

    @Test
    fun `drops items without a position`() {
        val body = """
            {"items":[
              {"title":"Coffee","resultType":"categoryQuery"},
              {"title":"Real Place","resultType":"place","position":{"lat":44.5,"lng":-88.0}}
            ]}
        """.trimIndent()
        val suggestions = HereAutosuggest.parse(body)
        assertEquals(listOf("Real Place"), suggestions.map { it.title })
    }

    @Test
    fun `blank query short-circuits without a request`() = runTest {
        val client = HereAutosuggest(OkHttpClient(), "k", server.url("/").toString().trimEnd('/'))
        assertEquals(emptyList(), client.suggest("   ", GeoPoint(44.5, -88.0)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `request carries query and bias point`() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        HereAutosuggest(OkHttpClient(), "test-key", server.url("/").toString().trimEnd('/'))
            .suggest("Lambeau", GeoPoint(44.5, -88.0), limit = 7)
        val url = server.takeRequest().requestUrl!!
        assertEquals("Lambeau", url.queryParameter("q"))
        assertEquals("44.5,-88.0", url.queryParameter("at"))
        assertEquals("7", url.queryParameter("limit"))
        assertEquals("test-key", url.queryParameter("apiKey"))
    }
}
