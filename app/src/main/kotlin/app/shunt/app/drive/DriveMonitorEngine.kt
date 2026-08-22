package app.shunt.app.drive

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.geo.bearingDegrees
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
                cursor = nearestVertex(chain[i], from = cursor)
                pinAlong[i] = alongAt[cursor]
                commitAlong[i] = commitPointFor(cursor)
            }
        }
    }

    /** Index of the route vertex nearest [p], searching forward from [from]. */
    private fun nearestVertex(p: GeoPoint, from: Int): Int {
        var best = from
        var bestDistance = Double.MAX_VALUE
        for (i in from until routePolyline.size) {
            val d = haversineMeters(p, routePolyline[i])
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
            if (bendDegreesAt(j) > config.turnCommitDegrees) return alongAt[j]
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

    /** Metres to the nearest camera we're warning about, or null when there are none. */
    fun metersToNearestCamera(at: GeoPoint): Double? =
        cameras.minOfOrNull { haversineMeters(at, it.location) }

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

        val speed = update.speedMetersPerSec ?: config.assumedSpeedMetersPerSec
        val lead = leadMetersFor(targetIndex, speed)
        // **How far there is left to drive, not how far away it is.**
        //
        // Straight-line distance is the wrong question wherever the route comes
        // back near itself — a cloverleaf, a switchback, a frontage road beside
        // the carriageway. The car can sit tens of metres from a waypoint it has
        // not reached yet and will not reach for another mile, and measuring
        // with a ruler says it has arrived. Distance along the route says it has
        // a mile to go, which is the truth the waypoint was placed against.
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
    internal fun leadMetersFor(index: Int, speedMetersPerSec: Double): Double {
        val bySpeed = maxOf(config.waypointLeadMinMeters, speedMetersPerSec * config.waypointLeadSeconds)
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
    private fun alongOf(p: GeoPoint): Double {
        val i = progressSegment.coerceIn(0, routePolyline.size - 2)
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
        val limit = alongAt[from] + PROGRESS_WINDOW_METERS
        var best = Double.MAX_VALUE
        var bestIndex = from
        var i = from
        while (i <= last && alongAt[i] <= limit) {
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
        const val PROGRESS_WINDOW_METERS = 1_000.0
    }
}
