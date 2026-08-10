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
     * Where the avoidance passes' time actually goes, now that the nogo scan is
     * indexed: the *search* getting bigger, or the per-link nogo lookup?
     *
     * On a real phone `fastest` takes 3.4 s and `blocked` 22.0 s over the same
     * graph, and the two explanations want opposite responses. If the lookup is
     * the cost there is still headroom in indexing; if it is the search space —
     * avoidance genuinely explores more roads and settles on a longer one — then
     * no amount of indexing helps and concurrency is the only lever left.
     *
     * Separated by moving every camera far north. The nogo *count* is identical,
     * so the per-link work is identical, but nothing is near the route, so the
     * search space is `fastest`'s. Whichever number it lands on is the answer.
     */
    @Test
    fun `is avoidance slow because of the lookup or the search`() = runTest(timeout = 60.minutes) {
        val from = point("SHUNT_BENCH_FROM")
        val to = point("SHUNT_BENCH_TO")
        val all = cameras()
        val router = BrouterRouter(
            segmentDir = File(benchDir, "segments"),
            profileDir = benchDir,
            passBudgetMillis = 30 * 60_000L,
        )
        val spine = router.route(RouteRequest(listOf(from, to))).firstOrNull()?.polyline
            ?: error("no direct route")
        val meters = 60_000.0
        val near = all.filter { c -> spine.any { app.shunt.solver.geo.haversineMeters(it, c.location) <= meters } }
            .map { CameraVision(it.location, it.directionDegrees) }
        // Same count, same shapes, nowhere near any road this trip would use.
        val displaced = near.map { CameraVision(GeoPoint(it.location.lat + 6.0, it.location.lon), it.directionDegrees) }

        println("--- ${near.size} cameras, real positions ---")
        var startedAt = System.currentTimeMillis()
        router.route(RouteRequest(listOf(from, to), near))
        println("  total ${(System.currentTimeMillis() - startedAt) / 1000.0} s")
        router.lastPassTimings.forEach { println("    %-34s %8.1f s".format(it.label, it.seconds)) }

        println("--- ${displaced.size} cameras, displaced 6 degrees north ---")
        startedAt = System.currentTimeMillis()
        router.route(RouteRequest(listOf(from, to), displaced))
        println("  total ${(System.currentTimeMillis() - startedAt) / 1000.0} s")
        router.lastPassTimings.forEach { println("    %-34s %8.1f s".format(it.label, it.seconds)) }
    }

    /**
     * What overlapping the two avoidance passes buys, and what it costs in
     * memory — the only thing standing between here and turning it on
     * everywhere. Run this with `-Xmx` set to something a phone would recognise
     * before drawing conclusions from it.
     */
    @Test
    fun `what concurrency buys and what it costs in memory`() = runTest(timeout = 60.minutes) {
        val from = point("SHUNT_BENCH_FROM")
        val to = point("SHUNT_BENCH_TO")
        val all = cameras()

        val refineMs = (System.getenv("SHUNT_BENCH_REFINE_MS")
            ?: BrouterPlanner.REFINE_BUDGET_MILLIS.toString()).toLong()
        for (lanes in listOf(1, 2)) {
            val router = BrouterRouter(
                segmentDir = File(benchDir, "segments"),
                profileDir = benchDir,
                maxConcurrentPasses = lanes,
            )
            val planner = BrouterPlanner(
                route = { request -> router.route(request) },
                missingTiles = { emptyList() },
                camerasIn = { bbox -> all.filter { bbox.contains(it.location) } },
                lastPassTimings = { router.lastPassTimings },
                refineBudgetMillis = refineMs,
            )
            System.gc()
            Thread.sleep(500)
            val runtime = Runtime.getRuntime()
            val before = runtime.totalMemory() - runtime.freeMemory()
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val peakSeen = java.util.concurrent.atomic.AtomicLong(before)
            val watcher = Thread {
                while (running.get()) {
                    peakSeen.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }
                    Thread.sleep(50)
                }
            }
            watcher.isDaemon = true
            watcher.start()

            val startedAt = System.currentTimeMillis()
            val outcome = planner.plan(from, to)
            val wall = System.currentTimeMillis() - startedAt
            running.set(false)
            watcher.join()

            println("=== $lanes concurrent pass(es): ${wall / 1000.0} s (refine budget ${refineMs} ms) ===")
            println("    heap: ${before / 1024 / 1024} MB before, peak ${peakSeen.get() / 1024 / 1024} MB")
            (outcome as? PlanOutcome.Routes)?.let { r ->
                r.timings?.stages?.forEach { println("    %-22s %7.1f s".format(it.label, it.seconds)) }
                r.timings?.routingPasses?.forEach { println("      %-22s %7.1f s".format(it.label, it.seconds)) }
                r.options.forEach { option ->
                    println("      -> %-16s %6.1f km %3d cameras %d pins".format(
                        option.choice, option.distanceMeters / 1000.0, option.camerasPassed, option.waypoints.size))
                    if (option.waypoints.isEmpty()) return@forEach
                    // Where the pins actually fall. The budget is spent
                    // front-to-back, so if it binds, the far end of the trip —
                    // which on a trip into a metro is the dense end — goes
                    // unpinned. A histogram in tenths says whether that is
                    // happening far better than a count does.
                    val line = option.polyline
                    val total = line.zipWithNext().sumOf { (a, b) -> app.shunt.solver.geo.haversineMeters(a, b) }
                    val buckets = IntArray(10)
                    option.waypoints.forEach { pin ->
                        val along = app.shunt.solver.geo.pointToPolylineProgress(pin, line).alongMeters
                        buckets[((along / total) * 10).toInt().coerceIn(0, 9)]++
                    }
                    println("         by tenth of trip: ${buckets.joinToString(" ")}")
                }
            } ?: println("    $outcome")
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
