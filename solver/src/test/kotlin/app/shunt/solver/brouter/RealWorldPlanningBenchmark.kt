package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.camera.parseDeFlockTile
import app.shunt.solver.geo.BoundingBox
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Plans a real trip, against real map tiles and the real camera dataset, and
 * prints where the time went.
 *
 * Every performance decision in this project until now was made from a
 * screenshot of the app's own breakdown, because the development sandbox could
 * not reach the tile CDN. That produced at least one wrong diagnosis (grouping
 * cameras by site was expected to help and moved the number by 0.7 s) and a
 * good deal of reasoning where a measurement would have done. This is the
 * measurement.
 *
 * **Off by default, and it takes no coordinates from this repository.** It
 * needs real `.rd5` tiles, which are hundreds of megabytes, and it would
 * otherwise be a place for someone's actual travel to end up committed — see
 * the privacy rule in CLAUDE.md §3. Everything comes from the environment:
 *
 * ```
 * SHUNT_BENCH_DIR=/path/with/segments,car-vario.brf,lookups.dat,cameras.json
 * SHUNT_BENCH_FROM=lat,lon
 * SHUNT_BENCH_TO=lat,lon
 * ./gradlew :solver:test --tests '*RealWorldPlanningBenchmark*' -i
 * ```
 *
 * Tiles come from `https://brouter.de/brouter/segments4/`, cameras from
 * `https://cdn.deflock.me/regions/{lat}/{lon}.json`, and the profile pair is
 * the one shipped in `app/src/main/assets/brouter/`.
 */
@EnabledIfEnvironmentVariable(named = "SHUNT_BENCH_DIR", matches = ".+")
class RealWorldPlanningBenchmark {

    private val benchDir = File(System.getenv("SHUNT_BENCH_DIR"))

    private fun point(name: String): GeoPoint {
        val raw = requireNotNull(System.getenv(name)) { "$name must be set to \"lat,lon\"" }
        val (lat, lon) = raw.split(",").map { it.trim().toDouble() }
        return GeoPoint(lat, lon)
    }

    private fun cameras(): List<Camera> {
        val file = benchDir.listFiles { f -> f.name.endsWith(".json") && f.name.startsWith("cams") }
            ?.firstOrNull()
            ?: error("put a DeFlock region json (cams*.json) in $benchDir")
        return parseDeFlockTile(file.readText()).map { it.toCamera() }
    }

    @Test
    fun `plan a real trip and report where the time went`() = runTest(timeout = 15.minutes) {
        val from = point("SHUNT_BENCH_FROM")
        val to = point("SHUNT_BENCH_TO")
        val all = cameras()
        val router = BrouterRouter(
            segmentDir = File(benchDir, "segments"),
            profileDir = benchDir,
        )
        val planner = BrouterPlanner(
            route = { request -> router.route(request) },
            missingTiles = { emptyList() },
            camerasIn = { bbox -> all.filter { bbox.contains(it.location) } },
            lastPassTimings = { router.lastPassTimings },
        )

        val startedAt = System.currentTimeMillis()
        val outcome = planner.plan(from, to)
        val wall = System.currentTimeMillis() - startedAt

        println("=".repeat(66))
        println("planned in ${wall / 1000.0} s")
        when (outcome) {
            is PlanOutcome.Routes -> {
                outcome.timings?.let { t ->
                    t.stages.forEach { println("  %-34s %8.1f s".format(it.label, it.seconds)) }
                    println("  each search over the road graph")
                    t.routingPasses.forEach { println("    %-32s %8.1f s".format(it.label, it.seconds)) }
                }
                println("  options:")
                outcome.options.forEach {
                    println(
                        "    %-16s %6.1f km  %3d cameras  %d pins%s".format(
                            it.choice, it.distanceMeters / 1000.0, it.camerasPassed,
                            it.waypoints.size, if (it.hardAvoidanceFailed) "  (hard avoidance failed)" else "",
                        ),
                    )
                }
            }
            is PlanOutcome.Failed -> println("  FAILED: ${outcome.reason}")
            is PlanOutcome.NeedsDownload -> println("  missing tiles: ${outcome.tiles.map { it.fileName }}")
        }
        println("=".repeat(66))
    }

    /**
     * How long each avoidance pass needs when it is allowed to finish, and how
     * that scales with the size of the camera set.
     *
     * The question this settles: whether the fewest-cameras passes are *slow*
     * or *hopeless*. A weighted penalty leaves every road passable, so it must
     * terminate with a route given time; a hard block may genuinely have no
     * answer, and proving that is the most expensive thing the engine does.
     * Those two want completely different product behaviour and had been
     * indistinguishable from the app's own breakdown.
     */
    @Test
    fun `how long the avoidance passes need when allowed to finish`() = runTest(timeout = 60.minutes) {
        val from = point("SHUNT_BENCH_FROM")
        val to = point("SHUNT_BENCH_TO")
        val all = cameras()
        val patient = BrouterRouter(
            segmentDir = File(benchDir, "segments"),
            profileDir = benchDir,
            passBudgetMillis = 20 * 60_000L,
        )
        val spine = patient.route(RouteRequest(listOf(from, to))).firstOrNull()?.polyline
            ?: error("no direct route")

        for (widthKm in listOf(5, 15)) {
            val meters = widthKm * 1000.0
            val near = all.filter { c -> spine.any { app.shunt.solver.geo.haversineMeters(it, c.location) <= meters } }
                .map { CameraVision(it.location, it.directionDegrees) }
            println("--- corridor ${widthKm} km: ${near.size} cameras ---")
            val startedAt = System.currentTimeMillis()
            val routes = patient.route(RouteRequest(listOf(from, to), near))
            println("  total ${(System.currentTimeMillis() - startedAt) / 1000.0} s")
            patient.lastPassTimings.forEach { println("    %-34s %8.1f s".format(it.label, it.seconds)) }
            routes.forEach { println("    -> %-16s %6.1f km %3d cameras".format(it.choice, it.distanceMeters / 1000.0, it.distinctCamerasPassed)) }
        }
    }

    /**
     * Times the avoidance passes at a given corridor width and prints the
     * resulting geometry's fingerprint, so a change meant to be answer-
     * preserving can be shown to be one rather than asserted to be.
     */
    @Test
    fun `time and fingerprint the passes at one corridor width`() = runTest(timeout = 60.minutes) {
        val from = point("SHUNT_BENCH_FROM")
        val to = point("SHUNT_BENCH_TO")
        val widthKm = (System.getenv("SHUNT_BENCH_CORRIDOR_KM") ?: "5").toDouble()
        val all = cameras()
        val router = BrouterRouter(
            segmentDir = File(benchDir, "segments"),
            profileDir = benchDir,
            passBudgetMillis = 30 * 60_000L,
        )
        val spine = router.route(RouteRequest(listOf(from, to))).firstOrNull()?.polyline
            ?: error("no direct route")
        val meters = widthKm * 1000.0
        val near = all.filter { c -> spine.any { app.shunt.solver.geo.haversineMeters(it, c.location) <= meters } }
            .map { CameraVision(it.location, it.directionDegrees) }

        println("--- ${widthKm.toInt()} km corridor: ${near.size} cameras ---")
        val startedAt = System.currentTimeMillis()
        val routes = router.route(RouteRequest(listOf(from, to), near))
        println("  total ${(System.currentTimeMillis() - startedAt) / 1000.0} s")
        router.lastPassTimings.forEach { println("    %-34s %8.1f s".format(it.label, it.seconds)) }
        routes.forEach {
            // A cheap stable fingerprint of the geometry: identical routes hash
            // identically, and any difference at all shows up here.
            val hash = it.polyline.fold(17L) { acc, p ->
                acc * 31 + (p.lat * 1e6).toLong() * 31 + (p.lon * 1e6).toLong()
            }
            println("    -> %-16s %7.2f km %3d cameras  points=%d  fingerprint=%d".format(
                it.choice, it.distanceMeters / 1000.0, it.distinctCamerasPassed, it.polyline.size, hash))
        }
    }

    /**
     * How many cameras a corridor of each width actually draws in, on this
     * trip. The corridor is the single biggest lever on planning time, and its
     * width was picked from geometry rather than from counting.
     */
    @Test
    fun `report how many cameras each corridor width draws in`() = runTest(timeout = 15.minutes) {
        val from = point("SHUNT_BENCH_FROM")
        val to = point("SHUNT_BENCH_TO")
        val all = cameras()
        val router = BrouterRouter(File(benchDir, "segments"), benchDir)

        val spine = router.route(RouteRequest(listOf(from, to))).firstOrNull()?.polyline
            ?: error("no direct route; check the tiles cover this trip")
        println("direct road: ${spine.size} points")

        for (widthKm in listOf(5, 10, 15, 20, 30, 60)) {
            val meters = widthKm * 1000.0
            val bbox = BoundingBox.of(spine).expand(meters)
            val inBox = all.filter { bbox.contains(it.location) }
            val inCorridor = inBox.count { c -> spine.any { app.shunt.solver.geo.haversineMeters(it, c.location) <= meters } }
            println("  %3d km corridor: %6d in box, %6d in corridor".format(widthKm, inBox.size, inCorridor))
        }
    }
}
