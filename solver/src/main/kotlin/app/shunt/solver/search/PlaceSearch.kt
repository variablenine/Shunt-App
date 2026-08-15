package app.shunt.solver.search

import app.shunt.core.GeoPoint

/**
 * Destination search across the two keyless OpenStreetMap geocoders, chosen to
 * play to each one's strengths and respect each one's usage policy.
 *
 * [primary] (Photon) answers the typeahead: it is built for autocomplete and is
 * happy to be called per keystroke. Its public index, however, lags OSM and
 * omits many POIs and house numbers — a place you can see on openstreetmap.org
 * may simply not be there.
 *
 * [fallback] (Nominatim) is the official OSM geocoder with a far fresher and
 * more complete index, but its public instance asks not to be used for
 * autocomplete and caps callers at ~1 request/second. So it is consulted **only
 * when the primary finds nothing** — at most one request per settled query,
 * which is exactly the "I typed a real address and got no results" case.
 *
 * The result: fast local typeahead, and no more dead ends for addresses and
 * businesses that genuinely exist in OpenStreetMap.
 */
class PlaceSearch(
    private val primary: suspend (String, GeoPoint) -> List<Suggestion>,
    private val fallback: suspend (String, GeoPoint) -> List<Suggestion>,
    /**
     * Places of a given kind near a point — the [PlaceCategories] path. Absent,
     * behaviour is exactly as it was: everything is a name search.
     */
    private val nearby: (suspend (List<String>, GeoPoint) -> List<Suggestion>)? = null,
) {
    suspend fun suggest(query: String, at: GeoPoint): List<Suggestion> {
        if (query.isBlank()) return emptyList()

        // A query that names a *kind* of place is a different question, and the
        // name geocoders answer it with nonsense — "coffee" finds Coffee County,
        // Alabama. Answer it by tag instead, and lead with it: someone who typed
        // "coffee" wants the nearest cafe, not somewhere called Coffee.
        val category = PlaceCategories.of(query)
        val byCategory = nearby
        if (category != null && byCategory != null) {
            val found = runCatching { byCategory(category.tags, at) }.getOrDefault(emptyList())
            // Falling through on an empty result is deliberate: out in open
            // country there may genuinely be no cafe within reach, and a name
            // search is a better answer than a blank screen.
            if (found.isNotEmpty()) return found.take(MAX_RESULTS)
        }

        val primaryResults = runCatching { primary(query, at) }
        val hits = primaryResults.getOrDefault(emptyList())

        // A *non-empty* result set is not the same as a match. Asked for a place
        // it doesn't have, the typeahead index happily returns fuzzy near-misses
        // (search a named diner, get four unrelated diners), so
        // "results.isNotEmpty()" would wrongly declare success and never consult
        // the index that actually has the place.
        if (hits.any { it.matches(query) }) return hits

        val fallbackResults = runCatching { fallback(query, at) }
        val rescued = fallbackResults.getOrElse {
            // The fallback failed too: surface the primary's failure (if it had
            // one) so the UI can tell "offline" from "no such place".
            primaryResults.getOrThrow()
            emptyList()
        }
        // Authoritative results lead; keep the fuzzy ones after as alternatives.
        return if (rescued.isEmpty()) hits
        else (rescued + hits.filterNot { h -> rescued.any { it.title == h.title } }).take(MAX_RESULTS)
    }

    private companion object {
        const val MAX_RESULTS = 10

        /** Query words worth matching on — skips "of", "st", "el", … */
        const val MIN_TOKEN_LENGTH = 3

        /**
         * True when [Suggestion.title] plausibly answers [query]: every
         * significant word of the query appears in it. Deliberately strict —
         * a miss only costs one fallback lookup, while a false match strands the
         * user on "no such place" for somewhere that really exists.
         */
        fun Suggestion.matches(query: String): Boolean {
            val tokens = query.lowercase()
                .split(*" ,.-/#".toCharArray())
                .filter { it.length >= MIN_TOKEN_LENGTH }
            if (tokens.isEmpty()) return true // nothing substantive to check
            val haystack = title.lowercase()
            return tokens.all { it in haystack }
        }
    }
}
