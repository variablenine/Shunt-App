package app.shunt.solver.search

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.haversineMeters

/**
 * Ordering search results the way someone planning a drive wants them.
 *
 * Shared by both geocoders, because both have the same failure: they rank by
 * how *notable* a place is, and a driving app wants the one you can reach. It
 * lived in `PhotonSearch` until Nominatim was found to need it just as badly.
 */

/**
 * How near a result must be to count as "local" and get promoted above far-away
 * namesakes (~75 mi — a day-trip radius). Both geocoders rank by OSM
 * "importance", so a famous distant landmark outranks a nearby place of the same
 * name; a driving app wants the reachable one first.
 */
const val LOCAL_RADIUS_METERS = 120_000.0

/**
 * Results this much closer than another are ordered by distance; within a
 * bucket, the geocoder's own relevance order is kept. Sorting strictly by
 * distance would put a random hut ahead of the town you actually typed.
 */
const val PROXIMITY_BUCKET_METERS = 25_000.0

/**
 * Anything within [LOCAL_RADIUS_METERS] first, then roughly nearest-first, with
 * the geocoder's relevance breaking ties inside each distance band.
 *
 * Both keys matter. The tier alone left a pile of far-flung namesakes in
 * importance order, so a search could fill up with places nowhere near the
 * driver; distance alone would throw away relevance entirely.
 */
fun rankByProximity(results: List<Suggestion>, at: GeoPoint): List<Suggestion> =
    results.withIndex()
        .sortedWith(
            compareBy<IndexedValue<Suggestion>> {
                if (haversineMeters(at, it.value.location) <= LOCAL_RADIUS_METERS) 0 else 1
            }
                .thenBy { (haversineMeters(at, it.value.location) / PROXIMITY_BUCKET_METERS).toInt() }
                .thenBy { it.index },
        )
        .map { it.value }
