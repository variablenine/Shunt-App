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

    // ---- Asking for a kind of place, not a name ---------------------------
    //
    // Measured against the public geocoders: "coffee" returns Coffee County,
    // Alabama; "grocery" returns shops called "Grocery" in Dubai. They match
    // names, and no ranking fixes that — it is the wrong question.

    private val cafe = Suggestion("Nearby Cafe", GeoPoint(39.01, -98.01), "cafe")

    @Test
    fun `a category word is answered by kind, near the driver`() = runTest {
        var nameSearched = false
        var askedFor: List<String>? = null
        val search = PlaceSearch(
            primary = { _, _ -> nameSearched = true; listOf(hit) },
            fallback = { _, _ -> emptyList() },
            nearby = { tags, _ -> askedFor = tags; listOf(cafe) },
        )

        assertEquals(listOf(cafe), search.suggest("coffee", at))
        assertEquals(listOf("amenity:cafe"), askedFor, "must ask for cafes, not for the word")
        assertTrue(!nameSearched, "a name search for \"coffee\" is what produced Coffee County")
    }

    @Test
    fun `a place that merely contains a category word is still a name search`() = runTest {
        // "Bank of America Stadium" is not a request for the nearest cash
        // machine, and "Food Lion" is a supermarket chain. Substring matching
        // here would hijack real searches, so only the whole query counts.
        var nearbyCalled = false
        val search = PlaceSearch(
            primary = { _, _ -> listOf(hit) },
            fallback = { _, _ -> emptyList() },
            nearby = { _, _ -> nearbyCalled = true; listOf(cafe) },
        )
        search.suggest("bank of america stadium", at)
        search.suggest("food lion", at)
        assertTrue(!nearbyCalled, "category search hijacked a search for a named place")
    }

    @Test
    fun `nothing of that kind nearby falls back to searching the name`() = runTest {
        // Out in open country there may genuinely be no cafe within reach, and
        // a name search beats a blank screen.
        val search = PlaceSearch(
            primary = { _, _ -> listOf(hit) },
            fallback = { _, _ -> emptyList() },
            nearby = { _, _ -> emptyList() },
        )
        assertEquals(listOf(hit), search.suggest("coffee", at))
    }

    @Test
    fun `a failing category lookup does not lose the search`() = runTest {
        val search = PlaceSearch(
            primary = { _, _ -> listOf(hit) },
            fallback = { _, _ -> emptyList() },
            nearby = { _, _ -> error("offline") },
        )
        assertEquals(listOf(hit), search.suggest("coffee", at))
    }

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

    // ---- A commercial geocoder the user supplied a key for ----------------
    //
    // Opt-in and default-off. Measured against small-town POIs, the keyless pair
    // find and correctly rank anything that is in OSM — so what is missing is
    // missing from the data, and the only fix is a different dataset. Someone
    // who goes and gets a key has decided that trade for themselves.

    private val fromGoogle = Suggestion("Birch Street Hardware", GeoPoint(39.02, -98.02), "12 Birch Street")

    @Test
    fun `a configured commercial geocoder answers first`() = runTest {
        // Consulting the keyless pair first would reproduce exactly the problem
        // the key was obtained to solve.
        var keylessAsked = false
        val search = PlaceSearch(
            primary = { _, _ -> keylessAsked = true; listOf(hit) },
            fallback = { _, _ -> emptyList() },
            preferred = { _, _ -> listOf(fromGoogle) },
        )

        assertEquals(listOf(fromGoogle), search.suggest("Birch Street Hardware", at))
        assertTrue(!keylessAsked, "the keyless geocoders were consulted despite a key being configured")
    }

    @Test
    fun `an unconfigured or failing commercial geocoder falls back to keyless`() = runTest {
        // A bad key, an exhausted quota, or no key at all must degrade to the
        // behaviour the app has always had — never to a broken search. Keeping
        // the keyless path underneath is the whole reason it is still there.
        val failing = PlaceSearch(
            primary = { _, _ -> listOf(hit) },
            fallback = { _, _ -> emptyList() },
            preferred = { _, _ -> error("quota exceeded") },
        )
        assertEquals(listOf(hit), failing.suggest("Civic Center", at))

        val empty = PlaceSearch(
            primary = { _, _ -> listOf(hit) },
            fallback = { _, _ -> emptyList() },
            preferred = { _, _ -> emptyList() },
        )
        assertEquals(listOf(hit), empty.suggest("Civic Center", at))
    }

    @Test
    fun `a category word still goes by tag, not to the commercial geocoder`() = runTest {
        // "coffee" is a question about kinds of place, and the nearby-by-tag
        // path answers it in about a second without spending a paid lookup.
        var commercialAsked = false
        val search = PlaceSearch(
            primary = { _, _ -> emptyList() },
            fallback = { _, _ -> emptyList() },
            nearby = { _, _ -> listOf(cafe) },
            preferred = { _, _ -> commercialAsked = true; listOf(fromGoogle) },
        )

        assertEquals(listOf(cafe), search.suggest("coffee", at))
        assertTrue(!commercialAsked, "a category search spent a paid lookup it did not need")
    }
}
