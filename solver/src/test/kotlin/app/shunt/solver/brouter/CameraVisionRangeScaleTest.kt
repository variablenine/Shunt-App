package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.destinationPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The adjustable camera reach.
 *
 * Nobody publishes the read range of an ALPR and it varies with the lens, the
 * mounting height, the traffic speed and the weather — so the built-in figure is
 * a policy about standoff rather than a measurement, and a driver testing
 * sensitivities needs to be able to move it.
 */
class CameraVisionRangeScaleTest {

    private val camera = GeoPoint(39.0, -98.0)

    @Test
    fun `the scale moves how far a camera sees`() {
        val at = CameraVision(camera, directionDegrees = null)
        val doubled = CameraVision(camera, directionDegrees = null, rangeScale = 2.0)
        assertEquals(at.range * 2, doubled.range)

        // A point beyond the normal reach but inside the doubled one.
        val out = destinationPoint(camera, 90.0, CameraVision.OMNI_RANGE_M * 1.5)
        assertFalse(at.sees(out), "the fixture must be outside the default reach")
        assertTrue(doubled.sees(out), "widening the reach must actually widen what is seen")
    }

    @Test
    fun `narrowing lets a route pass closer`() {
        val at = CameraVision(camera, directionDegrees = null)
        val narrowed = CameraVision(camera, directionDegrees = null, rangeScale = 0.5)
        val near = destinationPoint(camera, 90.0, CameraVision.OMNI_RANGE_M * 0.75)

        assertTrue(at.sees(near))
        assertFalse(narrowed.sees(near), "narrowing the reach must actually narrow what is seen")
    }

    @Test
    fun `the default changes nothing`() {
        // Every existing call site omits the scale, and none of their behaviour
        // may shift because this exists.
        val plain = CameraVision(camera, directionDegrees = 90.0)
        assertEquals(CameraVision.DIRECTIONAL_RANGE_M, plain.range)
        assertEquals(plain.range, CameraVision(camera, 90.0, rangeScale = 1.0).range)
    }
}
