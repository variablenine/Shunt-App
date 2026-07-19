package app.shunt.app.drive

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.geo.bearingDegrees
import app.shunt.solver.geo.haversineMeters

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
) {
    init {
        require(chain.isNotEmpty()) { "drive chain must have at least the destination" }
    }

    private val cameras = cameras
    private val cameraTier = HashMap<Long, Int>() // camera id -> tier fired (0/1/2)
    private var targetIndex = 0
    private var arrived = false

    /** Signals raised by this fix, in the order they should be acted on. */
    fun onLocation(update: LocationUpdate): List<DriveSignal> {
        if (arrived) return emptyList()
        val signals = mutableListOf<DriveSignal>()
        advanceOrArrive(update)?.let { signals += it }
        signals += cameraWarnings(update)
        return signals
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
}
