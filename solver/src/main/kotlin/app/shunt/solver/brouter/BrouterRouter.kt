package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.destinationPoint
import app.shunt.solver.geo.haversineMeters
import btools.router.OsmNodeNamed
import btools.router.OsmNogoPolygon
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import java.io.File
import kotlin.math.cos

/** Which point on the camera-avoidance spectrum a route represents. */
enum class RouteChoice { FASTEST, BALANCED, FEWEST_CAMERAS }

/**
 * One routing option the user can pick. [distinctCamerasPassed] is the count of
 * cameras whose field of view the route enters; [exposureMeters] is the metres
 * driven within sight of any camera.
 */
data class BrouterRoute(
    val choice: RouteChoice,
    val polyline: List<GeoPoint>,
    val distanceMeters: Int,
    val estimatedSeconds: Int,
    val distinctCamerasPassed: Int,
    val exposureMeters: Int,
    /**
     * The engine was asked to treat every camera as impassable and came back
     * with nothing. This deliberately does not claim the cameras are proven
     * unavoidable: an endpoint inside a nogo or an engine failure has the same
     * raw outcome. Only set on the fewest-cameras fallback option.
     */
    val hardAvoidanceFailed: Boolean = false,
)

/**
 * On-device, offline camera-aware routing over BRouter. Each ALPR is a "nogo"
 * shaped by its [CameraVision]: a 180° sector polygon in the direction a camera
 * faces (so routes may pass behind it), or a larger full circle when the facing
 * is unknown. Higher weight = avoid harder; a single shortest-path pass yields
 * the minimum-exposure route with no greedy backtracking.
 *
 * [route] returns up to three distinct options — fastest, balanced, and
 * fewest-cameras — for the user to choose between.
 *
 * [segmentDir] holds the `.rd5` tiles; [profileDir] holds `car-vario.brf` and
 * `lookups.dat` (see [BrouterAssets]). Routing is blocking and CPU-bound; call
 * it off the main thread.
 */
class BrouterRouter(
    private val segmentDir: File,
    private val profileDir: File,
    private val profileName: String = "car-vario",
    /**
     * Ceiling on all the searches in one [route] call together.
     *
     * Enforced two ways, and only the second one actually bites. Between passes
     * Shunt checks the clock and skips what is left — but a search is a tight
     * CPU loop with no suspension point, so that check never arrives while one
     * pass is the thing running long. What bounds it is BRouter's own
     * `maxRunningTime`, which it tests on every node it expands; each pass is
     * given whatever is left of this budget.
     *
     * Passing zero there means *no limit*, and zero is what was passed for the
     * life of this project, which is why planning could run for twenty minutes
     * with a budget nominally in force.
     *
     * Running out only ever removes an *option*. It never changes how a route
     * that is returned was planned, and never lets one be labelled against
     * cameras it was not given — so the honest failure is a shorter chooser,
     * not a wrong one.
     */
    private val passBudgetMillis: Long = PASS_BUDGET_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Why the last [route] found nothing, for diagnostics — null after a success. */
    @Volatile
    var lastFailureDiagnostic: String? = null
        private set

    /**
     * How long each search over the road graph took, in the most recent [route]
     * call. Temporary diagnostic — see [PlanTimings].
     */
    @Volatile
    var lastPassTimings: List<PlanTimings.Timed> = emptyList()
        private set

    /**
     * Route through [points] — origin, any intermediate stops in order, then the
     * destination — returning up to three options.
     *
     * [headingDegrees] is the compass bearing the vehicle is actually travelling
     * on, when it is moving. Given it, the route has to set off the way the car
     * is already pointing instead of doubling back — a re-plan that answers with
     * a U-turn is worse than useless at 60 mph. Pass null when parked or
     * unknown: a stationary fix's bearing is noise, and pinning the route to it
     * would rule out the road behind for no reason.
     */
    fun route(
        points: List<GeoPoint>,
        cameras: List<CameraVision> = emptyList(),
        headingDegrees: Double? = null,
    ): List<BrouterRoute> = route(RouteRequest(points, cameras, headingDegrees))

    fun route(request: RouteRequest): List<BrouterRoute> {
        val points = request.points
        val cameras = request.cameras
        val headingDegrees = request.headingDegrees
        val blockedRoads = request.blocked
        val budgetMillis = request.budgetMillis ?: passBudgetMillis
        require(points.size >= 2) { "a route needs at least an origin and a destination" }
        lastFailureDiagnostic = null
        val timings = mutableListOf<PlanTimings.Timed>()
        val startedWholeAt = nowMillis()
        // Built once and shared by every option's labelling. Counting cameras
        // by asking each one to walk the whole route is cameras × points, and
        // it happens per option — on a trip into a dense metro that ran to tens
        // of seconds *inside the budget*, which is how the hard-block pass came
        // to be skipped for want of time it had not actually spent routing.
        val index = CameraIndex(cameras)
        /** What is left of the budget for the next search over the graph. */
        fun remaining(): Long = budgetMillis - (nowMillis() - startedWholeAt)

        /**
         * Run one search and record what it cost *and how it ended*.
         *
         * A pass that runs out of time and a pass that succeeds took the same
         * shape in the breakdown before this — both just a label and a duration
         * — so a timed-out pass read as a working one, and the missing option
         * looked like Shunt deciding there wasn't one. That is the exact failure
         * the breakdown exists to prevent, so the outcome is on the line now.
         *
         * [share] is the fraction of the remaining budget this pass may use, so
         * one expensive search cannot spend the whole allowance and leave
         * nothing for the ones that matter more.
         */
        fun pass(label: String, avoidance: Avoidance, share: Double = 1.0): RawRoute? {
            if (remaining() <= 0) {
                timings += PlanTimings.Timed("$label (skipped — over budget)", 0)
                return null
            }
            val ceiling = (remaining() * share).toLong().coerceAtLeast(1L)
            val startedAt = System.nanoTime()
            val outcome = runRoute(points, cameras, avoidance, headingDegrees, ceiling, blockedRoads)
            val took = (System.nanoTime() - startedAt) / 1_000_000
            val suffix = when {
                outcome.route != null -> ""
                outcome.timedOut -> " (gave up — out of time)"
                else -> " (no route)"
            }
            timings += PlanTimings.Timed(label + suffix, took)
            return outcome.route
        }

        val fastest = pass("fastest", Avoidance.None)?.toResult(RouteChoice.FASTEST, index)
        // With no cameras nearby there is only one sensible route.
        if (cameras.isEmpty()) {
            lastPassTimings = timings
            return listOfNotNull(fastest)
        }

        // The fewest-cameras option goes first, and that ordering is the whole
        // point of the app rather than a tuning choice.
        //
        // "Balanced" is a convenience — a middle road for someone willing to
        // trade a camera for time. "Fewest cameras" is the reason anyone
        // installed this. Running balanced first meant that on the trips where
        // the budget actually binds — long ones into dense metro, exactly where
        // avoidance is worth most — balanced spent the entire allowance, timed
        // out, produced nothing, and the option that mattered was never even
        // attempted. The driver was left with the plain fastest road.
        //
        // "Fewest cameras" must mean *none* whenever a camera-free path exists at
        // any distance. A weighted nogo can't promise that: BRouter charges
        // (metres inside the zone × weight), so a road clipping the edge of a
        // cone costs little and gets chosen over a long back-road detour — the
        // route then passes a camera that was in fact avoidable. Blocking the
        // zones outright makes the engine find the camera-free path or none.
        //
        // It is capped short of the whole budget because a hard block that finds
        // nothing is the most expensive outcome there is — it exhausts every
        // road reachable before concluding — and the fallback below is what
        // rescues that case. Spending everything here would starve it.
        // A hard block cannot route out of, or into, a point that is already
        // inside one of the zones it blocks — BRouter rejects such a request
        // outright. In a city centre the destination itself is often within
        // sight of a camera, and that is the case where the block is both
        // guaranteed to fail and most expensive to fail: it exhausts every
        // reachable road before saying so. On a real trip that was 42 seconds
        // spent proving something knowable in microseconds, and it starved the
        // fallback that would have produced a route.
        //
        // Sound rather than merely likely: the nogo shapes are built to
        // *contain* what CameraVision.sees covers, so a point that is seen is
        // certainly inside the block. A point that is not seen may still be
        // inside it, and that case simply runs as before.
        val endpointInsideZone = points.any { index.anySeeing(it) }
        val blocked = if (endpointInsideZone) {
            timings += PlanTimings.Timed("blocked (skipped — an endpoint is inside a camera's view)", 0)
            null
        } else {
            pass("blocked", Avoidance.Blocked, share = BLOCKED_BUDGET_SHARE)
                ?.toResult(RouteChoice.FEWEST_CAMERAS, index)
        }
        val fewest = blocked
            // No camera-free path exists (or an endpoint sits inside a zone,
            // which a hard block rejects outright) — fall back to avoiding as
            // hard as possible so the user still gets the best available, and
            // record that hard avoidance failed without claiming why it failed.
            ?: pass("fewest (fallback)", Avoidance.Weighted(FEWEST_WEIGHT))
                ?.toResult(RouteChoice.FEWEST_CAMERAS, index)
                ?.copy(hardAvoidanceFailed = true)

        // Last, on whatever is left: the option it is least costly to lose.
        val balanced = pass("balanced", Avoidance.Weighted(BALANCED_WEIGHT))
            ?.toResult(RouteChoice.BALANCED, index)

        lastPassTimings = timings

        // Fastest first, then the avoidance options — but only ones that are
        // genuinely a different road, each kept under its own truthful label
        // (so a 0-camera detour reads "fewest cameras", not "balanced").
        val result = mutableListOf<BrouterRoute>()
        fastest?.let { result += it }
        if (balanced != null &&
            result.none { sameRoute(it.polyline, balanced.polyline) } &&
            (fewest == null || !sameRoute(balanced.polyline, fewest.polyline))
        ) {
            result += balanced
        }
        if (fewest != null && result.none { sameRoute(it.polyline, fewest.polyline) }) {
            result += fewest
        }
        return result.ifEmpty { listOfNotNull(fastest) }
    }

    private data class RawRoute(val polyline: List<GeoPoint>, val distanceMeters: Int, val seconds: Int)

    /** How hard this pass should avoid camera zones. */
    internal sealed interface Avoidance {
        /** Ignore cameras entirely — the plain fastest route. */
        data object None : Avoidance

        /** Penalise metres driven inside a zone; a camera can still be accepted. */
        data class Weighted(val weight: Double) : Avoidance

        /**
         * Treat every zone as impassable (BRouter's NaN-weight nogo). Either the
         * route is camera-free or there is no route at all.
         */
        data object Blocked : Avoidance
    }

    private fun runRoute(
        points: List<GeoPoint>,
        cameras: List<CameraVision>,
        avoidance: Avoidance,
        headingDegrees: Double? = null,
        /**
         * Hard ceiling on this one search, handed to BRouter itself.
         *
         * This is the only thing that can actually stop a search: it is a tight
         * CPU loop with no suspension point, so nothing outside it can interrupt
         * it, and checking the clock *between* passes — which is all Shunt did
         * before — bounds nothing when a single pass is the thing running long.
         * BRouter checks this on every node it expands and throws, which
         * [runRoute] turns into "no route at this avoidance level".
         *
         * Zero means no limit. That was the value being passed, which is why a
         * budget appeared to do nothing at all.
         */
        timeoutMillis: Long,
        blocked: List<GeoPoint> = emptyList(),
    ): RunOutcome {
        return try {
            val rc = RoutingContext()
            // BRouter applies this by placing an imaginary previous position
            // 1 km back along the bearing, so its ordinary turn costs make
            // setting off backwards expensive. forceUseStartDirection is what
            // makes it apply to a full route and not only a partial recalc.
            headingDegrees?.let {
                rc.startDirection = normalizedBearing(it)
                rc.forceUseStartDirection = true
            }
            // Absolute .brf path => BRouter's null-profileBaseDir branch: no global
            // system property, and lookups.dat is read from the same directory.
            rc.localFunction = File(profileDir, "$profileName.brf").absolutePath
            val collector = RoutingParamCollector()
            // BRouter routes through the whole chain in one pass, so
            // intermediate stops are honoured natively.
            val waypoints = collector.getWayPointList(
                points.joinToString("|") { "${it.lon},${it.lat}" },
            )
            val nogos = mutableListOf<OsmNodeNamed>()
            if (avoidance != Avoidance.None && cameras.isNotEmpty()) {
                // NaN is BRouter's "impassable"; a finite value is a per-metre penalty.
                val weight = (avoidance as? Avoidance.Weighted)?.weight ?: Double.NaN
                nogos += buildNogos(cameras, weight, collector)
            }
            // Blocked roads apply to every pass, the plain fastest one included:
            // a road the driver has refused is refused on every option offered,
            // not only the ones that were avoiding something anyway.
            if (blocked.isNotEmpty()) nogos += buildBlocked(blocked, collector)
            if (nogos.isNotEmpty()) {
                RoutingContext.prepareNogoPoints(nogos)
                rc.nogopoints = nogos
            }
            val engine = RoutingEngine(null, null, segmentDir, waypoints, rc, 0)
            engine.quite = true // suppress BRouter's GPX-to-stdout dump
            engine.doRun(timeoutMillis.coerceAtLeast(1L))
            // BRouter catches its own timeout and reports it here rather than
            // throwing it out, so this is where "ran out of time" has to be told
            // apart from "no road goes there". Getting that wrong is how a
            // timed-out pass came to read as a proven absence of a route.
            engine.errorMessage?.let { return failed("brouter: $it", timedOut = "timeout" in it.lowercase()) }
            val track = engine.foundTrack ?: return failed("brouter: no track returned")
            val line = track.nodes.map { node ->
                GeoPoint(
                    lat = (node.getILat() - 90_000_000) / 1_000_000.0,
                    lon = (node.getILon() - 180_000_000) / 1_000_000.0,
                )
            }
            if (line.size < 2) return failed("brouter: track < 2 points")
            val seconds = track.getTotalSeconds().takeIf { it > 0 } ?: estimateSeconds(track.distance)
            RunOutcome(RawRoute(line, track.distance, seconds), timedOut = false)
        } catch (e: Throwable) {
            // BRouter signals its own maxRunningTime by throwing. Telling that
            // apart from a genuine "no road goes there" is the difference
            // between "try again" and "there isn't one".
            val message = e.message.orEmpty()
            note("exception: ${message.ifBlank { e.toString() }}")
            RunOutcome(null, timedOut = "timeout" in message.lowercase())
        }
    }

    /** What one search produced, and whether it simply ran out of time. */
    private data class RunOutcome(val route: RawRoute?, val timedOut: Boolean)

    /**
     * Nogos matching each camera's field of view: directional cameras get a
     * 180° sector polygon they face; unknown-facing cameras get a full circle.
     */
    internal fun buildNogos(
        cameras: List<CameraVision>,
        weight: Double,
        collector: RoutingParamCollector,
    ): List<OsmNodeNamed> {
        val nogos = mutableListOf<OsmNodeNamed>()

        // NaN must reach BRouter verbatim — it means "impassable". Formatting it
        // as an Int would silently become 0, i.e. a nogo with no effect at all.
        val weightSpec = if (weight.isNaN()) "NaN" else weight.toInt().toString()

        // One shape per *site*, not per unit. A junction routinely carries
        // several cameras from one operator within a few metres of each other,
        // all watching the same approach, and each was becoming its own zone —
        // which is pure cost, since what makes routing slow here is checking
        // every expanded link against every zone.
        val clusters = clusterCameras(cameras)

        val omni = clusters.filter { it.directionDegrees == null }
        if (omni.isNotEmpty()) {
            val spec = omni.joinToString("|") { c ->
                val radius = (c.rangeMeters + NOGO_MARGIN_METERS).toInt()
                "${c.center.lon},${c.center.lat},$radius,$weightSpec"
            }
            collector.readNogoList(spec)?.let { nogos.addAll(it) }
        }

        for (cluster in clusters) {
            val direction = cluster.directionDegrees ?: continue
            nogos += sectorPolygon(
                apex = cluster.center,
                directionDeg = direction,
                range = cluster.rangeMeters,
                weight = weight,
                extraHalfAngleDegrees = cluster.extraHalfAngleDegrees,
            )
        }
        return nogos
    }

    /**
     * Impassable circles over roads the driver cannot use.
     *
     * Small on purpose. Big enough to block the road it sits on, small enough
     * not to take a parallel street with it — err large here and a re-plan in a
     * town finds no route at all, which is a worse answer than the one this is
     * trying to improve on. [BrouterPlanner] retries without these if that
     * happens anyway.
     */
    internal fun buildBlocked(
        points: List<GeoPoint>,
        collector: RoutingParamCollector,
    ): List<OsmNodeNamed> {
        val spec = points.joinToString("|") { p ->
            "${p.lon},${p.lat},${BLOCKED_RADIUS_METERS.toInt()},NaN"
        }
        return collector.readNogoList(spec).orEmpty()
    }

    private fun RawRoute.toResult(choice: RouteChoice, index: CameraIndex): BrouterRoute {
        // Only cameras close enough to see some part of this route can add to
        // the exposure, so the rest are dropped before the metre-by-metre walk.
        // The margin is the index's own sampling step, which is what makes this
        // exact rather than merely close: the nearest sample to any point is
        // within half a step, so nothing that could contribute is filtered out.
        val nearby = index.within(polyline, CameraVision.OMNI_RANGE_M + CameraIndex.SAMPLE_METERS)
        return BrouterRoute(
            choice = choice,
            polyline = polyline,
            distanceMeters = distanceMeters,
            estimatedSeconds = seconds,
            distinctCamerasPassed = index.seeing(polyline).size,
            exposureMeters = CameraVision.metersSeen(polyline, nearby).toInt(),
        )
    }

    /** Record the first (fastest-attempt) failure reason and return null. */
    private fun note(reason: String): RawRoute? {
        if (lastFailureDiagnostic == null) lastFailureDiagnostic = reason
        return null
    }

    /** A search that produced nothing, and whether that was for want of time. */
    private fun failed(reason: String, timedOut: Boolean = false): RunOutcome {
        note(reason)
        return RunOutcome(null, timedOut)
    }

    companion object {
        /**
         * How long the avoidance passes get before the rest are abandoned.
         *
         * Measured on a real phone: a 470 km trip spends about 30 s across all
         * of them, so this leaves generous headroom for something longer while
         * still bounding the trips that used to run for many minutes and never
         * be seen to finish. A driver waiting on a route will wait a minute; at
         * twenty they have already closed the app, which means the answer was
         * worth nothing however good it was.
         */
        const val PASS_BUDGET_MILLIS = 75_000L

        /**
         * The same ceiling for a plan computed while the car is moving.
         *
         * A driver at the kerb will wait out a long search; one at 60 mph covers
         * a mile while it runs, and the junction the answer was for has already
         * gone by. Better a shorter chooser, now.
         */
        const val REPLAN_PASS_BUDGET_MILLIS = 12_000L

        /**
         * The share of the remaining budget the hard-block pass may spend.
         *
         * A hard block that finds nothing is the most expensive outcome the
         * engine has — it exhausts every reachable road before concluding — and
         * the weighted fallback is what rescues exactly that case. Letting the
         * block have everything would starve the thing that covers its failure.
         */
        const val BLOCKED_BUDGET_SHARE = 0.6

        /** Radius of an impassable circle over a road the driver has refused. */
        internal const val BLOCKED_RADIUS_METERS = 70.0

        // Nogo penalty per meter inside a camera's zone. Balanced accepts a
        // camera to save a big detour; fewest avoids hard where a path exists.
        private const val BALANCED_WEIGHT = 500.0
        private const val FEWEST_WEIGHT = 20_000.0

        /**
         * Blocked zones are grown by this much so they strictly contain the
         * footprint [CameraVision] counts. Erring outward costs a marginally
         * longer detour; erring inward silently prints "camera-free" over a
         * route that drives past a camera, so the direction is not a toss-up.
         */
        internal const val NOGO_MARGIN_METERS = 15.0

        /** Widen the blocked fan past the field of view, for the same reason. */
        private const val NOGO_ANGLE_MARGIN_DEGREES = 5.0

        /** Chords per fan; more means a tighter fit around the true arc. */
        private const val SECTOR_STEPS = 12

        /** ETA fallback if BRouter timing is unavailable: ~40 km/h town average. */
        private fun estimateSeconds(meters: Int): Int = (meters / (40_000.0 / 3600.0)).toInt()

        /**
         * A closed sector polygon covering a camera's field of view, built to
         * **contain** it rather than approximate it.
         *
         * This is load-bearing. `Avoidance.Blocked` promises that a route it
         * returns is camera-free, and that promise is only as good as the
         * agreement between the shape BRouter blocks and the shape
         * [CameraVision.sees] counts. An inscribed polygon is *smaller* than the
         * true sector, so a road clipping the arc is neither blocked nor
         * unseen — it comes back labelled "fewest cameras" while passing one.
         *
         * So every approximation errs outward: the arc radius is scaled to
         * circumscribe rather than inscribe, the fan is widened past the field
         * of view (which also covers the small all-round disc [CameraVision]
         * sees right at the lens), and a flat margin is added on top.
         */
        internal fun sectorPolygon(
            apex: GeoPoint,
            directionDeg: Double,
            range: Double,
            weight: Double,
            /** Widening needed when this fan stands in for several cameras. */
            extraHalfAngleDegrees: Double = 0.0,
        ): OsmNogoPolygon {
            val poly = OsmNogoPolygon(true)
            // Start the fan a little *behind* the lens rather than at it. A
            // camera sees all round at point-blank range (CameraVision.sees
            // ignores bearing within a couple of metres), which a strictly
            // forward fan cannot contain; backing the apex off covers that
            // pocket and only ever grows the zone.
            val back = destinationPoint(apex, directionDeg + 180.0, NOGO_MARGIN_METERS)
            poly.addVertex(lonToInt(back.lon), latToInt(back.lat))
            val halfAngle = (CameraVision.FOV_HALF_ANGLE + NOGO_ANGLE_MARGIN_DEGREES + extraHalfAngleDegrees)
                .coerceAtMost(180.0)
            // Push the vertices out so the chords sit outside the true arc.
            val outer = (range + NOGO_MARGIN_METERS) /
                cos(Math.toRadians(halfAngle / SECTOR_STEPS))
            for (i in 0..SECTOR_STEPS) {
                val bearing = directionDeg - halfAngle + (2 * halfAngle) * i / SECTOR_STEPS
                val edge = destinationPoint(apex, bearing, outer)
                poly.addVertex(lonToInt(edge.lon), latToInt(edge.lat))
            }
            poly.nogoWeight = weight
            poly.calcBoundingCircle()
            return poly
        }

        /** A compass bearing in 0..359, whatever the caller's sign convention. */
        internal fun normalizedBearing(degrees: Double): Int =
            (((degrees % 360.0) + 360.0) % 360.0).toInt()

        private fun lonToInt(lon: Double): Int = ((lon + 180.0) * 1_000_000.0 + 0.5).toInt()
        private fun latToInt(lat: Double): Int = ((lat + 90.0) * 1_000_000.0 + 0.5).toInt()

        /** Two routes are "the same" if their endpoints and length line up closely. */
        private fun sameRoute(a: List<GeoPoint>, b: List<GeoPoint>): Boolean {
            if (a.isEmpty() || b.isEmpty()) return a.size == b.size
            fun len(p: List<GeoPoint>): Double {
                var d = 0.0; for (i in 0 until p.size - 1) d += haversineMeters(p[i], p[i + 1]); return d
            }
            return haversineMeters(a.first(), b.first()) < 20 &&
                haversineMeters(a.last(), b.last()) < 20 &&
                kotlin.math.abs(len(a) - len(b)) < 50
        }
    }
}
