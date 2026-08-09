package app.shunt.solver.geo

import app.shunt.core.GeoPoint
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.random.Random
import org.junit.jupiter.api.Test

/**
 * The index has to be *fast* and *exactly as correct* as the scan it replaces.
 * Speed is the point, but a wrong distance here silently changes which
 * stretches of a route count as divergent, so both are checked.
 */
class SpatialIndexTest {

    /** Matches PolylineIndex's default saturation point. */
    private val CAP = 5_000.0

    /** A wandering line of the size a real cross-state route reaches. */
    private fun longRoute(points: Int, seed: Int = 1): List<GeoPoint> {
        val random = Random(seed)
        var lat = 39.0
        var lon = -98.0
        return List(points) {
            lat += (random.nextDouble() - 0.45) * 0.0008
            lon += 0.0009
            GeoPoint(lat, lon)
        }
    }

    @Test
    fun `indexed distance matches the full scan`() {
        val line = longRoute(2_000)
        val index = PolylineIndex(line)
        val random = Random(7)

        repeat(300) {
            val probe = GeoPoint(
                39.0 + (random.nextDouble() - 0.5) * 0.4,
                -98.0 + random.nextDouble() * 1.8,
            )
            val exact = pointToPolyline(probe, line).distanceMeters
            val answer = index.distanceMeters(probe)
            if (exact < CAP) {
                assertEquals(exact, answer, absoluteTolerance = 0.5, message = "disagreed at $probe")
            } else {
                // Saturated: "at least this far" is all anyone asks for out here.
                assertTrue(answer >= CAP, "expected a saturated answer at $probe, got $answer")
            }
        }
    }

    @Test
    fun `a point right on the line reads as on it`() {
        val line = longRoute(500)
        val index = PolylineIndex(line)
        assertTrue(index.distanceMeters(line[250]) < 1.0)
    }

    @Test
    fun `indexing beats scanning on the comparison a plan actually makes`() {
        // This is the regression that matters. Comparing one route against
        // another point-by-point is quadratic, and at the sizes a real trip
        // reaches it took long enough to trip Android's "isn't responding".
        // The real query is a route against a route, so the probes track the
        // line rather than being scattered over the map.
        val line = longRoute(4_000)
        val probes = line.map { GeoPoint(it.lat + 0.004, it.lon) } // a parallel detour
        val index = PolylineIndex(line)

        val scanned = timeOf { probes.forEach { pointToPolyline(it, line) } }
        val indexed = timeOf { probes.forEach { index.distanceMeters(it) } }

        // Deliberately loose. The real speedup is an order of magnitude, but
        // this runs on shared CI hardware where a single measurement swings by
        // a factor of two or more; a tight bound here fails for reasons that
        // have nothing to do with the code. Four times is still far outside
        // anything the unindexed scan could reach.
        assertTrue(
            indexed * 4 < scanned,
            "expected a large speedup; scan took ${scanned}ms, index took ${indexed}ms",
        )
    }

    @Test
    fun `a point far off the line still resolves without scanning everything`() {
        val line = longRoute(4_000)
        val probes = List(500) { GeoPoint(39.0 + it * 0.001, -110.0) } // nowhere near
        val index = PolylineIndex(line)

        val scanned = timeOf { probes.forEach { pointToPolyline(it, line) } }
        val indexed = timeOf { probes.forEach { index.distanceMeters(it) } }

        assertTrue(
            indexed * 2 < scanned,
            "far probes should saturate quickly: ${indexed}ms vs a ${scanned}ms scan",
        )
    }

    @Test
    fun `point lookups find everything within the radius`() {
        val random = Random(3)
        val points = List(2_000) {
            GeoPoint(39.0 + (random.nextDouble() - 0.5) * 0.5, -98.0 + (random.nextDouble() - 0.5) * 0.5)
        }
        val index = PointIndex(points) { it }
        val probe = GeoPoint(39.05, -98.05)
        val radius = 1_500.0

        val expected = points.filter { haversineMeters(probe, it) <= radius }.toSet()
        val found = index.near(probe, radius).toSet()

        assertTrue(
            found.containsAll(expected),
            "index missed ${expected.minus(found).size} point(s) it should have found",
        )
    }

    private fun timeOf(block: () -> Unit): Long {
        block() // warm up, so the comparison isn't measuring JIT
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }
}
