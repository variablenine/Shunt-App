package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The indexed labelling has to give the *same answers* as walking every camera
 * over the whole route, not merely similar ones.
 *
 * This is the app's core claim — "this route passes no cameras" — so a speed-up
 * that quietly changes a count is worse than no speed-up. The naive version is
 * the definition; this pins the fast one to it over random geometry.
 */
class CameraLabellingParityTest {

    private fun route(seed: Int): List<GeoPoint> {
        val random = Random(seed)
        var lat = 39.0
        var lon = -98.0
        return (0..600).map {
            lat += (random.nextDouble() - 0.45) * 0.0009
            lon += 0.0006
            GeoPoint(lat, lon)
        }
    }

    private fun cameras(seed: Int, line: List<GeoPoint>): List<CameraVision> {
        val random = Random(seed + 977)
        return (0 until 120).map {
            // Scattered around the line: some on it, some far enough to be noise.
            val anchor = line[random.nextInt(line.size)]
            CameraVision(
                GeoPoint(
                    anchor.lat + (random.nextDouble() - 0.5) * 0.006,
                    anchor.lon + (random.nextDouble() - 0.5) * 0.006,
                ),
                directionDegrees = if (random.nextBoolean()) random.nextDouble() * 360.0 else null,
            )
        }
    }

    @Test
    fun `the indexed count matches walking every camera over the whole route`() {
        var everSeen = 0
        repeat(12) { seed ->
            val line = route(seed)
            val all = cameras(seed, line)
            val index = CameraIndex(all)

            val naive = all.count { it.seesRoute(line) }
            everSeen += naive
            assertEquals(naive, index.seeing(line).size, "seed $seed: camera count must not change")
        }
        // Without this the whole thing could agree on zero and prove nothing.
        assertTrue(everSeen > 0, "the fixtures must actually put cameras on the route")
    }

    @Test
    fun `exposure from the nearby subset matches exposure from every camera`() {
        var everExposed = 0
        // The filter that makes this fast drops cameras too far away to see any
        // part of the route. If the margin were wrong it would drop one that
        // could, and the route would read as less exposed than it is.
        repeat(12) { seed ->
            val line = route(seed)
            val all = cameras(seed, line)
            val index = CameraIndex(all)

            val nearby = index.within(line, CameraVision.OMNI_RANGE_M + CameraIndex.SAMPLE_METERS)
            val full = CameraVision.metersSeen(line, all).toInt()
            everExposed += full
            assertEquals(full, CameraVision.metersSeen(line, nearby).toInt(), "seed $seed: exposure must not change")
        }
        assertTrue(everExposed > 0, "the fixtures must actually produce exposure")
    }
}
