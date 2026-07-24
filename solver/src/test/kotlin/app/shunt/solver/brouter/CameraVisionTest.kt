package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.destinationPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraVisionTest {

    private val camera = GeoPoint(39.0, -98.0)

    @Test
    fun `a directional camera sees in front but not behind`() {
        val facingNorth = CameraVision(camera, directionDegrees = 0.0)
        val ahead = destinationPoint(camera, 0.0, 60.0)   // due north, in view
        val behind = destinationPoint(camera, 180.0, 60.0) // due south, behind
        assertTrue(facingNorth.sees(ahead), "should see straight ahead")
        assertFalse(facingNorth.sees(behind), "should not see behind it")
    }

    @Test
    fun `the field of view spans 180 degrees`() {
        val facingNorth = CameraVision(camera, directionDegrees = 0.0)
        // 89° off the facing is just inside the 180° FOV; 91° is just outside.
        assertTrue(facingNorth.sees(destinationPoint(camera, 89.0, 60.0)))
        assertFalse(facingNorth.sees(destinationPoint(camera, 91.0, 60.0)))
    }

    @Test
    fun `beyond range is not seen even dead ahead`() {
        val facing = CameraVision(camera, directionDegrees = 0.0)
        assertTrue(facing.sees(destinationPoint(camera, 0.0, CameraVision.DIRECTIONAL_RANGE_M - 10)))
        assertFalse(facing.sees(destinationPoint(camera, 0.0, CameraVision.DIRECTIONAL_RANGE_M + 10)))
    }

    @Test
    fun `an unknown-facing camera sees all directions and keeps more distance`() {
        val omni = CameraVision(camera, directionDegrees = null)
        assertTrue(omni.sees(destinationPoint(camera, 180.0, 60.0)), "omni sees behind too")
        // Its range is larger than a directional camera's.
        assertTrue(CameraVision.OMNI_RANGE_M > CameraVision.DIRECTIONAL_RANGE_M)
        assertTrue(omni.sees(destinationPoint(camera, 270.0, CameraVision.DIRECTIONAL_RANGE_M + 10)))
    }

    @Test
    fun `seesRoute detects a route entering the field of view`() {
        val facingNorth = CameraVision(camera, directionDegrees = 0.0)
        // A road 50 m north of the camera, crossing east-west through its view.
        val p = destinationPoint(camera, 0.0, 50.0)
        val through = listOf(destinationPoint(p, 270.0, 200.0), destinationPoint(p, 90.0, 200.0))
        assertTrue(facingNorth.seesRoute(through))
        // The same road 50 m *south* (behind) is not seen.
        val q = destinationPoint(camera, 180.0, 50.0)
        val behind = listOf(destinationPoint(q, 270.0, 200.0), destinationPoint(q, 90.0, 200.0))
        assertFalse(facingNorth.seesRoute(behind))
    }

    @Test
    fun `metersSeen accumulates only the watched stretch`() {
        val omni = CameraVision(camera, directionDegrees = null)
        // A ~400 m E-W line passing right over the camera.
        val west = destinationPoint(camera, 270.0, 200.0)
        val east = destinationPoint(camera, 90.0, 200.0)
        val seen = CameraVision.metersSeen(listOf(west, east), listOf(omni))
        assertTrue(seen > 0.0, "the middle of the line is within range")
        assertTrue(seen < 420.0, "the ends are out of range, so not the whole line")
        assertEquals(0.0, CameraVision.metersSeen(listOf(west, east), emptyList()), 0.0)
    }
}
