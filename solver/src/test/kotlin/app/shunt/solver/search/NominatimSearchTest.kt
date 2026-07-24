package app.shunt.solver.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NominatimSearchTest {

    @Test
    fun `a house number result becomes a readable street address`() {
        // The shape Nominatim returns for a plain address: no POI name, the
        // detail lives in `address`. This is the case Photon misses entirely.
        val body = """
            [{"lat":"39.7417547","lon":"-98.4290689","name":"","category":"place",
              "type":"house",
              "display_name":"5260, Prairie Road, Rockton, Smith County, Kansas, 66967, United States",
              "address":{"house_number":"5260","road":"Prairie Road","village":"Rockton",
                         "county":"Smith County","state":"Kansas"}}]
        """.trimIndent()
        val first = NominatimSearch.parse(body).single()
        assertEquals("5260 Prairie Road, Rockton, Kansas", first.title)
        assertEquals(39.7417547, first.location.lat, 1e-6)
        assertEquals(-98.4290689, first.location.lon, 1e-6)
    }

    @Test
    fun `a named POI keeps its name and place`() {
        val body = """
            [{"lat":"39.8758417","lon":"-98.0894523","name":"Prairie Supper Club",
              "category":"amenity","type":"restaurant",
              "display_name":"Prairie Supper Club, Highway 9, Smith County, Kansas, United States",
              "address":{"county":"Smith County","state":"Kansas"}}]
        """.trimIndent()
        val first = NominatimSearch.parse(body).single()
        assertEquals("Prairie Supper Club, Smith County, Kansas", first.title)
        assertEquals("restaurant", first.resultType)
    }

    @Test
    fun `an empty array yields no suggestions`() {
        assertTrue(NominatimSearch.parse("[]").isEmpty())
    }
}
