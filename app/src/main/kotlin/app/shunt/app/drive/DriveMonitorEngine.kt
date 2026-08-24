package app.shunt.app.drive

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.geo.bearingDegrees
import app.shunt.solver.geo.bearingDifference
import app.shunt.solver.geo.METERS_PER_DEGREE_LAT
import app.shunt.solver.geo.haversineMeters
import app.shunt.solver.geo.pointToSegmentMeters

/**
 * The safety-critical core, kept pure so it can be exhaustively tested without
 * a car, GPS, or the network.
 *
 * Waypoint advancement: the vehicle treats a waypoint as a stop and won't
 * consider it visited until parked there (and under driver assistance will
 * actually stop). So as the car approaches each intermediate waypoint we emit
 * [DriveSignal.ApproachingWaypoint] — early, by a configurable time lead, not
 * at the pin — carrying the remaining chain to push via advanceTo.
 *
 * Camera warnings: independent of the waypoint logic and entirely local, so
 * they keep working with no connectivity. Each camera warns at most twice —
 * an early tier and a closer escalated tier.
 *
 * Progress is assumed monotonic along the chain (ordinary driving); the target
 * pointer only moves forward.
 */
class DriveMonitorEngine(
    private val chain: List<GeoPoint>,
    cameras: List<Camera>,
    private val config: DriveMonitorConfig = DriveMonitorConfig(),
    /**
     * The planned route line, for off-route detection. Empty disables it (the
     * chain alone doesn't describe the roads taken between waypoints).
     */
    private val routePolyline: List<GeoPoint> = emptyList(),
    /**
     * Chain entries that are the driver's own stops. The vehicle treats every
     * waypoint as a place to park, which is why shaping pins are dropped on
     * approach — but a real stop is exactly where the driver means to end up,
     * so it must be left alone.
     */
    private val stopPoints: Set<GeoPoint> = emptySet(),
    /** Tiers already announced by the engine this one replaces. See [warnedTiers]. */
    alreadyWarned: Map<Long, Int> = emptyMap(),
) {
    init {
        require(chain.isNotEmpty()) { "drive chain must have at least the destination" }
    }

    private val cameras = cameras
    private val cameraTier = HashMap<Long, Int>() // camera id -> tier fired (0/1/2)

    /**
     * What has already been said about each camera, so a route that replaces
     * this one does not say it all again.
     *
     * A re-plan builds a fresh engine, and a fresh engine used to have no memory
     * of which cameras it had already warned about — so every camera still in
     * range got re-announced. During the closed-road loop, where re-plans came
     * one after another, that is what turned into alerts that would not stop.
     */
    val warnedTiers: Map<Long, Int> get() = cameraTier.toMap()
    private var targetIndex = 0
    private var arrived = false

    /**
     * How many fixes in a row the current waypoint has been behind the car and
     * getting further away, and how far away it was on the last fix. See
     * [strandedOn].
     */
    private var recedingFixes = 0
    private var lastTargetDistance = Double.MAX_VALUE
    private var recedingFrom: GeoPoint? = null

    /** Progress along [routePolyline], so each fix only searches nearby segments. */
    private var nearestSegment = 0
    init {
        cameraTier.putAll(alreadyWarned)
    }

    private var consecutiveOffRoute = 0
    private var offRoute = false

    /** Along-route distance at each vertex of [routePolyline]. */
    private val alongAt: DoubleArray = DoubleArray(routePolyline.size).also { out ->
        for (i in 1 until routePolyline.size) {
            out[i] = out[i - 1] + haversineMeters(routePolyline[i - 1], routePolyline[i])
        }
    }

    /**
     * For each waypoint, how far along the route the car must be before that
     * waypoint may be advanced past. `-inf` where there is no turn to commit to.
     *
     * **This is what stops Shunt pulling a car out of a turn lane.** Reported
     * from a real drive: stopped at a red light in a centre lane waiting to
     * turn, a little short of a waypoint just beyond the junction. The monitor's
     * lead distance has a floor of 150 m for crawling traffic, the car was
     * inside it and stationary, so the waypoint was advanced past — and the next
     * one was reachable by carrying straight on, so FSD moved to leave the turn
     * lane. A waypoint that is abandoned before the turn it exists to force is
     * worse than no waypoint at all: it actively steers the car the wrong way.
     *
     * Advancing early is still right in general — the car treats a waypoint as a
     * *stop* and will slow for it, which is the reason the lead exists — so this
     * does not delay the advance, it only refuses to do it before the turn is
     * behind the car.
     */
    private val commitAlong: DoubleArray = DoubleArray(chain.size) { Double.NEGATIVE_INFINITY }

    /**
     * How far along the route each waypoint sits.
     *
     * Found by walking forward from the previous waypoint rather than searching
     * the whole line, which is what keeps a route that crosses itself from
     * matching a waypoint to the wrong passage.
     */
    private val pinAlong: DoubleArray = DoubleArray(chain.size) { Double.MAX_VALUE }

    init {
        if (routePolyline.size >= 2) {
            var cursor = 0
            for (i in chain.indices) {
                val segment = nearestSegmentTo(chain[i], from = cursor)
                cursor = segment
                pinAlong[i] = alongWithin(chain[i], segment)
                // Never past the pin itself: a commit point beyond the thing it
                // guards could only be reached by arriving, and the arrival
                // valve already covers that case.
                commitAlong[i] = minOf(commitPointFor(segment), pinAlong[i])
            }
        }
    }

    /**
     * Index of the route *segment* nearest [p], searching forward from [from].
     *
     * **Segment, not vertex, and that is the whole point.** Snapping each pin to
     * its nearest vertex made `pinAlong` a vertex position rather than the pin's
     * own — which on a dense line is a metre or two and on a long straight
     * segment is the length of the segment. Two consequences, and a driver
     * reported both as one sentence: "the pin triggers early and then the next
     * pin after that fires shortly after".
     *
     *  - **Early**, because a pin rounded back to the start of the segment it
     *    sits on has a `pinAlong` short of where it really is, so the lead is
     *    satisfied that much sooner.
     *  - **In pairs**, because two pins inside the same long segment round to
     *    the *same* vertex. Their `pinAlong` is then identical, the gap between
     *    them is zero, and the moment one advances the next is already inside
     *    its lead — so it fires on the following fix.
     */
    private fun nearestSegmentTo(p: GeoPoint, from: Int): Int {
        val last = routePolyline.size - 2
        var best = from.coerceIn(0, last)
        var bestDistance = Double.MAX_VALUE
        for (i in best..last) {
            val d = pointToSegmentMeters(p, routePolyline[i], routePolyline[i + 1])
            if (d < bestDistance) { bestDistance = d; best = i }
        }
        return best
    }

    /**
     * Along-distance of the last turn before the waypoint at route vertex
     * [waypointVertex], or `-inf` when the approach is straight.
     *
     * Takes the *last* qualifying bend rather than the sharpest: what has to be
     * behind the car is the final decision point, and an earlier, sharper one is
     * already committed by the time that matters.
     */
    internal fun commitPointFor(waypointVertex: Int): Double {
        val target = alongAt[waypointVertex]
        var j = waypointVertex
        while (j > 0 && target - alongAt[j] <= config.turnCommitLookbackMeters) {
            // **Past the bend, not at it.** Standing at the junction the car
            // still has the choice the pin was placed to remove, so releasing
            // the aim there is the failure this gate exists to prevent — missed
            // by exactly this clearance.
            if (bendDegreesAt(j) > config.turnCommitDegrees) {
                return alongAt[j] + config.turnCommitClearanceMeters
            }
            j--
        }
        return Double.NEGATIVE_INFINITY
    }

    /** How sharply the route turns at vertex [j], measured over a fixed span. */
    private fun bendDegreesAt(j: Int): Double {
        val span = config.turnMeasureSpanMeters
        var a = j
        while (a > 0 && alongAt[j] - alongAt[a] < span) a--
        var b = j
        while (b < routePolyline.size - 1 && alongAt[b] - alongAt[j] < span) b++
        if (a == j || b == j) return 0.0
        val into = bearingDegrees(routePolyline[a], routePolyline[j])
        val outOf = bearingDegrees(routePolyline[j], routePolyline[b])
        return kotlin.math.abs(((outOf - into + 540.0) % 360.0) - 180.0)
    }

    /**
     * The not-yet-passed part of the chain — what the car should be steering
     * along right now. Empty once the whole chain is behind us.
     */
    fun remainingChain(): List<GeoPoint> =
        if (targetIndex > chain.lastIndex) emptyList() else chain.subList(targetIndex, chain.size).toList()

    /** The driver's own stops still ahead, in order. */
    fun remainingStops(): List<GeoPoint> = remainingChain().filter { it in stopPoints }

    /** Metres to the waypoint being steered to, or null once the chain is done. */
    fun metersToNextWaypoint(at: GeoPoint): Double? =
        chain.getOrNull(targetIndex)?.let { haversineMeters(at, it) }

    /**
     * Where along the route each waypoint still ahead would be handed to the
     * car, at [speedMetersPerSec].
     *
     * **Fixed for a given route, which is what makes it worth drawing.** The
     * lead is eighteen seconds at the *expected* speed, capped at half the gap
     * the pins were placed at, and then the turn-commit gate holds the advance
     * until the bend before the pin is behind the car, whichever comes later.
     *
     * These marks used to move with the speedometer, and a driver reported the
     * consequence: "waypoints are triggered way earlier than it shows on the
     * map". They were not lying to the driver about the rule, they were drawing
     * a rule whose answer changed between looking at the screen and reaching
     * the spot. Static, the mark on the map is the place it fires.
     *
     * The commit point wins where it is later, because that is what the monitor
     * does. A pin whose commit gate is unreachable shows its trigger at the pin
     * itself, which is honest: that is the arrival-radius valve firing.
     */
    fun triggerPoints(): List<GeoPoint> {
        if (routePolyline.size < 2 || alongAt.isEmpty()) return emptyList()
        return (targetIndex..chain.lastIndex).mapNotNull { i ->
            val pin = pinAlong.getOrNull(i) ?: return@mapNotNull null
            if (pin == Double.MAX_VALUE) return@mapNotNull null
            // The destination is arrived at, not advanced past — there is no
            // trigger to show, and drawing one would suggest the drive ends
            // early.
            if (i == chain.lastIndex || chain[i] in stopPoints) return@mapNotNull null
            val lead = leadMetersFor(i)
            val commit = commitAlong.getOrNull(i) ?: Double.NEGATIVE_INFINITY
            val at = maxOf(pin - lead, commit).coerceIn(0.0, alongAt.last())
            pointAtAlongRoute(at)
        }
    }

    /** The point [target] metres along the route line. */
    private fun pointAtAlongRoute(target: Double): GeoPoint? {
        if (routePolyline.isEmpty()) return null
        var lo = 0
        var hi = routePolyline.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (alongAt[mid] < target) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return routePolyline[0]
        // **Interpolated, not rounded to the vertex.**
        //
        // This is where the mark is *drawn*, and the advance it describes is
        // decided by `alongOf`, which projects the car into its segment and is
        // accurate to the metre. Returning the vertex at or past the target put
        // the mark at the far end of whatever segment the trigger fell inside —
        // and a route's segments are as long as the road is straight, so on a
        // motorway that is kilometres. The waypoint then fired, correctly, well
        // before the driver reached the ring: "still having waypoints firing
        // before I hit the trigger point".
        //
        // The same trap as `sampleSpine` and as `alongOf` before it: a
        // polyline's vertices say nothing about the road between them.
        val before = routePolyline[lo - 1]
        val after = routePolyline[lo]
        val span = alongAt[lo] - alongAt[lo - 1]
        if (span <= 0.0) return after
        val t = ((target - alongAt[lo - 1]) / span).coerceIn(0.0, 1.0)
        return GeoPoint(
            lat = before.lat + (after.lat - before.lat) * t,
            lon = before.lon + (after.lon - before.lon) * t,
        )
    }

    /** Metres to the nearest camera we're warning about, or null when there are none. */
    fun metersToNearestCamera(at: GeoPoint): Double? =
        cameras.minOfOrNull { haversineMeters(at, it.location) }

    /**
     * Every turn the route takes, as a distance along it.
     *
     * The same bend test the waypoint commit gate uses, applied to the whole
     * line rather than to the run before one waypoint — so a junction is a
     * junction by one definition wherever it is asked about.
     */
    private val turnsAlong: DoubleArray by lazy {
        if (routePolyline.size < 3) DoubleArray(0)
        else {
            val out = mutableListOf<Double>()
            for (j in routePolyline.indices) {
                if (bendDegreesAt(j) > config.turnCommitDegrees) {
                    // One entry per run of bend: a corner is several vertices.
                    if (out.isEmpty() || alongAt[j] - out.last() > TURN_MERGE_METERS) out += alongAt[j]
                }
            }
            out.toDoubleArray()
        }
    }

    /**
     * Metres of road left to the end of this chain, measured along the route.
     *
     * Along the route rather than as the crow flies, for the same reason
     * [metersLeftTo] is: a destination the line passes near on its way somewhere
     * else is not one the car is close to.
     */
    fun metersToEnd(at: GeoPoint): Double = metersLeftTo(chain.lastIndex, at)

    /**
     * Metres of road before the next turn ahead, or null when the route has no
     * turn left in it — which is clear road, not a blocker.
     */
    fun metersToNextTurn(at: GeoPoint): Double? {
        if (turnsAlong.isEmpty()) return null
        val here = alongOf(at)
        return turnsAlong.firstOrNull { it > here }?.minus(here)
    }

    /** Metres since the last turn behind, or null when none has been passed. */
    fun metersSinceLastTurn(at: GeoPoint): Double? {
        if (turnsAlong.isEmpty()) return null
        val here = alongOf(at)
        return turnsAlong.lastOrNull { it <= here }?.let { here - it }
    }

    /** True while the vehicle is judged to have left the planned route. */
    val isOffRoute: Boolean get() = offRoute

    /** Signals raised by this fix, in the order they should be acted on. */
    fun onLocation(update: LocationUpdate): List<DriveSignal> {
        if (arrived) return emptyList()
        val signals = mutableListOf<DriveSignal>()
        // Once per fix, before anything asks how far along the car is.
        updateProgress(update.point)
        // Route adherence first: leaving the route invalidates the camera
        // promise, and the caller may replace the plan because of it.
        routeAdherence(update)?.let { signals += it }
        advanceOrArrive(update)?.let { signals += it }
        signals += cameraWarnings(update)
        return signals
    }

    /**
     * Off-route detection with hysteresis: it takes several consecutive fixes
     * beyond [DriveMonitorConfig.offRouteMeters] to declare it, and rejoining
     * requires getting properly back on (a nearer threshold), so a car sitting
     * right at the boundary doesn't chatter alerts.
     */
    private fun routeAdherence(update: LocationUpdate): DriveSignal? {
        if (routePolyline.size < 2) return null
        val distance = distanceToRoute(update.point)

        if (offRoute) {
            if (distance <= config.backOnRouteMeters) {
                offRoute = false
                consecutiveOffRoute = 0
                return DriveSignal.BackOnRoute
            }
            return null // already reported; don't repeat every fix
        }

        if (distance > config.offRouteMeters) {
            consecutiveOffRoute++
            if (consecutiveOffRoute >= config.offRouteConsecutiveFixes) {
                offRoute = true
                return DriveSignal.OffRoute(update.point, distance)
            }
        } else {
            consecutiveOffRoute = 0
        }
        return null
    }

    /**
     * Metres from [p] to the planned line. Searches a window around the last
     * match — a cross-country route is tens of thousands of points and this
     * runs on every GPS fix — widening to a full scan when the window finds
     * nothing close, which covers a GPS jump or a long detour.
     */
    private fun distanceToRoute(p: GeoPoint): Double {
        fun scan(from: Int, to: Int): Pair<Double, Int> {
            var best = Double.MAX_VALUE
            var bestIndex = from
            for (i in from until to) {
                val d = pointToSegmentMeters(p, routePolyline[i], routePolyline[i + 1])
                if (d < best) { best = d; bestIndex = i }
            }
            return best to bestIndex
        }

        val last = routePolyline.size - 1
        val from = (nearestSegment - WINDOW_BEHIND).coerceIn(0, last)
        val to = (nearestSegment + WINDOW_AHEAD).coerceIn(0, last)
        var (best, bestIndex) = scan(from, to)

        // The window may simply have fallen behind (a fast stretch, or a long
        // way off route) — confirm against the whole line before declaring it.
        if (best > config.offRouteMeters) {
            val (fullBest, fullIndex) = scan(0, last)
            if (fullBest < best) { best = fullBest; bestIndex = fullIndex }
        }
        nearestSegment = bestIndex
        return best
    }

    private fun advanceOrArrive(update: LocationUpdate): DriveSignal? {
        if (targetIndex > chain.lastIndex) return null
        val target = chain[targetIndex]
        val distance = haversineMeters(update.point, target)
        if (target !== recedingFrom) {
            recedingFrom = target
            recedingFixes = 0
            lastTargetDistance = Double.MAX_VALUE
        }

        if (targetIndex == chain.lastIndex) {
            if (distance <= config.arrivalRadiusMeters) {
                arrived = true
                return DriveSignal.Arrived
            }
            return null
        }

        // A stop the driver asked for is not a pin to shed: let the car arrive.
        // Advance past it only once we're on top of it, so the rest of the trip
        // still follows.
        if (target in stopPoints) {
            if (distance <= config.arrivalRadiusMeters) {
                targetIndex++
                return DriveSignal.ReachedStop(target, chain.subList(targetIndex, chain.size).toList())
            }
            return null
        }

        val lead = leadMetersFor(targetIndex)
        // **How far there is left to drive, not how far away it is.**
        //
        // Straight-line distance is the wrong question wherever the route comes
        // back near itself — a cloverleaf, a switchback, a frontage road beside
        // the carriageway. The car can sit tens of metres from a waypoint it has
        // not reached yet and will not reach for another mile, and measuring
        // with a ruler says it has arrived. Distance along the route says it has
        // a mile to go, which is the truth the waypoint was placed against.
        // **A waypoint the car has driven past is not one to keep aiming at.**
        //
        // Everything above measures progress *along the route*, which is right
        // whenever the car is on it and useless the moment it is not: the
        // projection is forward-only and windowed, so a driver who takes over
        // and goes their own way stops making progress by that measure, and
        // `metersLeftTo` never falls below the lead again. The pin then sticks
        // for the rest of the drive. Reported as "it can still get caught up on
        // a previous waypoint and I'll have to exit and restart navigation".
        //
        // Deliberately checked before the lead rather than after: the whole
        // point is that the ordinary gates cannot fire in this state.
        if (strandedOn(update, distance)) {
            targetIndex++
            return DriveSignal.ApproachingWaypoint(chain.subList(targetIndex, chain.size).toList())
        }
        if (metersLeftTo(targetIndex, update.point) > lead) return null
        // Close enough to advance — but not until the turn this waypoint exists
        // to force is actually behind the car. Sitting at a light in a turn lane
        // is inside the lead and stationary, and advancing there hands the car a
        // target it can reach by going straight on. See [commitAlong].
        //
        // Being on top of the waypoint is the safety valve: if the car never
        // registers as past the commit point, it must still not be left aiming
        // at a waypoint it is sitting on, because the car would stop there. The
        // along-route test above still has to pass, so this cannot fire from the
        // other side of a loop.
        if (!pastCommitPoint(update.point) && distance > config.arrivalRadiusMeters) return null
        targetIndex++
        return DriveSignal.ApproachingWaypoint(chain.subList(targetIndex, chain.size).toList())
    }

    /**
     * Whether the car has plainly left the current waypoint behind.
     *
     * Two conditions together, and both are needed. **Behind** —
     * [DriveMonitorConfig.passedBehindDegrees] off the direction of travel — on
     * its own is true for a moment at every junction. **Receding** on its own is
     * true whenever a route bends away before coming back. Sustained for
     * [DriveMonitorConfig.passedFixes] together, they mean the pin is somewhere
     * the car is driving away from and has been for a quarter of a minute,
     * which no approach ever looks like.
     *
     * Reset whenever either stops holding, so an ordinary bend costs nothing.
     *
     * This is a safety net, not a route decision: it fires only where the
     * along-route gates cannot, and the pin it drops was going to be aimed at
     * for the rest of the drive otherwise. It never applies to the destination
     * or to a stop the driver asked for — both are handled before it, and both
     * are places the car is *meant* to arrive at.
     */
    private fun strandedOn(update: LocationUpdate, distance: Double): Boolean {
        val bearing = update.bearingDegrees
        val behind = bearing != null && kotlin.math.abs(
            bearingDifference(bearing, bearingDegrees(update.point, chain[targetIndex])),
        ) >= config.passedBehindDegrees
        recedingFixes = if (behind && distance > lastTargetDistance) recedingFixes + 1 else 0
        lastTargetDistance = distance
        return recedingFixes >= config.passedFixes
    }

    /**
     * How far short of the waypoint at [index] the monitor re-aims at the next
     * one.
     *
     * Two limits, and the second is the one that was missing. The **speed**
     * limit is what the lead is for: the car treats a waypoint as a stop and
     * will brake for it, so the aim moves on a few seconds out. The **gap**
     * limit is what keeps that from skipping pins — a lead longer than the
     * distance between two pins re-aims past the second one before the car has
     * even reached the first, so the pin never constrains anything and the turn
     * it was placed for is not forced.
     *
     * Those two were set from different things — spacing from camera density,
     * lead from speed — and a fast road through a watched corridor gets the
     * tightest spacing and the longest lead at the same time. See
     * [DriveMonitorConfig.waypointLeadGapFraction].
     *
     * Floored at the arrival radius so two nearly coincident pins cannot drive
     * the lead to nothing, which would leave the car aimed at a waypoint it is
     * sitting on — and it stops there.
     */
    internal fun leadMetersFor(index: Int): Double {
        // **The expected speed, not the current one.** See
        // DriveMonitorConfig.expectedSpeedMetersPerSec: a lead that tracked the
        // speedometer made every trigger point a moving target, which is
        // exactly what the driver could not calibrate against.
        val bySpeed =
            maxOf(config.waypointLeadMinMeters, config.expectedSpeedMetersPerSec * config.waypointLeadSeconds)
        val gap = gapBefore(index)
        if (gap == Double.MAX_VALUE) return bySpeed
        return minOf(bySpeed, gap * config.waypointLeadGapFraction)
            .coerceAtLeast(config.arrivalRadiusMeters)
    }

    /**
     * Along-route distance from the waypoint before [index] to it, or
     * [Double.MAX_VALUE] when there is no route line to measure along.
     *
     * The first waypoint is measured from the start of the route, which is where
     * the car set off from.
     */
    private fun gapBefore(index: Int): Double {
        val here = pinAlong.getOrNull(index) ?: return Double.MAX_VALUE
        if (here == Double.MAX_VALUE) return Double.MAX_VALUE
        val previous = if (index == 0) 0.0 else pinAlong[index - 1]
        if (previous == Double.MAX_VALUE) return Double.MAX_VALUE
        return (here - previous).coerceAtLeast(0.0)
    }

    /**
     * Metres of route still to drive before the waypoint at [index], or the
     * straight-line distance when there is no route line to measure along.
     *
     * Negative would mean the car is already past it, so it is clamped to zero —
     * past is as arrived as it gets.
     */
    private fun metersLeftTo(index: Int, at: GeoPoint): Double {
        val target = chain.getOrNull(index) ?: return Double.MAX_VALUE
        if (routePolyline.size < 2 || alongAt.isEmpty()) return haversineMeters(at, target)
        val there = pinAlong.getOrNull(index) ?: return haversineMeters(at, target)
        return (there - alongOf(at)).coerceAtLeast(0.0)
    }

    /**
     * How far along the route the car is: the vertex before it, plus how far it
     * has travelled *into* that segment.
     *
     * The projection is the whole of it. Rounding back to the segment's start
     * vertex is exact on a dense line and wildly wrong on a sparse one — a
     * re-planned leg or a straight hop between junctions is two points a
     * kilometre or two apart, and then the car reads as being at the start of
     * that hop until it reaches the far end. Every waypoint inside the hop is
     * then a kilometre further off than it really is, and none of them
     * advances. The same trap `sampleSpine` fell into in the planner: a
     * polyline's vertices say nothing about the road between them.
     */
    private fun alongOf(p: GeoPoint): Double = alongWithin(p, progressSegment)

    /** How far along the route [p] projects, taken within segment [segment]. */
    private fun alongWithin(p: GeoPoint, segment: Int): Double {
        val i = segment.coerceIn(0, routePolyline.size - 2)
        val a = routePolyline[i]
        val b = routePolyline[i + 1]
        val metersPerLon = METERS_PER_DEGREE_LAT * kotlin.math.cos(Math.toRadians(a.lat))
        val abEast = (b.lon - a.lon) * metersPerLon
        val abNorth = (b.lat - a.lat) * METERS_PER_DEGREE_LAT
        val apEast = (p.lon - a.lon) * metersPerLon
        val apNorth = (p.lat - a.lat) * METERS_PER_DEGREE_LAT
        val lengthSquared = abEast * abEast + abNorth * abNorth
        if (lengthSquared <= 0.0) return alongAt[i]
        val t = ((apEast * abEast + apNorth * abNorth) / lengthSquared).coerceIn(0.0, 1.0)
        return alongAt[i] + t * kotlin.math.sqrt(lengthSquared)
    }

    /**
     * Where the car is for the purposes of **progress**, as a segment index that
     * may only creep forward.
     *
     * **Separate from [nearestSegment], and that separation is the fix.** The
     * two questions look identical and are not: *how far am I from the line* is
     * honestly answered by the globally nearest segment, while *how far along am
     * I* must never teleport. [distanceToRoute] falls back to a full scan when
     * the window finds nothing close — right for the first question — and on a
     * route that comes back near itself the globally nearest segment can be one
     * the car has not reached yet.
     *
     * When that happened, `alongOf` jumped forward by kilometres, every pin
     * before the jump measured as zero metres away, and the monitor advanced
     * through the lot of them one per GPS fix. Reported from a real drive: "the
     * first waypoint triggered way too soon and the rest of them all got sent to
     * my car at once". The re-planned route in that log runs east, four
     * kilometres north-west, then back south-east — the car sitting at the start
     * is a few hundred metres from a segment most of the way through it.
     *
     * A drive is one direction along one line, so the same walk-forward
     * discipline `pinAlong` already uses when it locates the pins is what this
     * needs: search forward from where progress had reached, never back and
     * never past the window. A car that genuinely rejoins far ahead stalls this
     * instead, which is the safe direction — progress stops rather than
     * inventing itself, and off-route detection is what handles that case.
     */
    private var progressSegment = 0

    /**
     * Move [progressSegment] to the best match ahead of where it already is,
     * within [PROGRESS_WINDOW_METERS] of road.
     *
     * **A distance window, not a count of vertices.** Polyline density varies by
     * two orders of magnitude — a re-planned leg is points kilometres apart, a
     * city street is points every few metres — so a fixed number of segments is
     * a few hundred metres in one place and tens of kilometres in another. The
     * window has to be the thing it is actually bounding: how far a car can
     * plausibly have travelled since the last fix. A kilometre is far more than
     * that at any speed and any fix rate, and small enough that a road doubling
     * back a few kilometres later cannot be mistaken for this one.
     */
    private fun updateProgress(p: GeoPoint) {
        val last = routePolyline.size - 2
        if (last < 0) return
        val from = progressSegment.coerceIn(0, last)
        // **Measured from where the car is, not from the start of the segment
        // it is on**, and that distinction was a serious bug rather than a
        // refinement. A route's segments are as long as the road is straight —
        // on a motorway, kilometres — and anchoring the window at the segment's
        // start meant the *next* segment began beyond the window and was never
        // considered. Progress then saturated at the end of the current segment
        // and stopped, permanently, for the rest of the drive.
        //
        // Everything measured along the route went wrong from there, in both
        // directions at once: a pin ahead never got closer, so its advance never
        // fired ("doesn't really get triggered sometimes"), while every pin whose
        // own position was behind the frozen point read as already reached and
        // fired at once ("premature waypoint triggering, waaay too far ahead").
        // And the trigger marks on the map are drawn from the pins' fixed
        // positions, so they stayed right while the behaviour drifted — "waypoint
        // triggering overall just doesn't seem to line up with the map".
        val here = alongOf(p)
        val limit = here + PROGRESS_WINDOW_METERS
        var best = Double.MAX_VALUE
        var bestIndex = from
        var i = from
        // The segment the car is on is always a candidate, however long it is.
        while (i <= last && (i == from || alongAt[i] <= limit)) {
            val d = pointToSegmentMeters(p, routePolyline[i], routePolyline[i + 1])
            if (d < best) { best = d; bestIndex = i }
            i++
        }
        progressSegment = bestIndex
    }

    /** Whether the car is past the turn that the current waypoint depends on. */
    private fun pastCommitPoint(at: GeoPoint): Boolean {
        val commit = commitAlong.getOrNull(targetIndex) ?: return true
        if (commit == Double.NEGATIVE_INFINITY) return true
        if (routePolyline.size < 2) return true
        return alongOf(at) >= commit
    }

    private fun cameraWarnings(update: LocationUpdate): List<DriveSignal> {
        val out = mutableListOf<DriveSignal>()
        for (camera in cameras) {
            val distance = haversineMeters(update.point, camera.location)
            val tier = cameraTier[camera.id] ?: 0
            when {
                distance <= config.cameraImminentMeters && tier < 2 -> {
                    cameraTier[camera.id] = 2
                    out += DriveSignal.ApproachingCamera(camera, distance, sideOf(update, camera), imminent = true)
                }
                distance <= config.cameraWarnMeters && tier < 1 -> {
                    cameraTier[camera.id] = 1
                    out += DriveSignal.ApproachingCamera(camera, distance, sideOf(update, camera), imminent = false)
                }
            }
        }
        return out
    }

    /** Which side of travel the camera is on, if heading is known. */
    private fun sideOf(update: LocationUpdate, camera: Camera): Side? {
        val heading = update.bearingDegrees ?: return null
        val toCamera = bearingDegrees(update.point, camera.location)
        // Signed difference in (-180, 180]: positive = clockwise = to the right.
        val diff = ((toCamera - heading + 540.0) % 360.0) - 180.0
        return if (diff >= 0) Side.RIGHT else Side.LEFT
    }

    private companion object {
        /** Segments to search behind/ahead of the last match on each fix. */
        const val WINDOW_BEHIND = 50
        const val WINDOW_AHEAD = 400

        /**
         * How far along the road progress may move in a single fix.
         *
         * See [updateProgress]. Generous against what a car can cover between
         * fixes, tight against a route that comes back near itself.
         */
        /** Vertices of bend nearer than this are one corner, not several. */
        const val TURN_MERGE_METERS = 40.0

        const val PROGRESS_WINDOW_METERS = 1_000.0
    }
}
