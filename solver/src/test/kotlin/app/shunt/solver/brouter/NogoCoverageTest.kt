package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.destinationPoint
import btools.router.OsmNogoPolygon
import btools.router.RoutingParamCollector
import java.io.File
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The blocked zone must **contain** what the counter sees.
 *
 * `Avoidance.Blocked` is what lets "fewest cameras" promise *none*: the engine
 * either finds a route outside every zone or finds nothing. That promise is
 * only as good as the agreement between the shape BRouter blocks and the shape
 * [CameraVision.sees] counts — and the two are computed by completely different
 * code. If the blocked polygon is even slightly smaller, a road clipping the
 * edge is neither blocked nor unseen, and the user is handed a route labelled
 * "fewest cameras" that drives straight past one. That is the exact failure
 * this file exists to prevent, so it checks the containment directly rather
 * than trusting that the two formulas happen to line up.
 */
class NogoCoverageTest {

    /** Neutral placeholder location; the geometry is location-independent. */
    private val camera = GeoPoint(39.0, -98.0)

    private fun sector(direction: Double): OsmNogoPolygon =
        BrouterRouter.sectorPolygon(camera, direction, CameraVision.DIRECTIONAL_RANGE_M, Double.NaN)

    private fun GeoPoint.asPolygonPoint(): Pair<Long, Long> =
        ((lon + 180.0) * 1_000_000.0 + 0.5).toLong() to ((lat + 90.0) * 1_000_000.0 + 0.5).toLong()

    /** buildNogos needs an instance; it never touches the tile or profile dirs. */
    private val router = BrouterRouter(File("no-segments"), File("no-profiles"))

    /** Every point the camera can see, on a fine polar sweep of its range. */
    private fun seenPoints(vision: CameraVision): List<GeoPoint> = buildList {
        var bearing = 0.0
        while (bearing < 360.0) {
            var distance = 1.0
            while (distance <= vision.range) {
                val p = destinationPoint(camera, bearing, distance)
                if (vision.sees(p)) add(p)
                distance += 3.0
            }
            bearing += 1.0
        }
    }

    /** Every point a camera anywhere can see, swept around its own location. */
    private fun seenPointsAround(vision: CameraVision): List<GeoPoint> = buildList {
        var bearing = 0.0
        while (bearing < 360.0) {
            var distance = 1.0
            while (distance <= vision.range) {
                val p = destinationPoint(vision.location, bearing, distance)
                if (vision.sees(p)) add(p)
                distance += 3.0
            }
            bearing += 1.0
        }
    }

    @Test
    fun `one shape standing in for several cameras still covers every one of them`() {
        // Several units on one gantry become a single nogo, because the count of
        // zones is what makes routing slow in a city. That is only allowed if
        // the shape that replaces them blocks at least as much: a gap here is a
        // road labelled camera-free with a camera watching it.
        val gantry = (0 until 6).map {
            CameraVision(
                destinationPoint(camera, it * 55.0, 6.0 + it * 3.0),
                directionDegrees = 90.0 + (it - 3) * 3.0,
            )
        }

        val nogos = router.buildNogos(gantry, Double.NaN, RoutingParamCollector())
        val polygons = nogos.filterIsInstance<OsmNogoPolygon>()
        // Without this the test would pass just as happily on six separate
        // shapes, proving nothing about the merge it exists to guard.
        assertTrue(
            polygons.size < gantry.size,
            "the gantry must actually collapse: ${polygons.size} shapes for ${gantry.size} cameras",
        )

        for (vision in gantry) {
            val escaped = seenPointsAround(vision).filterNot { p ->
                val (x, y) = p.asPolygonPoint()
                polygons.any { it.isWithin(x, y) }
            }
            assertTrue(
                escaped.isEmpty(),
                "${escaped.size} points this camera sees fell outside every blocked shape",
            )
        }
    }

    @Test
    fun `a directional camera's whole field of view is inside the blocked sector`() {
        // Several facings, so the fan isn't only checked where it happens to
        // line up with the lat/lon axes.
        for (direction in listOf(0.0, 45.0, 90.0, 137.0, 180.0, 271.0, 315.0)) {
            val vision = CameraVision(camera, direction)
            val polygon = sector(direction)
            val escaped = seenPoints(vision).filterNot { p ->
                val (x, y) = p.asPolygonPoint()
                polygon.isWithin(x, y)
            }
            assertTrue(
                escaped.isEmpty(),
                "facing $direction: ${escaped.size} point(s) the camera sees fall outside the " +
                    "blocked zone, so a road there would be counted but never avoided",
            )
        }
    }

    @Test
    fun `the blocked sector reaches past the range the camera actually reads`() {
        // The margin has to be real, not nominal: BRouter blocks whole ways, so
        // a zone that stops exactly at the detection edge leaves the road at
        // the edge passable.
        val polygon = sector(90.0)
        val justBeyond = destinationPoint(
            camera, 90.0, CameraVision.DIRECTIONAL_RANGE_M + BrouterRouter.NOGO_MARGIN_METERS / 2,
        )
        val (x, y) = justBeyond.asPolygonPoint()
        assertTrue(polygon.isWithin(x, y), "the safety margin must extend the blocked zone")
    }

    @Test
    fun `an unknown-facing camera is blocked all round, past its range`() {
        val vision = CameraVision(camera, directionDegrees = null)
        val nogos = router.buildNogos(listOf(vision), Double.NaN, RoutingParamCollector())
        val circle = nogos.single()

        // BRouter carries a circle's radius in the node name ("nogo165"); that
        // round-trip is what actually decides how much road gets blocked, and
        // an unparseable name silently falls back to 20 m.
        val radius = circle.name.removePrefix("nogo").toInt()
        assertTrue(
            radius > CameraVision.OMNI_RANGE_M,
            "blocked radius $radius m must exceed the ${CameraVision.OMNI_RANGE_M} m the camera sees",
        )
    }

    /**
     * The containment invariant has to hold **at every setting**, and this is
     * the case that was broken in shipped builds.
     *
     * `CameraVision.rangeScale` is a user setting, and everything that asked a
     * `CameraVision` directly honoured it — the counting, the drive warnings,
     * the cones on the map. The nogo shapes did not: `CameraCluster` read the
     * base constants straight off the companion object, so BRouter was handed
     * default-sized zones however far the slider was pushed. Turning it up made
     * Shunt report more cameras on a route it had made no attempt to move, which
     * is the containment failure this whole file exists to prevent, wearing the
     * one disguise none of the tests above could see through — they all use the
     * default scale, where the two agree.
     *
     * Reported from a real plan: "it is routing us past cameras that are
     * avoidable […] It is now [triggering them], but not changing the route."
     */
    @Test
    fun `the blocked shape follows the adjustable reach, not the built-in one`() {
        for (scale in listOf(0.5, 1.0, 2.0, 4.0)) {
            for (direction in listOf<Double?>(null, 0.0, 90.0, 217.0)) {
                val vision = CameraVision(camera, direction, rangeScale = scale)
                val nogos = router.buildNogos(listOf(vision), Double.NaN, RoutingParamCollector())
                val polygons = nogos.filterIsInstance<OsmNogoPolygon>()
                val circles = nogos - polygons.toSet()

                val escaped = seenPointsAround(vision).filterNot { p ->
                    val (x, y) = p.asPolygonPoint()
                    polygons.any { it.isWithin(x, y) } ||
                        circles.any { c ->
                            app.shunt.solver.geo.haversineMeters(camera, p) <=
                                c.name.removePrefix("nogo").toDouble()
                        }
                }
                assertTrue(
                    escaped.isEmpty(),
                    "scale $scale facing $direction: ${escaped.size} point(s) the camera sees fall " +
                        "outside every blocked zone, so a road there is counted but never avoided",
                )
            }
        }
    }

    @Test
    fun `a wider setting really does block more road`() {
        // The test above only proves the zone is big enough. This one proves it
        // *changed* — a shape that ignored the scale entirely would still
        // contain everything at 0.5, so containment alone cannot catch the bug.
        fun radiusAt(scale: Double): Double = router
            .buildNogos(
                listOf(CameraVision(camera, directionDegrees = null, rangeScale = scale)),
                Double.NaN,
                RoutingParamCollector(),
            )
            .single().name.removePrefix("nogo").toDouble()

        assertTrue(
            radiusAt(2.0) > radiusAt(1.0),
            "doubling the reach must widen the blocked circle: ${radiusAt(1.0)} m -> ${radiusAt(2.0)} m",
        )
        assertTrue(
            radiusAt(0.5) < radiusAt(1.0),
            "halving the reach must narrow the blocked circle: ${radiusAt(1.0)} m -> ${radiusAt(0.5)} m",
        )
    }

    @Test
    fun `blocking uses NaN verbatim, which is what makes a zone impassable`() {
        // Formatting the weight as an Int would turn NaN into 0 — a nogo with
        // no effect whatsoever, and every route would come back "camera-free".
        val nogos = router.buildNogos(
            listOf(CameraVision(camera, null)), Double.NaN, RoutingParamCollector(),
        )
        assertTrue(nogos.single().nogoWeight.isNaN(), "a blocked zone must reach BRouter as NaN")
    }
}
