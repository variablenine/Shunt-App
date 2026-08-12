package app.shunt.solver.search

/**
 * Turns what a driver types into the OpenStreetMap tags that mean it.
 *
 * **The gap this closes.** Both keyless geocoders match *names*, not kinds of
 * place. Measured against the public instances, from a point in Kansas:
 *
 * | typed | what came back |
 * |---|---|
 * | `coffee` | Coffee County, Alabama — 1,490 km |
 * | `gas station` | a farm track called "Gas Station (Not)", then filling stations in South Korea |
 * | `grocery` | shops literally named "Grocery", in Dubai |
 *
 * None of that is a ranking bug to be tuned away. A text geocoder asked for
 * "coffee" is doing exactly what it is built to do — find things *called*
 * coffee — and what the driver wanted was a cafe, near them, now. The two are
 * different questions and only one of them was ever being asked.
 *
 * So a query that names a *kind* of place is answered by tag, through Photon's
 * reverse endpoint, which takes `osm_tag` filters and a radius and needs no
 * query text at all. Same host, same keyless terms, about a second. Asked the
 * right way it returns real supermarkets 2.6 km off instead of Dubai.
 *
 * **Kept deliberately small.** Every entry is a word a driver would actually
 * type mid-trip, and each maps to tags that are well populated in OSM. A long
 * tail of rare synonyms would mostly add ways to match by accident — and a
 * false category match is worse than none, because it silently replaces the
 * search someone meant with one they didn't.
 */
object PlaceCategories {

    /** OSM `key=value` pairs, in Photon's `key:value` filter syntax. */
    data class Category(val label: String, val tags: List<String>)

    private val byPhrase: Map<String, Category> = buildMap {
        fun add(category: Category, vararg phrases: String) {
            phrases.forEach { put(it, category) }
        }

        add(
            Category("Coffee", listOf("amenity:cafe")),
            "coffee", "cafe", "café", "coffee shop", "coffee shops", "cafes", "espresso",
        )
        add(
            Category("Fuel", listOf("amenity:fuel")),
            "gas", "gas station", "gas stations", "fuel", "petrol", "petrol station", "diesel",
        )
        add(
            // Charging is its own thing in this app, but someone will still type it.
            Category("Charging", listOf("amenity:charging_station")),
            "charger", "chargers", "charging", "charging station", "ev charger", "supercharger",
        )
        add(
            Category("Food", listOf("amenity:restaurant", "amenity:fast_food")),
            "food", "restaurant", "restaurants", "eat", "lunch", "dinner", "fast food", "takeaway",
        )
        add(
            Category("Groceries", listOf("shop:supermarket", "shop:convenience")),
            "grocery", "groceries", "supermarket", "supermarkets", "convenience store",
        )
        add(
            Category("Restrooms", listOf("amenity:toilets")),
            "restroom", "restrooms", "toilet", "toilets", "bathroom", "washroom",
        )
        add(
            Category("Parking", listOf("amenity:parking")),
            "parking", "car park", "parking lot",
        )
        add(
            Category("Pharmacy", listOf("amenity:pharmacy")),
            "pharmacy", "pharmacies", "chemist", "drugstore",
        )
        add(
            Category("Hotels", listOf("tourism:hotel", "tourism:motel")),
            "hotel", "hotels", "motel", "motels", "lodging",
        )
        add(
            Category("Cash", listOf("amenity:atm", "amenity:bank")),
            "atm", "cash machine", "bank",
        )
        add(
            Category("Hospital", listOf("amenity:hospital", "amenity:clinic")),
            "hospital", "hospitals", "emergency room", "urgent care", "clinic",
        )
        add(
            Category("Rest area", listOf("highway:rest_area", "highway:services")),
            "rest area", "rest stop", "services", "service area",
        )
    }

    /**
     * The category [query] names, or null when it names a place instead.
     *
     * Whole-query matching only. Substring matching would hijack real searches —
     * "Bank of America Stadium" is not a request for the nearest cash machine,
     * and "Food Lion" is a supermarket chain, not a plea for lunch. When someone
     * types a name, the name search must be what answers.
     */
    fun of(query: String): Category? = byPhrase[query.trim().lowercase()]

    /** Every phrase understood, for tests and for anything that wants to list them. */
    val phrases: Set<String> get() = byPhrase.keys
}
