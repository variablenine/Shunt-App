package app.shunt.solver.waypoints

import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.CameraIndex
import app.shunt.solver.geo.PolylineIndex
import app.shunt.solver.geo.haversineMeters
import app.shunt.solver.geo.pointAtAlong
import app.shunt.solver.geo.turnsAlong
import kotlin.math.abs

/**
 * Where on a route a pin may actually be put.
 *
 * **A pin is not a description, it is a coordinate the car snaps to its own
 * road graph** (§6). Everything that makes pins — turns, camera guards, the
 * shape pass, the refiner's forks — decides *how far along* the route one
 * should go and has, until now, put it exactly there. That is fine on open
 * road and wrong in two ways a driver reported from a real drive:
 *
 * > Sometimes it can cause the car to pull into a driveway if a waypoint after
 * > a turn ends up too close on the actual Tesla nav. The shunt waypoint was on
 * > a turn directly after a turn. Another point had me navigating to a road
 * > parallel to the planned route.
 *
 * Both are the same mistake — a position chosen for what it means to *us*, with
 * no check that it is a sane thing to hand a car.
 *
 * - **On a junction.** The car treats a waypoint as a *destination*, so it
 *   arrives: it slows, and it pulls in. Arriving in the middle of a junction
 *   means the driveway or the side street. A pin placed a fork distance past
 *   turn A lands on top of turn B whenever B follows closely, which is exactly
 *   the report.
 * - **Beside another road.** A frontage road, a service road or the far
 *   carriageway sits tens of metres from ours, and the car snaps the coordinate
 *   to whichever its own map calls nearest. Then it navigates the parallel
 *   road, which is the second half of the report.
 *
 * So a position has to *earn* being a pin: [usable]. [settle] takes what a
 * caller wanted and finds the nearest position to it that qualifies, or says
 * there is none and lets the caller drop the pin — which is safe, because a
 * route with fewer pins is still the route we planned, still labelled and still
 * warned about, while a pin on the wrong road actively steers the car off it.
 *
 * **What this cannot see.** A parallel road that neither our route nor the
 * fastest one uses is invisible here — there is no geometry for it. Answering
 * that properly means asking BRouter's graph what ways lie near a point, which
 * is engine work; see the roadmap. What is caught is the case the geometry does
 * describe: our own line coming back near itself (a cloverleaf, a switchback, a
 * frontage road we route onto) and the fastest line running alongside ours,
 * which is where a detour around a camera puts us by construction.
 */
class PinSites(
    private val chosen: List<GeoPoint>,
    /**
     * Other roads we know the geometry of — in practice the fastest route,
     * which is a real road running near ours wherever this route has left it.
     */
    private val elsewhere: List<PolylineIndex> = emptyList(),
    /** The cameras this route avoids, for [cameraApproaches]. */
    avoided: CameraIndex = CameraIndex(emptyList()),
) {
    private val alongAt = DoubleArray(chosen.size)
    private val self = PolylineIndex(chosen)

    init {
        for (i in 1 until chosen.size) {
            alongAt[i] = alongAt[i - 1] + haversineMeters(chosen[i - 1], chosen[i])
        }
    }

    /** How long the route is, in metres. */
    val length: Double get() = alongAt.lastOrNull() ?: 0.0

    /**
     * Every turn the route takes, as a distance along it.
     *
     * Computed once and shared: the turn pins want them to place against, and
     * every other pin wants them to stay clear of.
     */
    val turns: List<Double> =
        if (chosen.size < 3) emptyList()
        else turnsAlong(chosen, WaypointExtractor.TURN_DEGREES, WaypointExtractor.TURN_SPAN_METERS)

    private val turnArray = turns.toDoubleArray().also { it.sort() }

    /**
     * Where the route passes closest to each camera it avoids, as a distance
     * along it. Computed once here and read by everything that needs it, rather
     * than swept per producer.
     */
    val cameraApproaches: List<Double> =
        if (chosen.size < 2) emptyList()
        else avoided.closestApproachAlong(
            chosen,
            WaypointExtractor.CAMERA_GUARD_RADIUS_METERS,
            WaypointExtractor.GUARD_SAMPLE_METERS,
        ).values.sorted()

    private val approachArray = cameraApproaches.toDoubleArray()

    /**
     * The stretch a pin for the turn at [turnAlong] may occupy: past that turn,
     * and short of the next one. Empty when two turns are too close together to
     * fit a pin between them — a jog, a Michigan left, a roundabout exit — and
     * an empty stretch means no pin, because the *next* turn's pin holds both.
     * A point only reachable by making both turns instructs both.
     */
    fun blockAfter(turnAlong: Double): ClosedFloatingPointRange<Double> {
        // The *next* turn, however close — a turn inside the clearance is
        // precisely the case this exists for, and skipping to the one after it
        // would let the block run straight over the junction in between.
        val next = turnArray.firstOrNull { it > turnAlong + SAME_TURN_METERS } ?: length
        val floor = turnAlong + CLEARANCE_METERS
        val ceiling = next - CLEARANCE_METERS
        return if (floor > ceiling) EMPTY else floor..ceiling
    }

    /**
     * The nearest position to [desired] that a pin may occupy, within
     * [floor]..[ceiling], or null when nothing in that stretch qualifies.
     *
     * Searches backwards before forwards at each step: earlier is nearer the
     * thing the pin was placed for — the turn it commits, the camera it guards
     * — and a pin that has slid *past* what it was guarding is no longer doing
     * the job it was added to do.
     */
    fun settle(desired: Double, floor: Double, ceiling: Double): Double? {
        if (floor > ceiling) return null
        val start = desired.coerceIn(floor, ceiling)
        if (usable(start)) return start
        var step = SETTLE_STEP_METERS
        while (step <= SETTLE_REACH_METERS) {
            val back = start - step
            if (back >= floor && usable(back)) return back
            val on = start + step
            if (on <= ceiling && usable(on)) return on
            step += SETTLE_STEP_METERS
        }
        return null
    }

    /** [settle] within [SETTLE_REACH_METERS] either side, for a pin with no block of its own. */
    fun settleNear(desired: Double): Double? =
        settle(desired, desired - SETTLE_REACH_METERS, desired + SETTLE_REACH_METERS)

    /**
     * [settle] for a pin that brackets something it must stay clear of: it may
     * slide either way within [SETTLE_REACH_METERS], but never nearer to
     * [keepClearOf] than [CAMERA_STANDOFF_METERS].
     *
     * The camera guards are the case, and the bound has to be expressed against
     * the *camera* rather than against the wanted position. Allowed to slide
     * freely, the pin placed a fork distance before a camera settles forward
     * onto it — which does not merely weaken the guard, it aims the car at the
     * camera the route detoured to avoid, and the car takes its own road to get
     * there. Bounded at the wanted position instead, a guard near the start or
     * end of a leg has nowhere legal to go and is dropped, which loses the
     * bracket altogether.
     */
    fun settleClearOf(desired: Double, keepClearOf: Double): Double? =
        if (desired <= keepClearOf) {
            settle(desired, desired - SETTLE_REACH_METERS, keepClearOf - CAMERA_STANDOFF_METERS)
        } else {
            settle(desired, keepClearOf + CAMERA_STANDOFF_METERS, desired + SETTLE_REACH_METERS)
        }

    /** Whether a pin at [at] metres along would be a sane thing to send a car to. */
    fun usable(at: Double): Boolean {
        if (at <= 0.0 || at >= length) return false
        if (nearAnyTurn(at)) return false
        if (nearAvoidedCamera(at)) return false
        val p = pointAt(at) ?: return false
        return unambiguous(at, p)
    }

    /**
     * Whether [at] is close enough to an avoided camera to be a bad place to
     * stop.
     *
     * **A pin is where the car arrives**, and the car reaches it by its own
     * route, not ours. Putting one at the point where our line passes nearest a
     * camera we deliberately dodged asks the car to drive *to* that spot — and
     * whatever road it picks to get there, it is heading at the camera rather
     * than past it at a distance. Every other rule here is about the pin being
     * on the wrong road; this one is about it being in the wrong place on the
     * right road.
     *
     * The guard pins sit a fork distance out (250 m in a city, 600 m on open
     * road), so this never removes the pins whose job is to bracket a camera —
     * only the ones that would land between them.
     */
    private fun nearAvoidedCamera(at: Double): Boolean {
        if (approachArray.isEmpty()) return false
        var lo = 0
        var hi = approachArray.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val a = approachArray[mid]
            if (abs(a - at) < CAMERA_STANDOFF_METERS) return true
            if (a < at) lo = mid + 1 else hi = mid - 1
        }
        if (lo < approachArray.size && abs(approachArray[lo] - at) < CAMERA_STANDOFF_METERS) return true
        if (lo > 0 && abs(approachArray[lo - 1] - at) < CAMERA_STANDOFF_METERS) return true
        return false
    }

    /**
     * How far along the route [p] sits, by nearest vertex.
     *
     * Coarse by design — its callers hand back points this class produced, so
     * the answer is exact for those and near enough for anything else.
     */
    fun alongOf(p: GeoPoint): Double {
        if (chosen.isEmpty()) return 0.0
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (i in chosen.indices) {
            val d = haversineMeters(p, chosen[i])
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return alongAt[best]
    }

    /**
     * The point [at] metres along, by binary search over the cumulative
     * distances built here.
     *
     * Identical to `pointAtAlong` — the vertex at or just past the target — but
     * not linear in the route's length. That matters now rather than before:
     * settling asks this question up to a dozen times per pin instead of once,
     * and a cross-state route is tens of thousands of points, so keeping the
     * old walk would have put a second or two of pure scanning into the pin
     * budget for nothing.
     */
    fun pointAt(at: Double): GeoPoint? {
        if (chosen.isEmpty() || at > length) return null
        var lo = 0
        var hi = chosen.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (alongAt[mid] < at) lo = mid + 1 else hi = mid
        }
        return chosen[lo]
    }

    private fun nearAnyTurn(at: Double): Boolean {
        if (turnArray.isEmpty()) return false
        var lo = 0
        var hi = turnArray.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val t = turnArray[mid]
            if (abs(t - at) < CLEARANCE_METERS) return true
            if (t < at) lo = mid + 1 else hi = mid - 1
        }
        // `lo` is the first turn past `at`; its neighbour on the other side is
        // the only other one that can be inside the clearance.
        if (lo < turnArray.size && abs(turnArray[lo] - at) < CLEARANCE_METERS) return true
        if (lo > 0 && abs(turnArray[lo - 1] - at) < CLEARANCE_METERS) return true
        return false
    }

    /** Whether the only road near [p] is the stretch of ours the pin is on. */
    private fun unambiguous(at: Double, p: GeoPoint): Boolean {
        for (other in elsewhere) {
            val d = other.distanceMeters(p)
            // Below [SAME_ROAD_METERS] it *is* our road: two routes over the
            // same OSM way share its nodes, so the distance is zero and the
            // slack is only for a point taken part-way along a segment. Above
            // it and inside the snap radius, it is a different way running
            // alongside — which is the one the car may pick.
            if (d > SAME_ROAD_METERS && d < AMBIGUITY_RADIUS_METERS) return false
        }
        return self.distanceMeters(p, excluding = nearbySegments(at)) >= AMBIGUITY_RADIUS_METERS
    }

    /** Segments of our own line within [AMBIGUITY_ALONG_METERS] of [at], by index. */
    private fun nearbySegments(at: Double): IntRange {
        val lo = firstSegmentEndingAfter(at - AMBIGUITY_ALONG_METERS)
        val hi = lastSegmentStartingBefore(at + AMBIGUITY_ALONG_METERS)
        return lo..hi
    }

    private fun firstSegmentEndingAfter(target: Double): Int {
        var lo = 0
        var hi = chosen.size - 2
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (alongAt[mid + 1] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun lastSegmentStartingBefore(target: Double): Int {
        var lo = 0
        var hi = chosen.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (alongAt[mid] > target) hi = mid - 1 else lo = mid
        }
        return lo
    }

    companion object {
        /**
         * How far along the route a pin must be from every turn on it.
         *
         * The car arrives at a waypoint — it slows and pulls in — so this is
         * about where "arriving" happens rather than about routing. Roughly the
         * drive monitor's arrival radius, which is the distance at which the
         * app already considers the pin reached, and comfortably past the
         * junction itself and the driveways either side of it.
         */
        const val CLEARANCE_METERS = 60.0

        /**
         * How near another road may be before the car might snap to it instead.
         *
         * A frontage or service road runs fifteen to thirty metres from the
         * carriageway it serves, and the two maps do not have to agree to the
         * metre for that to be enough.
         */
        const val AMBIGUITY_RADIUS_METERS = 35.0

        /** Nearer than this and it is our own road, drawn from the same nodes. */
        const val SAME_ROAD_METERS = 3.0

        /**
         * How far apart along the route two passages must be before they count
         * as different roads. Below this it is the same stretch, seen from a
         * point that sits on it.
         */
        const val AMBIGUITY_ALONG_METERS = 400.0

        /**
         * How far along the route a pin must keep from an avoided camera.
         *
         * Comfortably inside the fork distance the guards are placed at, so the
         * bracket survives and only a pin that would sit *on* the camera is
         * refused.
         */
        const val CAMERA_STANDOFF_METERS = 150.0

        /**
         * How near another road counts as "the car could go either way".
         *
         * **Reporting only.** It used to gate whether a pin survived, and that
         * deleted pins all over a motorway — see `BrouterPlanner`.
         *
         * Kept because the distinction is real and worth naming: below this,
         * which road the car's map picks is a coin toss, and a pin that has to
         * sit there is one to be suspicious of when a drive goes wrong. Well
         * below [AMBIGUITY_RADIUS_METERS], which is the *preference* — a
         * divided highway's carriageways run twenty metres apart for their
         * whole length.
         */
        const val MIN_OTHER_ROAD_METERS = 12.0

        /** How far a pin may be slid from where its maker wanted it. */
        const val SETTLE_REACH_METERS = 200.0

        /** And in what increments. Fine against the clearance it has to clear. */
        const val SETTLE_STEP_METERS = 20.0

        /**
         * Two bends nearer than this are one turn. `turnsAlong` reports the
         * sharpest point of each run of bend, and a junction taken in two
         * strokes can report twice; without this the second report would be
         * read as a turn to stay clear of and the junction would go unpinned.
         */
        const val SAME_TURN_METERS = 5.0

        private val EMPTY = 1.0..0.0
    }
}
