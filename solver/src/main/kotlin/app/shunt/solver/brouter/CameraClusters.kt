package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.Grid
import app.shunt.solver.geo.bearingDifference
import app.shunt.solver.geo.haversineMeters

/**
 * One nogo shape standing in for a group of cameras that sit on top of each
 * other.
 *
 * Real ALPR sites are not one camera. A single junction routinely carries half a
 * dozen units from the same operator within a few tens of metres, all watching
 * the same approach — six separate zones describing one piece of road.
 *
 * That matters because of what dominates routing cost. On a trip into a dense
 * metro the plain fastest search takes about three seconds, and the same search
 * carrying the camera set takes forty-three, whether the cameras are weighted or
 * impassable. Two completely different search spaces, near-identical cost: the
 * time is going into checking every expanded link against every zone, so the
 * count of zones *is* the cost.
 */
internal data class CameraCluster(
    val center: GeoPoint,
    /** How far the furthest member sits from [center]. */
    val spreadMeters: Double,
    /** Facing shared by every member, or null when the group watches all round. */
    val directionDegrees: Double?,
    val size: Int,
) {
    /** Range the shape must reach to contain every member's own reach. */
    val rangeMeters: Double
        get() = (if (directionDegrees == null) CameraVision.OMNI_RANGE_M else CameraVision.DIRECTIONAL_RANGE_M) +
            spreadMeters

    /**
     * Extra half-angle the fan needs to cover members standing off to the side
     * of the centre, plus the spread in their facings.
     *
     * A member [spreadMeters] away can see [spreadMeters] further round the
     * apex than the centre can; the arcsine converts that offset into the angle
     * it opens up.
     */
    val extraHalfAngleDegrees: Double
        get() = if (directionDegrees == null) {
            0.0
        } else {
            val ratio = (spreadMeters / CameraVision.DIRECTIONAL_RANGE_M).coerceIn(0.0, 1.0)
            Math.toDegrees(kotlin.math.asin(ratio)) + FACING_TOLERANCE_DEGREES
        }
}

/**
 * Group cameras that describe the same piece of road.
 *
 * Deliberately conservative, because merging is only ever allowed to *grow* the
 * blocked shape. Over-blocking costs a longer detour; under-blocking prints
 * "camera-free" over a road a camera watches, and only one of those is
 * recoverable.
 *
 * So a group forms only when its members are within [CLUSTER_RADIUS_METERS] of
 * each other **and** agree about where they are looking. Cameras with different
 * facings stay separate rather than collapsing into an all-round zone — the
 * whole point of tracking facing is that a route may pass *behind* a camera, and
 * merging that away would quietly delete real roads from consideration.
 */
internal fun clusterCameras(
    cameras: List<CameraVision>,
    radiusMeters: Double = CLUSTER_RADIUS_METERS,
): List<CameraCluster> {
    if (cameras.size < 2) {
        return cameras.map { CameraCluster(it.location, 0.0, it.directionDegrees, 1) }
    }
    val grid = Grid(radiusMeters, cameras.first().location.lat)
    val buckets = HashMap<Long, MutableList<CameraVision>>()
    for (camera in cameras) {
        buckets.getOrPut(grid.cellOf(camera.location)) { mutableListOf() }.add(camera)
    }

    val taken = HashSet<CameraVision>()
    val out = mutableListOf<CameraCluster>()
    for (camera in cameras) {
        if (!taken.add(camera)) continue
        val members = mutableListOf(camera)
        for (cell in grid.cellsAround(camera.location, radiusMeters)) {
            for (other in buckets[cell].orEmpty()) {
                if (other in taken) continue
                if (haversineMeters(camera.location, other.location) > radiusMeters) continue
                if (!sameFacing(camera, other)) continue
                if (taken.add(other)) members += other
            }
        }
        out += fold(members)
    }
    return out
}

/** Two cameras describe the same view when both are all-round, or point alike. */
private fun sameFacing(a: CameraVision, b: CameraVision): Boolean {
    val da = a.directionDegrees
    val db = b.directionDegrees
    if (da == null || db == null) return da == null && db == null
    return kotlin.math.abs(bearingDifference(da, db)) <= FACING_TOLERANCE_DEGREES
}

private fun fold(members: List<CameraVision>): CameraCluster {
    val center = GeoPoint(
        members.sumOf { it.location.lat } / members.size,
        members.sumOf { it.location.lon } / members.size,
    )
    val spread = members.maxOf { haversineMeters(center, it.location) }
    // Averaging bearings through their components avoids the wrap at north
    // turning 350° and 10° into 180°.
    val direction = members.first().directionDegrees?.let {
        val x = members.sumOf { m -> kotlin.math.cos(Math.toRadians(m.directionDegrees!!)) }
        val y = members.sumOf { m -> kotlin.math.sin(Math.toRadians(m.directionDegrees!!)) }
        (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }
    return CameraCluster(center, spread, direction, members.size)
}

/**
 * How close two cameras must be to count as one site.
 *
 * Small on purpose. This is meant to catch the several units bolted to a single
 * gantry, not to generalise about a neighbourhood — the shape grows by the
 * spread, so a wide radius would blur genuinely separate cameras into one large
 * blocked area.
 */
internal const val CLUSTER_RADIUS_METERS = 35.0

/** How far two facings may differ and still describe the same view. */
internal const val FACING_TOLERANCE_DEGREES = 12.0
