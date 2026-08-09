package app.shunt.solver.waypoints

import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.CameraIndex
import app.shunt.solver.brouter.CameraVision
import app.shunt.solver.geo.haversineMeters
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
     * How far past the fork to drop the pin. Far enough that reaching it means
     * the turn has been taken and the car is committed; short enough that no
     * other road reaches it first.
     */
    const val PAST_FORK_METERS = 250.0

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

        while (passes++ < MAX_PASSES) {
            if (outOfTime()) break
            val chain = listOf(chosen.first()) + current + chosen.last()
            var inserted = false
            for (i in 0 until chain.size - 1) {
                val from = chain[i]
                val to = chain[i + 1]
                if (from in hopeless || (from to to) in clean) continue
                if (outOfTime()) return WaypointExtractor.spaceOut(current)

                val carPath = carRoute(from, to)
                if (carPath == null) {
                    clean += from to to
                    continue
                }
                if (!avoidedIndex.anySees(carPath)) {
                    clean += from to to
                    continue
                }

                // The car would pass a camera getting to this pin. Put one in
                // just past where its path and ours part company.
                if (maxPins != WaypointExtractor.NO_LIMIT && current.size >= maxPins) return current
                val pin = pinPastFork(chosen, carPath, from, to)
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
        return WaypointExtractor.spaceOut(current)
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
    ): GeoPoint? {
        if (carPath.size < 2) return null
        val startAlong = pointToPolylineProgress(from, chosen).alongMeters
        val endAlong = pointToPolylineProgress(to, chosen).alongMeters
        if (endAlong - startAlong < PAST_FORK_METERS) return null

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
            if (along - forkAlong >= PAST_FORK_METERS) return chosen[i]
        }
        // Diverges but never far enough past it before the next pin: put the
        // pin at the end of the usable stretch rather than nothing at all.
        return forkAlong?.let { pointAtAlong(chosen, (it + endAlong) / 2) }
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
