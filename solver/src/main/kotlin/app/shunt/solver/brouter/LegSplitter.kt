package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.haversineMeters

/**
 * Where to cut a long trip so its parts can be planned one at a time.
 *
 * ## Why cut at all
 *
 * Planning cost grows faster than trip length — the search space widens, the
 * camera set grows, and past a certain distance the routes detour outside the
 * area cameras were gathered from, which costs the *whole chooser* a second
 * time out of one budget (see the widen problem in CLAUDE.md §7.8). A driver
 * setting off across three states should not sit watching a spinner for two
 * minutes, and on a phone that spinner is competing with the routing threads
 * for the same CPU.
 *
 * Cutting the trip fixes all three at once. Each leg is planned against its own
 * corridor with its own budget, the first one is short enough to hand the driver
 * quickly, and the rest are planned while the car is already moving.
 *
 * ## Why the cut point is the whole problem
 *
 * A leg boundary is not a suggestion, it is a **hard waypoint**: both legs must
 * touch it. So it costs whatever the difference is between the best route
 * *through that point* and the best route overall. Put it in the wrong place and
 * both legs are distorted to reach it, and the driver is dragged through turns —
 * or worse, past cameras — for no reason they can see. As the maintainer put it,
 * planning a trip that passes a big city and letting a leg end
 *
 * > somewhere in the middle of [the city] along the fastest line is going to end
 * > up getting dragged through a bunch of turns and stuff when it would be
 * > faster and easier to avoid.
 *
 * Exactly so, and that rules out the obvious implementation. Cutting at a fixed
 * fraction of the distance puts the boundary wherever it happens to land, and
 * the one place it must never land is a dense metro — which is precisely where a
 * long trip's fastest line goes, because that is where the roads are.
 *
 * ## The rule
 *
 * **Cut where there is nothing to avoid.** A boundary costs nothing wherever
 * every plausible route goes the same way anyway, and that is exactly a stretch
 * with no cameras near it: with nothing to dodge, the fastest road *is* the
 * fewest-cameras road, so pinning the route to a point on it constrains nothing
 * that was going to happen differently.
 *
 * So the candidates are the points on the direct road between [minLegMeters] and
 * [maxLegMeters] along, and the winner is the quietest of them — fewest cameras
 * within [quietRadiusMeters]. In the maintainer's example the cut lands in the
 * open country before or after the city, never inside it, and the city is then
 * planned as one whole leg with full freedom to route around it.
 *
 * Camera count is the proxy for "nothing to decide here", the same proxy and the
 * same reasoning as `WaypointExtractor.DENSITY_RADIUS_METERS`: ALPRs are sited
 * where the traffic and the junctions are, so *no cameras for miles* and *no
 * meaningful choice of road* are the same places. It is also the only signal
 * available without planning the very routes the cut is supposed to make cheap.
 */
object LegSplitter {

    /**
     * The longest a leg may be, and so the length above which a trip is cut at
     * all.
     *
     * Set from measured planning cost rather than from anything about roads: on
     * a real phone a 241 km trip planned in 10 s and a ~470 km one in 41 s, and
     * on the repository benchmark a 330 km trip takes about 68 s and a 583 km
     * one fails to produce a camera-free option at all. A quarter of a thousand
     * kilometres is the point where a driver is still waiting a tolerable time
     * for the part of the route they are about to drive.
     */
    const val MAX_LEG_METERS = 250_000.0

    /**
     * The shortest a leg may be, which is really a deadline in disguise.
     *
     * The legs after the first are planned while the car drives the first, so
     * this is the distance the driver must cover before the next leg is needed —
     * and it has to be far more than planning could possibly take. At 120 km/h
     * this is over an hour against a leg that plans in about a minute. The margin
     * is deliberate: arriving at a leg boundary with nothing beyond it is the one
     * genuinely bad outcome of splitting, and it should never be close.
     */
    const val MIN_LEG_METERS = 120_000.0

    /**
     * How far around a candidate cut to look for cameras.
     *
     * Generous on purpose. The question is not "is this exact point watched"
     * but "could a route through here have wanted to go somewhere else" — and a
     * camera several kilometres away is enough to pull a route off the direct
     * road. Eight kilometres is roughly the scale at which a detour around a
     * camera stops being a local manoeuvre and starts being a different road.
     */
    const val QUIET_RADIUS_METERS = 8_000.0

    /**
     * A candidate cut point: where it is, how far along, and how quiet.
     *
     * Kept as a type rather than a bare point so tests — and anyone debugging a
     * cut that landed somewhere surprising — can see *why* it was chosen.
     */
    data class Cut(
        val point: GeoPoint,
        /** Where [point] sits in the spine it was chosen from, so it can be truncated there. */
        val index: Int,
        val alongMeters: Double,
        val camerasNearby: Int,
    )

    /**
     * The best place to end the first leg of [spine], or null when the trip is
     * short enough to plan whole.
     *
     * [spine] is the direct road, already sampled — this does no routing and no
     * network access, so it costs nothing next to the passes it saves.
     */
    fun cut(
        spine: List<GeoPoint>,
        cameras: CameraIndex,
        minLegMeters: Double = MIN_LEG_METERS,
        maxLegMeters: Double = MAX_LEG_METERS,
        quietRadiusMeters: Double = QUIET_RADIUS_METERS,
    ): Cut? {
        if (spine.size < 2) return null
        require(minLegMeters <= maxLegMeters) { "a leg cannot be both at least $minLegMeters and at most $maxLegMeters" }

        val total = lengthOf(spine)
        // Short enough to plan in one go. Splitting a trip that does not need it
        // buys nothing and spends a hard waypoint.
        if (total <= maxLegMeters) return null

        var best: Cut? = null
        var along = 0.0
        for (i in spine.indices) {
            if (i > 0) along += haversineMeters(spine[i - 1], spine[i])
            if (along < minLegMeters) continue
            if (along > maxLegMeters) break
            // What is left after this cut must still be worth driving to; a cut
            // metres from the destination is a boundary for nothing.
            if (total - along < MIN_TAIL_METERS) break

            val nearby = cameras.countWithin(spine[i], quietRadiusMeters)
            val candidate = Cut(spine[i], i, along, nearby)
            // Strictly fewer cameras wins; an equal count further along wins too,
            // because a longer first leg means one fewer boundary over the trip
            // and every boundary is a constraint the route did not ask for.
            if (best == null || nearby <= best.camerasNearby) best = candidate
        }
        return best
    }

    /**
     * [points] split at [cut]: the leg to plan now, and what is left to plan
     * after it.
     *
     * The cut is inserted rather than replacing anything, so a trip with the
     * driver's own stops in it needs no special handling — a cut falling inside
     * the run up to a stop simply produces `[origin, cut]` and
     * `[cut, stop, …, destination]`, and every stop survives in order.
     */
    fun split(points: List<GeoPoint>, cut: GeoPoint): Pair<List<GeoPoint>, List<GeoPoint>> {
        val ahead = points.drop(1).dropWhile { isBefore(it, points, cut) }
        return listOf(points.first(), cut) to (listOf(cut) + ahead)
    }

    /**
     * Whether [stop] comes before [cut] along [points].
     *
     * Compared by distance along the straight chain rather than along the road,
     * which is coarse but only has to order a handful of points that are tens of
     * kilometres apart.
     */
    private fun isBefore(stop: GeoPoint, points: List<GeoPoint>, cut: GeoPoint): Boolean {
        val origin = points.first()
        return haversineMeters(origin, stop) < haversineMeters(origin, cut)
    }

    private fun lengthOf(line: List<GeoPoint>): Double =
        (1 until line.size).sumOf { haversineMeters(line[it - 1], line[it]) }

    /**
     * Below this, the remainder is not worth a leg of its own — the boundary
     * would cost a constraint and save almost no planning.
     */
    private const val MIN_TAIL_METERS = 20_000.0
}
