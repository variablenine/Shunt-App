package app.shunt.solver.waypoints

import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.CameraIndex
import app.shunt.solver.brouter.CameraVision
import app.shunt.solver.geo.haversineMeters
import app.shunt.solver.geo.PolylineIndex
import app.shunt.solver.geo.pointToPolyline
import app.shunt.solver.geo.pointToPolylineProgress

/**
 * Places the shaping pins where the **car** will actually honour them.
 *
 * Everything else in this app plans a line. The car is never given that line —
 * only one point at a time — and it then routes itself there by its own fastest
 * path. So a pin is not a description of where to go; it is a constraint, and it
 * only constrains anything if the quickest way to reach it happens to be the
 * road we want. Two ways that fails, both of which put the car past a camera the
 * route was built to avoid:
 *
 *  - **A pin too far along the detour.** The car will not take an unnecessary
 *    turn to reach it. Given a pin near the far end of a loop, it drives the
 *    fast road — straight past the camera — and joins the detour at its tail.
 *  - **A pin past the fork but on the wrong side of it.** Once the car has
 *    committed to a road it does not turn back, so the pin has to be placed
 *    before the decision, not after.
 *
 * The fix is to stop guessing and ask. For each leg the car will drive — from
 * the point it starts at to the next pin — this routes it the way the *car*
 * will, with no camera avoidance at all, and checks whether that path enters a
 * camera our route stays clear of. Where it does, a pin goes in just past the
 * point where the two paths part company: far enough onto the detour that the
 * car is committed, near enough to the fork that it cannot get there any other
 * way.
 *
 * This is deliberately empirical. The old version compared a straight chord
 * between pins against the camera cones, which is a guess about what the car
 * would do; this drives the same engine the car effectively uses and looks at
 * the answer.
 */
object WaypointRefiner {

    /**
     * Runaway guard only, not a budget. Termination comes from the geometry:
     * every pin is a point on the route, each insertion strictly shortens the
     * stretch it splits, and a stretch shorter than [PAST_FORK_METERS] cannot
     * hold one — so a route of finite length admits finitely many pins. This
     * just bounds the routing calls if some pathological line defeats that.
     */
    const val MAX_PASSES = 500

    /**
     * How far our route must be from the car's own path to count as having
     * forked. Generous enough to ignore the two lines wobbling along the same
     * road, tight enough to catch a genuine turn.
     */
    const val FORK_THRESHOLD_METERS = 60.0

    /**
     * Whether a leg the car would drive needs a pin putting in it.
     *
     * Two ways it can, and **for most of this project only the first counted**:
     *
     *  - the car's own path enters a camera the route was built to avoid, or
     *  - the car gets there by a different road altogether.
     *
     * The second was the gap, and it showed up on the first real drive: *"the
     * car picked a different route than what Shunt was banking on."* Judged on
     * cameras alone the leg is fine — a different road that happens to be
     * camera-free passes the test — so no pin went in, and the car drove
     * somewhere the map on the phone did not show. Everything downstream then
     * misreads: the drive monitor calls it off-route, camera warnings are
     * computed for a line the car is not on, and a re-plan may fire.
     *
     * Checking both is also what makes the pin set *definite* rather than
     * merely sufficient — it is the same predicate `pruneIdlePins` uses to take
     * pins back out, so insertion and removal agree and the phase settles on the
     * pins that hold the route and no others.
     */
    private fun needsPin(carPath: List<GeoPoint>, avoided: CameraIndex, line: PolylineIndex): Boolean =
        avoided.anySees(carPath) || straysFrom(line, carPath)

    /**
     * Whether [path] leaves [line] anywhere along it.
     *
     * **Walks the path rather than checking its vertices**, and that is not
     * pedantry — it is the same trap `BrouterPlanner.sampleSpine` fell into.
     * A path's vertices can all sit on the planned line while the road between
     * two of them goes somewhere else entirely: a straight hop from one junction
     * to another is two points, and everything that matters happens in between.
     * Checking only the ends reports such a leg as faithful.
     */
    private fun straysFrom(line: PolylineIndex, path: List<GeoPoint>): Boolean {
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val steps = (haversineMeters(a, b) / PATH_SAMPLE_METERS).toInt().coerceAtLeast(1)
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val at = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                if (line.distanceMeters(at) > FORK_THRESHOLD_METERS) return true
            }
        }
        return false
    }

    /** How finely a leg is walked when asking whether it follows the route. */
    private const val PATH_SAMPLE_METERS = 50.0

    /**
     * How far past the fork to drop the pin, on open road.
     *
     * **The governing constraint is the drive monitor, not the geometry**, and
     * missing that is how this number came to be wrong twice. The monitor
     * advances to the *next* pin as soon as the car is within
     * `max(150 m, speed × 18 s)` of the current one. So a pin placed less than
     * that distance past a fork is re-aimed away from **before the car reaches
     * the fork** — the pin stops constraining the turn it exists to force, and
     * the car is free to carry straight on. Worked through:
     *
     * | speed | monitor advances at | 250 m past fork | 600 m past fork |
     * |---|---|---|---|
     * | 30 mph | 241 m | re-aims 121 m *before* the fork | committed |
     * | 45 mph | 362 m | re-aims 112 m *before* the fork | committed |
     * | 70 mph | 563 m | re-aims 313 m *before* the fork | committed |
     *
     * At 250 m — the value this held for most of the project — a pin only
     * survived to do its job below about 35 mph. On a highway fork it never
     * did, which is the "long stretches where the car doesn't follow it" report.
     *
     * So this is the lead distance at highway speed, plus margin. The competing
     * pressure is real — a pin too far along a detour lets the car take the fast
     * road and join at the tail — but **that failure is the one the refiner
     * catches**, by re-routing the leg and seeing the car go the wrong way,
     * whereas being advanced past early is invisible to it. Erring long is
     * therefore the recoverable direction.
     */
    const val PAST_FORK_METERS = 600.0

    /**
     * The same, where the road network is dense.
     *
     * Same rule, lower speed: city driving puts the monitor's lead at 160-240 m,
     * so a pin has to clear the fork by about that much rather than by 600 m.
     * Being able to place it sooner is what lets a grid be pinned at all — at
     * 600 m in a city the pin lands several junctions past the turn.
     *
     * **This was briefly 120 m, which was wrong at every speed** — even at
     * 20 mph the monitor advances 161 m out, so the car re-aimed 41 m before
     * reaching the fork. The intuition behind it ("a city turn is committed
     * immediately") was about the car, and the binding constraint is the
     * monitor.
     */
    const val DENSE_PAST_FORK_METERS = 250.0

    /**
     * Pins for [chosen] that the car will follow. [pins] are the candidates
     * from [WaypointExtractor]; [carRoute] routes a leg the way the car would
     * (no avoidance), returning null when it can't — in which case that leg is
     * left alone rather than pinned on a guess.
     */
    suspend fun refine(
        chosen: List<GeoPoint>,
        pins: List<GeoPoint>,
        avoid: List<CameraVision>,
        maxPins: Int = WaypointExtractor.NO_LIMIT,
        /** Shared grid; building one per call would undo the point of having it. */
        index: CameraIndex = CameraIndex(avoid),
        /**
         * Pins that must survive pruning whatever the routing says.
         *
         * The turn pins from [WaypointExtractor]. Pruning asks BRouter whether
         * the car would follow our line without a given pin, and drops it if the
         * answer is yes — which is sound only as far as BRouter models Tesla's
         * router. At a junction that assumption is exactly the one worth not
         * making, so a pin placed *because* there is a turn there is not a pin
         * to remove on the strength of a prediction.
         */
        protectedPins: Set<GeoPoint> = emptySet(),
        /**
         * Stop and hand back what we have. Every pin costs a full routing pass,
         * and a long camera-dense trip can want dozens — which is how planning a
         * five-hour route came to take minutes.
         *
         * Giving up early is safe in a way that giving up on the route itself
         * would not be: pins only *steer* a car that routes itself, so a route
         * with fewer of them is still the route we planned and still labelled
         * with the cameras it passes, and the drive monitor still warns on
         * approach. A plan that arrives too late to use is worth less than a
         * slightly less firmly steered one that arrives in time.
         */
        outOfTime: () -> Boolean = { false },
        carRoute: suspend (from: GeoPoint, to: GeoPoint) -> List<GeoPoint>?,
    ): List<GeoPoint> {
        if (chosen.size < 2 || avoid.isEmpty()) return pins

        // Where a pin may go on this route. The refiner has no view of the
        // fastest line, so this catches the junctions and our own line coming
        // back near itself; the extractor's copy sees more. Built once — it
        // walks the whole route.
        val sites = WaypointExtractor.sitesFor(chosen, null)

        // Only cameras this route actually avoids are worth spending a pin on.
        // One it drives past anyway is already counted and warned about, and
        // pinning against it would buy nothing.
        val seen = index.seeing(chosen).toSet()
        val avoided = avoid.filterNot { it in seen }
        if (avoided.isEmpty()) return pins
        val avoidedIndex = CameraIndex(avoided)

        val current = pins.toMutableList()
        // Legs a pin cannot rescue — the car passes the camera however early we
        // place one, typically because it sits on the only road out. Without
        // this the refiner keeps inserting ever-earlier pins into the same leg,
        // piling a dozen of them onto the first mile and starving the rest of
        // the trip. One is a fact about the roads; more do not change it.
        val hopeless = mutableSetOf<GeoPoint>()

        // Legs already known clean. Re-checking them after every insertion made
        // the whole thing quadratic in routing calls — the expensive kind — for
        // no new information, since inserting a pin later in the trip cannot
        // change how the car drives an earlier leg.
        val clean = mutableSetOf<Pair<GeoPoint, GeoPoint>>()
        var passes = 0
        // Built once; every leg is measured against the whole planned line.
        val line = PolylineIndex(chosen)

        while (passes++ < MAX_PASSES) {
            if (outOfTime()) break
            val chain = listOf(chosen.first()) + current + chosen.last()
            var inserted = false
            for (i in 0 until chain.size - 1) {
                val from = chain[i]
                val to = chain[i + 1]
                if (from in hopeless || (from to to) in clean) continue
                if (outOfTime()) return WaypointExtractor.spaceOut(current, density = index)

                val carPath = carRoute(from, to)
                if (carPath == null) {
                    clean += from to to
                    continue
                }
                if (!needsPin(carPath, avoidedIndex, line)) {
                    clean += from to to
                    continue
                }

                // The car would pass a camera getting to this pin, or would get
                // there by a different road. Put one in just past where its path
                // and ours part company.
                if (maxPins != WaypointExtractor.NO_LIMIT && current.size >= maxPins) return current
                val pin = pinPastFork(chosen, carPath, from, to, pastForkAt(from, index), sites)
                if (pin == null || pin in current) {
                    // Nowhere left to put one on this stretch.
                    hopeless += from
                    continue
                }
                current.add(i, pin)
                inserted = true
                break
            }
            if (!inserted) break
        }
        pruneIdlePins(chosen, current, avoidedIndex, protectedPins, outOfTime, carRoute)
        return WaypointExtractor.spaceOut(current, density = index)
    }

    /**
     * Drop pins that are not holding the car to anything.
     *
     * Pins arrive from two places, and only one of them checks its work.
     * [WaypointExtractor] adds them from the shape of the route and from chords
     * that clip a camera — both geometric guesses — and the loop above adds them
     * where the car provably strays. Nothing then asked whether each one was
     * still needed, so a route could carry several in a row on a straight road
     * where the car has nowhere else to go: reported from real use as
     * "pointless waypoints one after the other on the same straight road".
     *
     * The test is the same one used to add a pin, run backwards: take it out,
     * route the leg it was splitting the way the car would, and keep it unless
     * the car does **both** of the things the pin was there to ensure —
     *
     *  - stays out of every camera the route avoids, and
     *  - actually drives the road we planned, within [FORK_THRESHOLD_METERS].
     *
     * The second condition is what stops this from quietly becoming a different
     * feature. Judged on cameras alone, pruning would strip a route back to the
     * few pins that cameras strictly force and let the car pick its own way
     * between them — camera-free, but not the route on the screen, and every
     * divergence is something the drive monitor then reports as off-route. A pin
     * that keeps the car on the line it was shown is doing a job even when no
     * camera depends on it.
     *
     * What is left to remove is then exactly what was reported: pins on a
     * straight road the car was going to drive anyway. That makes the whole pin
     * phase self-correcting — earlier stages can be generous, because anything
     * they over-add is removed here on evidence rather than by a rule about
     * spacing.
     *
     * Conservative in the two places it has to be: a leg the router cannot
     * answer for keeps its pin, and running out of time stops the pruning rather
     * than assuming the rest were idle.
     */
    private suspend fun pruneIdlePins(
        chosen: List<GeoPoint>,
        pins: MutableList<GeoPoint>,
        avoidedIndex: CameraIndex,
        protectedPins: Set<GeoPoint>,
        outOfTime: () -> Boolean,
        carRoute: suspend (from: GeoPoint, to: GeoPoint) -> List<GeoPoint>?,
    ) {
        // Built once: asking "how far is this point from the route" walks the
        // whole line otherwise, and a merged leg is sampled at every vertex.
        val line = PolylineIndex(chosen)
        var i = 0
        while (i < pins.size) {
            if (outOfTime()) return
            if (pins[i] in protectedPins) { i++; continue }
            val before = if (i == 0) chosen.first() else pins[i - 1]
            val after = if (i == pins.lastIndex) chosen.last() else pins[i + 1]
            val withoutIt = carRoute(before, after)
            // Exactly the predicate insertion uses, negated: a pin stays if and
            // only if taking it out would give a leg the refiner would have put
            // one into. That symmetry is what makes the phase settle rather than
            // oscillate, and what lets "no more, no less" mean something.
            val idle = withoutIt != null && !needsPin(withoutIt, avoidedIndex, line)
            if (idle) pins.removeAt(i) else i++
        }
    }

    /**
     * How far past a fork around [p] the pin should go, given how built-up it
     * is. Uses the same density reading as pin spacing, so the two agree about
     * what counts as a city.
     */
    internal fun pastForkAt(p: GeoPoint, density: CameraIndex): Double {
        val nearby = density.countWithin(p, WaypointExtractor.DENSITY_RADIUS_METERS)
        val builtUp = (nearby.toDouble() / WaypointExtractor.DENSE_CAMERA_COUNT).coerceIn(0.0, 1.0)
        return PAST_FORK_METERS - builtUp * (PAST_FORK_METERS - DENSE_PAST_FORK_METERS)
    }

    /**
     * The point on [chosen] shortly after it leaves [carPath], between [from]
     * and [to]. Null when the two never diverge in that stretch (so no pin
     * would help) or the stretch is too short to place one in.
     */
    internal fun pinPastFork(
        chosen: List<GeoPoint>,
        carPath: List<GeoPoint>,
        from: GeoPoint,
        to: GeoPoint,
        pastForkMeters: Double = PAST_FORK_METERS,
        /**
         * Where a pin may actually sit. A fork pin is as capable as any other
         * of landing in the next junction or beside a parallel road — more so,
         * because a fork *is* a junction and the road it forks from runs
         * alongside for a while afterwards. See PinSites.
         */
        sites: PinSites? = null,
    ): GeoPoint? {
        if (carPath.size < 2) return null
        val startAlong = pointToPolylineProgress(from, chosen).alongMeters
        val endAlong = pointToPolylineProgress(to, chosen).alongMeters
        if (endAlong - startAlong < pastForkMeters) return null

        var along = 0.0
        var forkAlong: Double? = null
        for (i in chosen.indices) {
            if (i > 0) along += haversineMeters(chosen[i - 1], chosen[i])
            if (along <= startAlong) continue
            if (along >= endAlong) break

            if (forkAlong == null) {
                // Still on the car's own path — look for where we leave it.
                if (pointToPolyline(chosen[i], carPath).distanceMeters > FORK_THRESHOLD_METERS) {
                    forkAlong = along
                }
                continue
            }
            // Past the fork: take the first point far enough along the detour
            // that the turn is behind us.
            if (along - forkAlong >= pastForkMeters) {
                return settled(chosen, sites, along, forkAlong, endAlong) ?: chosen[i]
            }
        }
        // Diverges but never far enough past it before the next pin: put the
        // pin at the end of the usable stretch rather than nothing at all.
        val fork = forkAlong ?: return null
        val midway = (fork + endAlong) / 2
        return settled(chosen, sites, midway, fork, endAlong) ?: pointAtAlong(chosen, midway)
    }

    /**
     * [desired] moved to the nearest position a pin may occupy, between the
     * fork it commits and the next pin along. Null when this route has no
     * placement rules to apply — the caller then keeps what it had, which is
     * what this did before there were any.
     */
    private fun settled(
        chosen: List<GeoPoint>,
        sites: PinSites?,
        desired: Double,
        forkAlong: Double,
        endAlong: Double,
    ): GeoPoint? {
        if (sites == null) return null
        val at = sites.settle(
            desired,
            floor = forkAlong + PinSites.CLEARANCE_METERS,
            ceiling = endAlong,
        ) ?: return null
        return pointAtAlong(chosen, at)
    }

    /** The point [target] metres along [line]. */
    private fun pointAtAlong(line: List<GeoPoint>, target: Double): GeoPoint? {
        var along = 0.0
        for (i in line.indices) {
            if (i > 0) along += haversineMeters(line[i - 1], line[i])
            if (along >= target) return line[i]
        }
        return null
    }
}
