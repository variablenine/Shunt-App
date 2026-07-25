package app.shunt.solver.search

import app.shunt.core.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PlaceSearchTest {

    private val at = GeoPoint(39.0, -98.0)
    private val hit = Suggestion("Civic Center", GeoPoint(39.1, -98.1), "place")

    @Test
    fun `the fast typeahead index answers when it has results`() = runTest {
        var fallbackCalled = false
        val search = PlaceSearch(
            primary = { _, _ -> listOf(hit) },
            fallback = { _, _ -> fallbackCalled = true; emptyList() },
        )
        assertEquals(listOf(hit), search.suggest("civic", at))
        assertTrue(!fallbackCalled, "must not spend a rate-limited call when the primary answered")
    }

    @Test
    fun `an empty typeahead falls back to the authoritative geocoder`() = runTest {
        // The real bug: addresses and POIs that exist in OSM are missing from
        // the typeahead index, so the app said "no matching places" for a real
        // address. The fallback must rescue exactly this case.
        val search = PlaceSearch(
            primary = { _, _ -> emptyList() },
            fallback = { _, _ -> listOf(hit) },
        )
        assertEquals(listOf(hit), search.suggest("5260 some road", at))
    }

    @Test
    fun `fuzzy near-misses do not count as a match and still trigger the fallback`() = runTest {
        // The real bug: asked for a named place it doesn't have, the typeahead
        // index returns unrelated results that merely share a common word. A
        // non-empty list is not a match, so the fallback must still run.
        val nearMisses = listOf(
            Suggestion("Lakeside Grill", GeoPoint(39.2, -98.2), "restaurant"),
            Suggestion("Riverside Grill", GeoPoint(39.3, -98.3), "restaurant"),
        )
        val real = Suggestion("Prairie View Grill", GeoPoint(39.05, -98.05), "restaurant")
        val search = PlaceSearch(
            primary = { _, _ -> nearMisses },
            fallback = { _, _ -> listOf(real) },
        )
        val results = search.suggest("Prairie View Grill", at)
        assertEquals(real, results.first(), "the real match must lead")
        assertTrue(results.containsAll(nearMisses), "near-misses stay as alternatives")
    }

    @Test
    fun `a genuine typeahead match spends no fallback call`() = runTest {
        var fallbackCalled = false
        val search = PlaceSearch(
            primary = { _, _ -> listOf(Suggestion("Kwik Trip, Springfield", GeoPoint(39.1, -98.1), "fuel")) },
            fallback = { _, _ -> fallbackCalled = true; emptyList() },
        )
        val results = search.suggest("Kwik Trip", at)
        assertEquals(1, results.size)
        assertTrue(!fallbackCalled, "a real match must not spend the rate-limited call")
    }

    @Test
    fun `when the fallback finds nothing the typeahead guesses are still offered`() = runTest {
        val guesses = listOf(Suggestion("Something Else", GeoPoint(39.2, -98.2), "place"))
        val search = PlaceSearch(
            primary = { _, _ -> guesses },
            fallback = { _, _ -> emptyList() },
        )
        assertEquals(guesses, search.suggest("totally unmatched query", at))
    }

    @Test
    fun `a failing typeahead still gets rescued by the fallback`() = runTest {
        val search = PlaceSearch(
            primary = { _, _ -> throw java.io.IOException("photon down") },
            fallback = { _, _ -> listOf(hit) },
        )
        assertEquals(listOf(hit), search.suggest("civic", at))
    }

    @Test
    fun `both sources failing surfaces the error rather than a false empty`() = runTest {
        // "No matching places" must not be shown when we simply couldn't reach
        // anything — the UI distinguishes offline from genuinely-absent.
        val search = PlaceSearch(
            primary = { _, _ -> throw java.io.IOException("photon down") },
            fallback = { _, _ -> throw java.io.IOException("nominatim down") },
        )
        assertFailsWith<java.io.IOException> { search.suggest("civic", at) }
    }

    @Test
    fun `a blank query never reaches the network`() = runTest {
        val search = PlaceSearch(
            primary = { _, _ -> error("must not be called") },
            fallback = { _, _ -> error("must not be called") },
        )
        assertTrue(search.suggest("   ", at).isEmpty())
    }
}
