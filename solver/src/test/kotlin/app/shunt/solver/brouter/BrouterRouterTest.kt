package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.destinationPoint
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic tests for the exposure measurement and asset install. Real
 * routing against an rd5 tile is exercised by a local harness, not CI (the
 * tile is a 10 MB binary we don't commit) — see docs/brouter-spike.md.
 */
class BrouterRouterTest {

    // A ~1 km E-W line built from real offsets (neutral test coordinates).
    private val start = GeoPoint(39.0, -98.0)
    private val end = destinationPoint(start, 90.0, 1_000.0)
    private val line = listOf(start, end)

    @Test
    fun `camera on the line counts as passed and contributes exposure`() {
        val onLine = destinationPoint(start, 90.0, 500.0) // midpoint, on the road
        val passed = BrouterRouter.minDistanceToLine(onLine, line)
        assertTrue(passed < 5.0, "camera on the line should be ~0 m away, was $passed")
        val exposure = BrouterRouter.exposureMeters(line, listOf(onLine), 75.0)
        assertTrue(exposure > 0.0, "a camera on the line should expose part of it")
    }

    @Test
    fun `camera well off the line is neither passed nor exposing`() {
        val offLine = destinationPoint(start, 0.0, 500.0) // 500 m due north
        assertTrue(BrouterRouter.minDistanceToLine(offLine, line) > 75.0)
        assertEquals(0.0, BrouterRouter.exposureMeters(line, listOf(offLine), 75.0), 0.001)
    }

    @Test
    fun `exposure only counts segments within the standoff radius`() {
        // Camera 60 m north of the midpoint: within a 75 m standoff, outside 40 m.
        val near = destinationPoint(destinationPoint(start, 90.0, 500.0), 0.0, 60.0)
        assertTrue(BrouterRouter.exposureMeters(line, listOf(near), 75.0) > 0.0)
        assertEquals(0.0, BrouterRouter.exposureMeters(line, listOf(near), 40.0), 0.001)
    }

    @Test
    fun `install writes the bundled profile and dictionary`() {
        val dir = Files.createTempDirectory("brouter-assets").toFile()
        try {
            BrouterAssets.install(dir)
            assertTrue(File(dir, "car-vario.brf").length() > 0, "profile not installed")
            assertTrue(File(dir, "lookups.dat").length() > 0, "lookups.dat not installed")
        } finally {
            dir.deleteRecursively()
        }
    }
}
