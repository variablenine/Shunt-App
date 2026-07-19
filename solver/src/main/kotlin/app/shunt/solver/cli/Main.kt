package app.shunt.solver.cli

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.DeFlockCameraSource
import app.shunt.solver.here.HereGeocoder
import app.shunt.solver.here.HereRoutingClient
import app.shunt.solver.routing.RouteSolver
import app.shunt.solver.routing.SolveResult
import app.shunt.solver.routing.SolverConfig
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

private const val USAGE = """
usage: solve --from "lat,lon" --to "lat,lon | address" [options]

options:
  --json               machine-readable output
  --radius METERS      camera buffer radius (default 40)
  --strict-direction   honor camera direction tags (default off)

HERE_API_KEY is read from the environment, .env, or local.properties.
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] != "solve") {
        System.err.println(USAGE.trim())
        exitProcess(2)
    }
    val opts = parseArgs(args.drop(1))
    val from = opts["from"] ?: fail("--from is required")
    val to = opts["to"] ?: fail("--to is required")
    val json = "json" in opts
    val radius = opts["radius"]?.toDoubleOrNull() ?: 40.0
    val strictDirection = "strict-direction" in opts

    val apiKey = loadHereApiKey() ?: fail("HERE_API_KEY not set (env, .env, or local.properties)")

    val http = OkHttpClient()
    val cacheDir = File(System.getProperty("user.home"), ".cache/shunt/deflock")
    val cameraSource = DeFlockCameraSource(http, cacheDir)
    val solver = RouteSolver(
        api = HereRoutingClient(http, apiKey),
        cameras = { bbox -> cameraSource.camerasIn(bbox).cameras },
        config = SolverConfig(bufferRadiusMeters = radius, strictDirection = strictDirection),
    )

    val result = runBlocking {
        val origin = parsePoint(from) ?: fail("--from must be \"lat,lon\", got: $from")
        val destination = parsePoint(to)
            ?: runCatching { HereGeocoder(http, apiKey).geocode(to) }
                .getOrElse { e -> fail("geocoding failed: ${e.message}") }
            ?: fail("could not geocode destination: $to")
        solver.solve(origin, destination)
    }

    if (json) printJson(result) else printHuman(result)
    http.dispatcher.executorService.shutdown()
    http.connectionPool.evictAll()
    if (result is SolveResult.Failed) exitProcess(1)
}

private fun parseArgs(args: List<String>): Map<String, String> {
    val opts = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (!arg.startsWith("--")) fail("unexpected argument: $arg")
        val name = arg.removePrefix("--")
        val hasValue = name in setOf("from", "to", "radius")
        if (hasValue) {
            if (i + 1 >= args.size) fail("$arg needs a value")
            opts[name] = args[i + 1]; i += 2
        } else {
            opts[name] = "true"; i += 1
        }
    }
    return opts
}

private fun parsePoint(s: String): GeoPoint? {
    val parts = s.split(",").map { it.trim() }
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lon = parts[1].toDoubleOrNull() ?: return null
    return runCatching { GeoPoint(lat, lon) }.getOrNull()
}

private fun loadHereApiKey(): String? {
    System.getenv("HERE_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
    for (name in listOf(".env", "local.properties")) {
        val file = File(name)
        if (!file.isFile) continue
        file.readLines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("HERE_API_KEY=") }
            ?.substringAfter("=")?.trim()?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }
    return null
}

private fun printHuman(result: SolveResult) = when (result) {
    is SolveResult.Clean -> {
        println("CLEAN — camera-free route found")
        println("  added time vs fastest: ${formatSeconds(result.addedSecondsVsFastest)}")
        println("  duration: ${formatSeconds(result.route.durationSeconds)}  length: ${result.route.lengthMeters} m")
        println("  waypoints (${result.waypoints.size}): " +
            result.waypoints.joinToString { "%.5f,%.5f".format(it.lat, it.lon) })
    }
    is SolveResult.MinimumExposure -> {
        println("MINIMUM EXPOSURE — no camera-free route exists for this trip")
        println("  this route passes ${result.passedCameras.size} camera(s):")
        result.passedCameras.forEach {
            println("    #${it.id} at %.5f,%.5f %s".format(
                it.location.lat, it.location.lon,
                it.tags["manufacturer"]?.let { m -> "($m)" } ?: ""))
        }
        println("  added time vs fastest: ${formatSeconds(result.addedSecondsVsFastest)}")
        println("  duration: ${formatSeconds(result.route.durationSeconds)}  length: ${result.route.lengthMeters} m")
        println("  waypoints (${result.waypoints.size}): " +
            result.waypoints.joinToString { "%.5f,%.5f".format(it.lat, it.lon) })
    }
    is SolveResult.Failed -> println("FAILED — ${result.reason}")
}

private fun printJson(result: SolveResult) {
    // Hand-rolled to keep the CLI's output stable and dependency-free.
    fun pts(points: List<GeoPoint>) =
        points.joinToString(",", "[", "]") { """{"lat":${it.lat},"lon":${it.lon}}""" }
    val body = when (result) {
        is SolveResult.Clean ->
            """{"outcome":"clean","addedSecondsVsFastest":${result.addedSecondsVsFastest},""" +
                """"durationSeconds":${result.route.durationSeconds},"lengthMeters":${result.route.lengthMeters},""" +
                """"waypoints":${pts(result.waypoints)}}"""
        is SolveResult.MinimumExposure ->
            """{"outcome":"minimum_exposure","cameraCount":${result.passedCameras.size},""" +
                """"cameras":${pts(result.passedCameras.map { it.location })},""" +
                """"addedSecondsVsFastest":${result.addedSecondsVsFastest},""" +
                """"durationSeconds":${result.route.durationSeconds},"lengthMeters":${result.route.lengthMeters},""" +
                """"waypoints":${pts(result.waypoints)}}"""
        is SolveResult.Failed ->
            """{"outcome":"failed","reason":${jsonString(result.reason)}}"""
    }
    println(body)
}

private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun formatSeconds(total: Int): String {
    val sign = if (total < 0) "-" else ""
    val t = kotlin.math.abs(total)
    return if (t >= 3600) "$sign${t / 3600}h ${(t % 3600) / 60}m" else "$sign${t / 60}m ${t % 60}s"
}

private fun fail(message: String): Nothing {
    System.err.println("error: $message")
    System.err.println(USAGE.trim())
    exitProcess(2)
}
