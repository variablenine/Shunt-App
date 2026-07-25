package app.shunt.app.drive

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.geo.bearingDegrees
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
) {
    init {
        require(chain.isNotEmpty()) { "drive chain must have at least the destination" }
    }

    private val cameras = cameras
    private val cameraTier = HashMap<Long, Int>() // camera id -> tier fired (0/1/2)
    private var targetIndex = 0
    private var arrived = false

    /** Progress along [routePolyline], so each fix only searches nearby segments. */
    private var nearestSegment = 0
    private var consecutiveOffRoute = 0
    private var offRoute = false

    /** Signals raised by this fix, in the order they should be acted on. */
    fun onLocation(update: LocationUpdate): List<DriveSignal> {
        if (arrived) return emptyList()
        val signals = mutableListOf<DriveSignal>()
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

        val speed = update.speedMetersPerSec ?: config.assumedSpeedMetersPerSec
        val lead = maxOf(config.waypointLeadMinMeters, speed * config.waypointLeadSeconds)
        if (distance <= lead) {
            targetIndex++
            return DriveSignal.ApproachingWaypoint(chain.subList(targetIndex, chain.size).toList())
        }
        return null
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
    }
}
