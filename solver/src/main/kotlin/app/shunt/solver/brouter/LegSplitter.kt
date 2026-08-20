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
        /**
         * Places the driver asked to visit, in order.
         *
         * **A stop inside the leg window is the boundary**, in preference to
         * anything this can invent. That is not a tie-break, it is free: a stop
         * is a point the route must pass through whatever happens, so ending a
         * leg there costs nothing at all, while an invented cut bends both legs
         * to reach a place nobody asked to be.
         *
         * A stop *outside* the window changes nothing. Before it, the window
         * floor is already past it and it simply travels in the first leg —
         * [split] carries it. Beyond it, the cut lands short of it and it is
         * reached on a later leg, which is right: forcing a first leg long
         * enough to include a stop most of a day away hands back exactly the
         * two-minute plan splitting exists to prevent.
         *
         * **This is narrower than the rule it replaces**, which was "a cut is
         * never made before the first stop" and was implemented as *plan the
         * whole trip*. On a 900 km trip with a charger 100 km in, that produced
         * the unsplit plan CLAUDE.md §7.10 describes: the fastest road and
         * 43 cameras. The original report — a Supercharger that showed on the
         * map while the route ignored it — is answered by [split] carrying
         * stops into the leg they fall in, which it did not do either.
         */
        stops: List<GeoPoint> = emptyList(),
    ): Cut? {
        if (spine.size < 2) return null
        require(minLegMeters <= maxLegMeters) { "a leg cannot be both at least $minLegMeters and at most $maxLegMeters" }

        val total = lengthOf(spine)
        // Short enough to plan in one go. Splitting a trip that does not need it
        // buys nothing and spends a hard waypoint.
        if (total <= maxLegMeters) return null

        // A stop inside the window is the boundary, and the earliest such stop:
        // a shorter first leg is a faster one to plan, and the boundary is free
        // either way.
        stops.map { it to alongOf(spine, it) }
            .filter { (_, along) -> along in minLegMeters..maxLegMeters }
            .filter { (_, along) -> total - along >= MIN_TAIL_METERS }
            .minByOrNull { (_, along) -> along }
            ?.let { (stop, along) ->
                // The point is the stop itself, so the leg genuinely ends where
                // the driver asked to be. The *index* is the nearest spine
                // vertex, which is all its callers need it for — slicing the
                // spine for this leg's camera corridor and for the road onward.
                // Those are kilometre-scale questions and a stop sits within a
                // sample of the road it is on.
                return Cut(stop, nearestIndex(spine, stop), along, cameras.countWithin(stop, quietRadiusMeters))
            }

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
     * Every stop lands in the leg it falls in, ordered **along the road** rather
     * than by how far it is from the origin as the crow flies.
     *
     * **Both of those were wrong, and the first one silently deleted stops.**
     * The first leg was built as `[origin, cut]` and the remainder by dropping
     * every leading point classed as "before the cut" — so a stop on that side
     * of the boundary was in neither list. It was classed by straight-line
     * distance from the origin, which misorders whenever the road bends: a stop
     * 60 km along a road curving back toward the origin is closer to it than a
     * cut 200 km along a straight one. That is not a rare shape on a trip long
     * enough to be split.
     */
    fun split(
        points: List<GeoPoint>,
        spine: List<GeoPoint>,
        cut: Cut,
    ): Pair<List<GeoPoint>, List<GeoPoint>> {
        val intermediate = points.drop(1).dropLast(1).filter { it != cut.point }
        val before = intermediate.filter { alongOf(spine, it) < cut.alongMeters }
        val after = intermediate.filter { alongOf(spine, it) >= cut.alongMeters }
        return (listOf(points.first()) + before + cut.point) to
            (listOf(cut.point) + after + points.last())
    }

    /** The index of the vertex of [spine] nearest [p]. */
    private fun nearestIndex(spine: List<GeoPoint>, p: GeoPoint): Int {
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (i in spine.indices) {
            val d = haversineMeters(spine[i], p)
            if (d < bestDistance) { bestDistance = d; best = i }
        }
        return best
    }

    /**
     * Roughly how far along [spine] the point nearest [p] sits.
     *
     * Nearest-vertex rather than a projection: the spine is sampled every few
     * kilometres and this only has to decide which side of a leg boundary a stop
     * falls on, which is a question about tens of kilometres.
     */
    private fun alongOf(spine: List<GeoPoint>, p: GeoPoint): Double {
        var along = 0.0
        var bestAlong = 0.0
        var bestDistance = Double.MAX_VALUE
        for (i in spine.indices) {
            if (i > 0) along += haversineMeters(spine[i - 1], spine[i])
            val d = haversineMeters(spine[i], p)
            if (d < bestDistance) { bestDistance = d; bestAlong = along }
        }
        return bestAlong
    }

    private fun lengthOf(line: List<GeoPoint>): Double =
        (1 until line.size).sumOf { haversineMeters(line[it - 1], line[it]) }

    /**
     * Below this, the remainder is not worth a leg of its own — the boundary
     * would cost a constraint and save almost no planning.
     */
    private const val MIN_TAIL_METERS = 20_000.0
}
