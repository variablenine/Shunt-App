package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.bearingDifference
import app.shunt.solver.geo.destinationPoint
import app.shunt.solver.geo.haversineMeters
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Merging several cameras into one blocked shape is only allowed to *grow* what
 * is blocked.
 *
 * Over-blocking costs a longer detour. Under-blocking prints "camera-free" over
 * a road a camera is watching, which is the one failure this app cannot have.
 * So the property under test is containment, not resemblance: every point any
 * member can see must fall inside the shape that replaces them.
 */
class CameraClusterTest {

    private val site = GeoPoint(39.0, -98.0)

    private fun near(bearing: Double, meters: Double) = destinationPoint(site, bearing, meters)

    @Test
    fun `several units on one gantry become one shape`() {
        // The real pattern behind this: six cameras from one operator inside
        // thirty metres, all watching the same approach, previously six separate
        // zones for one piece of road.
        val gantry = (0 until 6).map {
            CameraVision(near(it * 60.0, 8.0 + it), directionDegrees = 90.0 + it)
        }

        val clusters = clusterCameras(gantry)

        assertEquals(1, clusters.size, "one site, one shape")
        assertEquals(6, clusters.single().size)
    }

    @Test
    fun `cameras looking different ways are never merged`() {
        // Facing is the reason a route may pass *behind* a camera. Collapsing
        // opposite-facing units into one zone would delete real roads from
        // consideration, so they stay apart however close they are.
        val opposed = listOf(
            CameraVision(near(0.0, 3.0), directionDegrees = 0.0),
            CameraVision(near(180.0, 3.0), directionDegrees = 180.0),
        )

        assertEquals(2, clusterCameras(opposed).size)
    }

    @Test
    fun `an all-round camera is never merged into a facing one`() {
        val mixed = listOf(
            CameraVision(near(0.0, 3.0), directionDegrees = null),
            CameraVision(near(90.0, 3.0), directionDegrees = 90.0),
        )

        assertEquals(2, clusterCameras(mixed).size)
    }

    @Test
    fun `distant cameras stay separate`() {
        val apart = listOf(
            CameraVision(site, directionDegrees = null),
            CameraVision(near(0.0, CLUSTER_RADIUS_METERS * 4), directionDegrees = null),
        )

        assertEquals(2, clusterCameras(apart).size)
    }

    @Test
    fun `the merged shape contains everything its members can see`() {
        // The property the whole optimisation rests on, over random sites.
        val random = Random(20260809)
        repeat(300) {
            val omni = random.nextBoolean()
            val facing = if (omni) null else random.nextDouble() * 360.0
            val members = (0 until random.nextInt(2, 7)).map {
                CameraVision(
                    near(random.nextDouble() * 360.0, random.nextDouble() * CLUSTER_RADIUS_METERS / 2),
                    directionDegrees = facing?.plus((random.nextDouble() - 0.5) * FACING_TOLERANCE_DEGREES),
                )
            }

            for (cluster in clusterCameras(members)) {
                val covered = members.filter { m ->
                    haversineMeters(cluster.center, m.location) <= cluster.spreadMeters + 0.001
                }
                for (member in covered) {
                    // Reach: the furthest point the member sees, measured from
                    // the shape's centre, must be inside the shape's range.
                    val memberReach = haversineMeters(cluster.center, member.location) + member.range
                    assertTrue(
                        cluster.rangeMeters + 0.001 >= memberReach,
                        "range ${cluster.rangeMeters} must cover $memberReach",
                    )
                    // Bearing: a member standing off to one side sees round the
                    // corner of the centre's fan, and the widening must cover it.
                    val clusterFacing = cluster.directionDegrees
                    val memberFacing = member.directionDegrees
                    if (clusterFacing != null && memberFacing != null) {
                        val offset = abs(bearingDifference(clusterFacing, memberFacing))
                        assertTrue(
                            cluster.extraHalfAngleDegrees + 0.001 >= offset,
                            "widening ${cluster.extraHalfAngleDegrees}° must cover a $offset° offset",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a lone camera is unchanged by clustering`() {
        val one = CameraVision(site, directionDegrees = 42.0)

        val cluster = clusterCameras(listOf(one)).single()

        assertEquals(site, cluster.center)
        assertEquals(0.0, cluster.spreadMeters)
        assertEquals(42.0, cluster.directionDegrees)
        assertEquals(CameraVision.DIRECTIONAL_RANGE_M, cluster.rangeMeters)
    }
}
