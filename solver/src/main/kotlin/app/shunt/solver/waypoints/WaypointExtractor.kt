package app.shunt.solver.waypoints

import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.CameraIndex
import app.shunt.solver.brouter.CameraVision
import app.shunt.solver.geo.haversineMeters
import app.shunt.solver.geo.pointAtAlong
import app.shunt.solver.geo.turnsAlong
import app.shunt.solver.geo.PolylineIndex
import app.shunt.solver.geo.pointToPolyline
import app.shunt.solver.geo.pointToSegmentMeters

/**
 * Picks the intermediate points to pin the chosen route against the vehicle's
 * own routing.
 *
 * The vehicle is not given our polyline — only a short chain of waypoints — and
 * it routes *itself* between them. So every waypoint we omit is a stretch where
 * the car may take its own line. For a camera-avoiding route that is the whole
 * ballgame: a car that cuts the corner between two sparse waypoints can rejoin
 * the fast road and drive straight past the camera the detour existed to avoid,
 * while the app still shows the route as camera-free.
 *
 * So selection happens in two passes:
 *  1. **Shape** — one waypoint at the most divergent point of each stretch where
 *     the chosen route leaves the fastest one. This is what makes the car take
 *     the detour at all.
 *  2. **Safety** — for each consecutive pair of pins, ask whether a car cutting
 *     straight between them would enter a camera's field of view that our own
 *     route stays out of. Where it would, insert the route point that pulls
 *     hardest away from that shortcut, and re-check. This spends the limited
 *     waypoint budget where it actually prevents exposure.
 *
 * There is no fixed budget: a route gets as many pins as it needs. See
 * [NO_LIMIT] for why a cap did more harm than good, and what bounds the count
 * instead. Callers may still pass an explicit cap.
 */
object WaypointExtractor {
    /**
     * No fixed budget: a route gets the pins it needs and no more.
     *
     * There used to be a cap of 8, on the theory that the vehicle takes a
     * bounded chain. It doesn't work that way — a car that requires signed
     * commands accepts one destination at a time, so pins go out one by one as
     * the drive progresses and the only cost is a rate-limited command each,
     * spread over hours. Meanwhile a cap does real harm: it is spent on
     * whichever stretches happen to be checked first and starves the rest of
     * the trip, so a long route ends up unpinned exactly where it matters.
     *
     * What bounds the count instead is the route itself. A pin is always an
     * actual point on the line, every insertion strictly shortens the stretch
     * it splits, and a stretch too short to hold one is left alone — so the
     * process runs out of places to put pins long before it runs out of route.
     */
    const val NO_LIMIT = Int.MAX_VALUE

    /** Chosen-route points farther than this from the fastest route count as divergent. */
    const val DIVERGENCE_THRESHOLD_METERS = 50.0

    /**
     * How far apart pins must be on open road.
     *
     * The reason is not tidiness, it is that the drive monitor advances to the
     * next pin once the car is within `max(150 m, speed × 18 s)` of the current
     * one — so two pins closer together than that lead distance are not two
     * constraints, they are one, because the second is advanced past before the
     * car ever aims at it. At highway speed that lead is 500-550 m.
     *
     * **Two rules bracket this from opposite sides, and it must satisfy both:**
     *
     * - No smaller than the monitor's lead, or pins stop being constraints.
     * - No *larger* than [WaypointRefiner.PAST_FORK_METERS], or the refiner's
     *   own pins get discarded — those sit exactly that far past a fork, so a
     *   wider spacing throws away the most carefully placed pin on the route.
     *   At 800 m against a 250 m fork distance, that is precisely what happened.
     *
     * So it equals the fork distance at each end of the density scale. **If the
     * monitor's numbers change, all four move together** (`DriveModel`'s
     * `waypointLeadSeconds` / `waypointLeadMinMeters`); a test in `:app` holds
     * the relationship, because only that module can see both sides of it.
     *
     * This is the *loose* end of the scale. See [DENSE_PIN_SPACING_METERS].
     */
    const val MIN_PIN_SPACING_METERS = 600.0

    /**
     * How far apart pins may be where the road network is dense.
     *
     * The old rule was one number everywhere, justified by "the car cannot
     * meaningfully deviate inside a few hundred metres". On a highway with
     * junctions kilometres apart that is true. In a city grid it is plainly
     * false — there is a turn every block — and it was doing real harm, since
     * at 800 m it discarded the refiner's own pins for sitting too close to the
     * previous one.
     *
     * Matches [WaypointRefiner.DENSE_PAST_FORK_METERS], and for the same reason
     * as the open-road pair: city speed puts the monitor's lead at 160-240 m, so
     * this is that plus margin, and a pin the refiner placed past a fork is
     * never then thrown away for being where it was deliberately put.
     *
     * The cost of more pins is one rate-limited command each as the drive
     * passes them, which is exactly where a city is at its most forgiving:
     * reception is better there than anywhere else on the trip.
     */
    const val DENSE_PIN_SPACING_METERS = 250.0

    /**
     * How far around a pin to look when judging how built-up it is.
     *
     * Camera count is the proxy, because it is what this app can actually see —
     * a polyline says nothing about the side streets leading off it. It is a
     * good proxy for the thing that matters: ALPRs go where the traffic and the
     * junctions are, so "many cameras within a mile" and "many ways for the car
     * to surprise us" are the same places. About a mile.
     */
    const val DENSITY_RADIUS_METERS = 1_500.0

    /**
     * Cameras within [DENSITY_RADIUS_METERS] at which spacing is fully tightened.
     *
     * Between zero and this, spacing slides between the two numbers above rather
     * than switching, so a route entering a metro tightens up gradually instead
     * of at one arbitrary line.
     */
    const val DENSE_CAMERA_COUNT = 12

    /**
     * How sharply the route must bend to count as a turn worth pinning.
     *
     * Well above the wander of a polyline following a curving road, and below a
     * normal junction turn. Too low and a sweeping highway bend becomes a
     * "decision"; too high and a slip road onto a parallel route reads as
     * straight on, which is exactly the case where the car goes its own way.
     */
    const val TURN_DEGREES = 35.0

    /** Distance either side of a point used to measure how sharply it bends. */
    const val TURN_SPAN_METERS = 40.0

    /**
     * How close an avoided camera has to come to the route before the route is
     * pinned either side of it.
     *
     * Reported from a real plan: a route that dodged a camera by taking the
     * next street over, with no pin anywhere near the squeeze — *"a route that
     * the car could easily still route through a camera, I don't trust that."*
     * Nothing was wrong with the route; what was missing is that the stretch
     * was held only by **prediction**, and the same objection applies here as
     * at a turn. Every existing check asks BRouter whether the car would stray:
     * the chord test in [pinAgainstShortcuts] and the leg routing in
     * [WaypointRefiner]. Where BRouter's model of Tesla's router is wrong, the
     * leg looks clean and the car drives past the camera anyway.
     *
     * Six hundred metres is deliberately about one open-road pin spacing. A
     * camera further off our line than that needs the car to deviate half a
     * kilometre to reach, which is a different and larger failure — the one
     * turn pins and the refiner exist for. Nearer than that and the camera is
     * a street away, which is precisely the geometry in the report.
     */
    const val CAMERA_GUARD_RADIUS_METERS = 600.0

    /**
     * Sampling used to find where the route passes closest to a camera.
     *
     * The answer positions a bracket hundreds of metres wide, so this is as
     * fine as it needs to be, and a cross-state route is walked once per
     * option.
     */
    const val GUARD_SAMPLE_METERS = 25.0

    fun extract(
        chosen: List<GeoPoint>,
        fastest: List<GeoPoint>,
        avoid: List<CameraVision> = emptyList(),
        maxWaypoints: Int = NO_LIMIT,
        thresholdMeters: Double = DIVERGENCE_THRESHOLD_METERS,
        /** Shared grid over [avoid]; building one per call would undo the point of it. */
        index: CameraIndex = CameraIndex(avoid),
        /**
         * Stop closing shortcuts and hand back the pins found so far.
         *
         * The same reasoning as [WaypointRefiner]'s budget: a pin only *steers* a
         * car that routes itself, so a route with fewer of them is still the
         * route we planned, still labelled with the cameras it passes, and still
         * warned about on approach. This phase used to have no bound at all, and
         * it shares the refinement budget rather than minting one of its own —
         * they are two halves of deciding the same pins.
         */
        outOfTime: () -> Boolean = { false },
        /**
         * The cameras this route stays clear of, indexed. Computing it means
         * sweeping the whole line against the camera grid, and the planner has
         * already done that — passing it in stops every option paying for it
         * twice. Omitted, it is derived here exactly as it used to be.
         */
        avoided: CameraIndex = avoidedIndex(chosen, avoid, index),
    ): List<GeoPoint> {
        if (chosen.size < 2 || fastest.size < 2) return emptyList()

        // Built once: both the shape pass and the camera guards ask the same
        // question of it — how far is this point from the road the car would
        // have taken anyway.
        val fastestIndex = PolylineIndex(fastest)
        val shape = shapeIndices(chosen, fastestIndex, maxWaypoints, thresholdMeters)
        val pinned = pinAgainstShortcuts(chosen, shape, maxWaypoints, avoided, outOfTime)
        // Merged in route order — spacing walks the list in order, so an
        // out-of-order pin would be measured against the wrong neighbour.
        val along = DoubleArray(chosen.size)
        for (i in 1 until chosen.size) along[i] = along[i - 1] + haversineMeters(chosen[i - 1], chosen[i])
        val merged = (
            turnPins(chosen, index) +
                cameraGuardPins(chosen, avoided, index, fastestIndex, thresholdMeters) +
                pinned.map { along[it] }
            ).sorted()
        val spaced = spaceOut(merged.mapNotNull { pointAtAlong(chosen, it) }.distinct(), density = index)
        // An explicit cap stays a cap. Production passes NO_LIMIT — see that
        // constant for why — but a caller that asks for a ceiling gets one.
        return if (maxWaypoints == NO_LIMIT) spaced else spaced.take(maxWaypoints)
    }

    /**
     * A pin just past every turn the route takes.
     *
     * **This is the belt to the refiner's braces, and it exists because of what
     * the refiner cannot know.** The refiner decides a leg is safe by routing it
     * the way the car would — using BRouter — and seeing that the car stays on
     * our line. That is only ever as true as BRouter's model of Tesla's router.
     * Where the two disagree, a leg that looked fine is one the car drives its
     * own way, and nothing on the route says otherwise.
     *
     * A turn is where that disagreement can actually cost something. Carrying
     * straight on is never a wrong answer to "the route goes straight on"; it is
     * only at a junction that the car has a choice to get wrong, and the further
     * away the next waypoint is, the more freedom it has in making it. So every
     * turn gets a pin whether or not BRouter thinks one is needed, which turns
     * the route from *predicted* to *instructed* at exactly the points where the
     * prediction could be wrong.
     *
     * The cost is a rate-limited command each as the drive passes them, spread
     * over hours — cheap against the car quietly taking a road we did not plan.
     *
     * Spacing still applies afterwards, so a dense grid does not produce pins
     * closer together than the drive monitor can actually use them.
     */
    internal fun turnPins(chosen: List<GeoPoint>, density: CameraIndex): List<Double> =
        turnsAlong(chosen, TURN_DEGREES, TURN_SPAN_METERS).mapNotNull { turnAlong ->
            // Past the turn, for the same reason the refiner places its pins
            // there: a pin before the turn is one the monitor abandons while the
            // car is still short of the junction.
            val at = pointAtAlong(chosen, turnAlong) ?: return@mapNotNull null
            turnAlong + WaypointRefiner.pastForkAt(at, density)
        }

    /**
     * A pin either side of every avoided camera the route squeezes past.
     *
     * Same argument as [turnPins], applied to the other place where being wrong
     * is expensive. The refiner and the shortcut check both decide a stretch is
     * safe by asking BRouter what the car would do; near a camera we are
     * deliberately dodging, "BRouter thinks the car stays on our road" is the
     * one answer that is not good enough, because the cost of it being wrong is
     * the exposure the whole route exists to prevent.
     *
     * So the stretch is *instructed* instead. The near pin puts the car on our
     * line before the camera's neighbourhood; the far one holds it there past
     * the camera rather than letting it rejoin immediately. Both are placed at
     * the density-aware fork distance, which is the smallest offset the drive
     * monitor can still treat as a separate constraint.
     *
     * These are protected from pruning ([protectedPins]) for the same reason
     * they exist: pruning drops a pin on BRouter's word.
     *
     * **Only where the route has left the fastest one**, which is what keeps
     * this from flooding a city. A straight run through a metro passes hundreds
     * of cameras a few streets over that it never goes near, and bracketing
     * every one of them would put a pin every couple of hundred metres on a
     * road with no decision on it — dozens of pushes to the car for nothing.
     * Where the route *is* the fastest route the car has no reason to leave it,
     * exactly as carrying straight on is never a wrong answer to a route that
     * goes straight on. The camera worth guarding is the one our line detoured
     * around, and on that stretch the two lines are apart by construction.
     */
    internal fun cameraGuardPins(
        chosen: List<GeoPoint>,
        avoided: CameraIndex,
        density: CameraIndex,
        fastest: PolylineIndex?,
        thresholdMeters: Double = DIVERGENCE_THRESHOLD_METERS,
    ): List<Double> =
        avoided.closestApproachAlong(chosen, CAMERA_GUARD_RADIUS_METERS, GUARD_SAMPLE_METERS)
            .values
            .flatMap { at ->
                val p = pointAtAlong(chosen, at) ?: return@flatMap emptyList<Double>()
                if (fastest != null && fastest.distanceMeters(p) <= thresholdMeters) {
                    return@flatMap emptyList<Double>()
                }
                val guard = WaypointRefiner.pastForkAt(p, density)
                listOf(at - guard, at + guard)
            }
            .filter { it > 0.0 }

    /**
     * The cameras [chosen] stays clear of, indexed.
     *
     * Only these are worth pinning against — a camera the route knowingly
     * passes is already reported to the user, and no waypoint changes that.
     */
    fun avoidedIndex(
        chosen: List<GeoPoint>,
        avoid: List<CameraVision>,
        index: CameraIndex = CameraIndex(avoid),
    ): CameraIndex {
        if (avoid.isEmpty()) return CameraIndex(emptyList())
        val seen = index.seeing(chosen).toSet()
        return CameraIndex(avoid.filterNot { it in seen })
    }

    /**
     * The pins on [chosen] that exist because of its geometry rather than
     * because BRouter predicted the car would need them — past every turn, and
     * either side of every avoided camera the route squeezes past.
     *
     * Pruning must not second-guess these. It removes a pin when routing the
     * leg without it says the car would stay on our line anyway, which is the
     * same prediction these two exist to stop relying on; letting it run on
     * them would undo them one at a time.
     */
    fun protectedPins(
        chosen: List<GeoPoint>,
        fastest: List<GeoPoint>,
        index: CameraIndex,
        avoided: CameraIndex,
    ): Set<GeoPoint> {
        val fastestIndex = if (fastest.size >= 2) PolylineIndex(fastest) else null
        return (turnPins(chosen, index) + cameraGuardPins(chosen, avoided, index, fastestIndex))
            .mapNotNull { pointAtAlong(chosen, it) }
            .toSet()
    }

    /**
     * Drop pins that sit on top of one another. Keeps the first of each cluster
     * — the earliest is the one that actually forces the turn.
     *
     * How close is "on top of" depends on where: open road wants
     * [MIN_PIN_SPACING_METERS], a dense grid wants [DENSE_PIN_SPACING_METERS],
     * and [density] decides which by counting cameras nearby. Without an index
     * this behaves exactly as it always did, at the open-road spacing.
     *
     * Where two pins straddle the change — one out in open country, the next
     * entering a city — the *tighter* of their two spacings wins. Erring that
     * way keeps the first pin of a built-up stretch, which is the one that has
     * to survive: it is the one holding the car onto the right road as the
     * junctions start.
     */
    internal fun spaceOut(pins: List<GeoPoint>, density: CameraIndex? = null): List<GeoPoint> {
        val kept = mutableListOf<GeoPoint>()
        for (pin in pins) {
            val last = kept.lastOrNull()
            if (last == null) {
                kept += pin
                continue
            }
            val spacing = minOf(spacingAt(last, density), spacingAt(pin, density))
            if (haversineMeters(last, pin) >= spacing) kept += pin
        }
        return kept
    }

    /** How far apart pins need to be around [p], given how built-up it is. */
    internal fun spacingAt(p: GeoPoint, density: CameraIndex?): Double {
        if (density == null) return MIN_PIN_SPACING_METERS
        val nearby = density.countWithin(p, DENSITY_RADIUS_METERS)
        val builtUp = (nearby.toDouble() / DENSE_CAMERA_COUNT).coerceIn(0.0, 1.0)
        return MIN_PIN_SPACING_METERS - builtUp * (MIN_PIN_SPACING_METERS - DENSE_PIN_SPACING_METERS)
    }

    /** Indices of the most divergent point of each stretch off the fastest route. */
    private fun shapeIndices(
        chosen: List<GeoPoint>,
        fastestIndex: PolylineIndex,
        maxWaypoints: Int,
        thresholdMeters: Double,
    ): List<Int> {
        data class Run(val peakIndex: Int, val lengthMeters: Double)
        val runs = mutableListOf<Run>()
        var runStart = -1
        var peakIndex = -1
        var peakDistance = -1.0
        var runLength = 0.0

        fun closeRun() {
            if (runStart >= 0) {
                runs += Run(peakIndex, runLength)
                runStart = -1; peakIndex = -1; peakDistance = -1.0; runLength = 0.0
            }
        }

        // Indexed: this used to compare every point of one route against every
        // segment of the other, which on two forty-thousand-point lines is over
        // a billion distance calculations and was the single slowest thing in a
        // long plan.
        for (i in chosen.indices) {
            val d = fastestIndex.distanceMeters(chosen[i])
            if (d > thresholdMeters) {
                if (runStart < 0) runStart = i
                if (d > peakDistance) { peakDistance = d; peakIndex = i }
                if (i > 0) runLength += haversineMeters(chosen[i - 1], chosen[i])
            } else {
                closeRun()
            }
        }
        closeRun()

        return runs
            .sortedByDescending { it.lengthMeters }
            .let { if (maxWaypoints == NO_LIMIT) it else it.take(maxWaypoints) }
            .map { it.peakIndex }
            .sorted() // restore route order
    }

    /**
     * Insert extra waypoints wherever a car cutting straight from one pin to the
     * next would pass through a camera our route avoids, until no such shortcut
     * remains or the budget runs out.
     */
    private fun pinAgainstShortcuts(
        chosen: List<GeoPoint>,
        shape: List<Int>,
        maxWaypoints: Int,
        avoidedIndex: CameraIndex,
        outOfTime: () -> Boolean,
    ): List<Int> {
        if (avoidedIndex.isEmpty) return shape

        // Work over the full chain including the endpoints, which bound the
        // first and last shortcuts.
        val pins = (listOf(0) + shape + listOf(chosen.lastIndex)).distinct().toMutableList()

        // Unlimited means "as many as the shortcuts demand"; the loop still ends
        // when there is no shortcut left to close.
        var budget = if (maxWaypoints == NO_LIMIT) Int.MAX_VALUE else maxWaypoints - shape.size
        var madeProgress = true
        while (budget > 0 && madeProgress) {
            if (outOfTime()) break
            madeProgress = false
            var i = 0
            while (i < pins.size - 1 && budget > 0) {
                if (outOfTime()) return pins.filter { it != 0 && it != chosen.lastIndex }
                val a = pins[i]
                val b = pins[i + 1]
                if (b - a > 1 && shortcutIsExposed(chosen, a, b, avoidedIndex)) {
                    val insert = mostDivergentBetween(chosen, a, b)
                    if (insert != null) {
                        pins.add(i + 1, insert)
                        budget--
                        madeProgress = true
                        continue // re-check the first half of the split span
                    }
                }
                i++
            }
        }

        // Endpoints are the origin and destination, supplied separately.
        return pins.filter { it != 0 && it != chosen.lastIndex }
    }

    /**
     * True if the straight line from `chosen[a]` to `chosen[b]` enters a
     * camera's view.
     *
     * Through the grid, and that is not a micro-optimisation. A chord early in
     * this loop spans most of the trip, and a camera walks a line at ten-metre
     * samples — so asking every avoided camera in turn is (trip length ÷ 10 m) ×
     * cameras, per check, with a check per insertion. Measured on a 615 km trip
     * carrying 5,395 cameras, choosing the pins took 349 s against a phase
     * budget of 20; through the index the same work is a few seconds.
     */
    private fun shortcutIsExposed(
        chosen: List<GeoPoint>,
        a: Int,
        b: Int,
        avoided: CameraIndex,
    ): Boolean = avoided.anySees(listOf(chosen[a], chosen[b]))

    /**
     * The route point between [a] and [b] that sits farthest from the straight
     * line between them — the one that most forces the car off the shortcut.
     */
    private fun mostDivergentBetween(chosen: List<GeoPoint>, a: Int, b: Int): Int? {
        var best = -1
        var bestDistance = 0.0
        for (i in (a + 1) until b) {
            val d = pointToSegmentMeters(chosen[i], chosen[a], chosen[b])
            if (d > bestDistance) { bestDistance = d; best = i }
        }
        return best.takeIf { it >= 0 }
    }
}
