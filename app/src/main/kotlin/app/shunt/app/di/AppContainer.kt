package app.shunt.app.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.shunt.BuildConfig
import app.shunt.app.drive.DriveActivity
import app.shunt.app.drive.DriveStatus
import app.shunt.app.drive.legExtensionChannel
import app.shunt.app.plan.CameraGateway
import app.shunt.app.plan.DrivePlan
import app.shunt.app.plan.LocationProvider
import app.shunt.app.plan.PlanViewModel
import app.shunt.app.plan.RoutePlanner
import app.shunt.app.plan.SuggestionSearch
import app.shunt.app.plan.TileDownloader
import app.shunt.app.ui.MapCamera
import app.shunt.solver.brouter.BrouterAssets
import app.shunt.solver.brouter.BrouterPlanner
import app.shunt.solver.brouter.BrouterRouter
import app.shunt.solver.brouter.BrouterTileSource
import app.shunt.solver.camera.Camera
import app.shunt.solver.camera.DeFlockCameraSource
import app.shunt.solver.camera.Freshness
import app.shunt.solver.charging.CHARGER_CORRIDOR_METERS
import app.shunt.solver.charging.SuperchargerSource
import app.shunt.solver.charging.rankChargeStops
import app.shunt.solver.geo.BoundingBox
import app.shunt.solver.geo.bearingDegrees
import app.shunt.solver.geo.haversineMeters
import app.shunt.app.diag.DiagnosticLog
import app.shunt.core.GeoPoint
import app.shunt.app.plan.Destination
import app.shunt.solver.brouter.CameraIndex
import app.shunt.solver.brouter.CameraVision
import app.shunt.solver.brouter.RouteRequest
import app.shunt.solver.brouter.LegJoin
import app.shunt.solver.brouter.LegSplitter
import app.shunt.solver.brouter.PlannedRoute
import app.shunt.solver.brouter.PlanOutcome
import app.shunt.solver.brouter.RouteChoice
import app.shunt.solver.camera.PracticeCameras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.shunt.solver.search.NominatimSearch
import app.shunt.solver.search.PhotonSearch
import app.shunt.solver.search.PlaceSearch
import app.shunt.tesla.ConnectionCheck
import app.shunt.tesla.FakeVehicleNavClient
import app.shunt.tesla.TessieAccountClient
import app.shunt.tesla.TessieVehicleNavClient
import app.shunt.tesla.VehicleNavClient
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The single place the app builds its dependencies. Everything downstream
 * takes its collaborators from here and never constructs a concrete client
 * itself. Swapping the fake vehicle client for the production one is the ONE
 * marked line below — the seam Part B drops into.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val http = OkHttpClient()

    private val cameraSource = DeFlockCameraSource(
        http = http,
        cacheDir = File(appContext.cacheDir, "deflock"),
    )

    // Keyless, OpenStreetMap-based destination search (no account, no card).
    // Photon drives the typeahead; Nominatim (fresher and more complete, but
    // rate limited and not for autocomplete) rescues queries Photon can't find.
    private val photonSearch = PhotonSearch(http)
    private val nominatimSearch = NominatimSearch(http)

    private val placeSearch = PlaceSearch(
        primary = { query, at -> photonSearch.suggest(query, at) },
        fallback = { query, at -> nominatimSearch.suggest(query, at) },
        // "coffee", "gas", "restroom" — answered by OSM tag near the driver
        // rather than by name, which is what made those searches useless.
        nearby = { tags, at -> photonSearch.nearby(tags, at) },
    )

    /**
     * A rolling week of what the app decided, for bug reports.
     *
     * Never uploaded and never scheduled — see [DiagnosticLog]. It lives in
     * private storage, expires by itself, and only leaves the phone if the
     * person holding it exports and sends it.
     */
    val diagnostics = DiagnosticLog(File(appContext.filesDir, "diagnostics/shunt.log"))

    /**
     * Practice mode: mix in a deterministic field of invented cameras.
     *
     * For testing avoidance somewhere the real ones have been removed — see
     * [PracticeCameras]. Off unless deliberately switched on, persisted so a
     * drive can be planned and then driven, and everything it produces is
     * tagged as not real all the way to the screen.
     */
    private val practicePrefs = appContext.getSharedPreferences("practice", Context.MODE_PRIVATE)

    /**
     * How far a camera is treated as seeing, as a percentage of the built-in
     * estimate. See `CameraRangeSetting` for why this is adjustable at all.
     */
    var cameraRangePercent: Int
        get() = practicePrefs.getInt("camera_range_percent", 100)
        set(value) { practicePrefs.edit().putInt("camera_range_percent", value).apply() }

    var practiceCameras: Boolean
        get() = practicePrefs.getBoolean("enabled", false)
        set(value) {
            practicePrefs.edit().putBoolean("enabled", value).apply()
            diagnostics.record(
                DiagnosticLog.Kind.CAMERA,
                if (value) "practice cameras ON — routes are against invented data" else "practice cameras off",
            )
        }

    /**
     * The real cameras, plus the practice field when it is on.
     *
     * One seam rather than a switch at each call site: the planner, the map and
     * the warm-up all ask the same question, and a mode that applied to some of
     * them and not others would show a route avoiding cameras the map does not
     * draw.
     */
    private suspend fun camerasFor(bbox: BoundingBox): List<Camera> {
        val result = cameraSource.camerasIn(bbox)
        // **A hole in the camera data is not a camera set.** A tile nothing
        // could supply used to come back as "no cameras here", and a route
        // planned through it is labelled camera-free while never having been
        // asked to avoid anything — the one failure CLAUDE.md §5 names outright.
        // Throwing puts it on the path the planner already has for this:
        // `camerasAlong` returns null and the plan says so out loud.
        if (result.missingTiles > 0) {
            diagnostics.record(
                DiagnosticLog.Kind.PLAN,
                "camera data has ${result.missingTiles} unloadable tile(s) over this area — refusing to " +
                    "plan against a set with a hole in it",
            )
            throw IOException("camera data incomplete: ${result.missingTiles} tile(s) unavailable")
        }
        // Loud when the answer looks thin, because "no cameras" and "we could
        // not see any" are the same shape on screen and only one of them is
        // safe. Every report of a route taking an avoidable camera has come
        // down to which of those it was, and the log could not say.
        if (result.cameras.isEmpty() || result.freshness != Freshness.NETWORK) {
            diagnostics.record(
                DiagnosticLog.Kind.PLAN,
                "cameras over a ${bboxKmLabel(bbox)} area: ${result.cameras.size} " +
                    "(${result.freshness.name.lowercase()})",
            )
        }
        val real = result.cameras
        if (!practiceCameras) return real
        // Snapped onto real roads using the tiles already on disk for routing,
        // which also thins them out to where the roads are — so a practice field
        // is dense in town and sparse in the country, like the real thing.
        return real + PracticeCameras.inBox(bbox) { points, meters ->
            brouterRouter.snapToRoads(points, meters)
        }
    }

    /**
     * How big a camera query's area is, in kilometres, without saying *where*.
     *
     * The size is the diagnostic — a hundred cameras over a city block and a
     * hundred over three states are very different answers — and the location is
     * the thing this log must not leak unless the person exporting asks for it.
     */
    private fun bboxKmLabel(bbox: BoundingBox): String {
        val north = haversineMeters(
            GeoPoint(bbox.minLat, bbox.minLon), GeoPoint(bbox.maxLat, bbox.minLon),
        ) / 1000
        val east = haversineMeters(
            GeoPoint(bbox.minLat, bbox.minLon), GeoPoint(bbox.minLat, bbox.maxLon),
        ) / 1000
        return "${east.toInt()}x${north.toInt()} km"
    }

    /** BRouter's offline tiles + profile live under the app's private storage. */
    private val brouterDir = File(appContext.filesDir, "brouter")
    private val brouterProfileDir = File(brouterDir, "profiles").apply {
        // Load bundled assets via AssetManager — getResourceAsStream is
        // unreliable on Android, which left BRouter without a profile (routes
        // silently came back empty as "no route found").
        runCatching { BrouterAssets.install(this) { name -> appContext.assets.open("brouter/$name") } }
    }
    private val tileSource = BrouterTileSource(http, File(brouterDir, "segments"))
    private val brouterRouter = BrouterRouter(
        segmentDir = tileSource.segmentDir,
        profileDir = brouterProfileDir,
        maxConcurrentPasses = concurrentRoutingPasses(),
    )
    private val brouterPlanner = BrouterPlanner(
        route = { request ->
            withContext(Dispatchers.Default) { brouterRouter.route(request) }
        },
        missingTiles = { bbox -> tileSource.missingTiles(bbox) },
        camerasIn = { bbox -> camerasFor(bbox) },
        diagnostics = { routingDiagnostic() },
        lastPassTimings = { brouterRouter.lastPassTimings },
        cameraRangeScale = { cameraRangePercent / 100.0 },
    )

    /** One-line on-disk + engine state, surfaced on a no-route failure (alpha aid). */
    private fun routingDiagnostic(): String {
        fun listing(dir: File): String =
            dir.listFiles()?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { "${it.name}=${it.length() / 1024}KB" }
                ?: "(empty)"
        return "profiles: ${listing(brouterProfileDir)} | segments: ${listing(tileSource.segmentDir)}" +
            (brouterRouter.lastFailureDiagnostic?.let { " | $it" } ?: "")
    }

    /** The user's Tessie token + VIN, entered in-app and stored encrypted. */
    val vehicleCredentials = VehicleCredentialsStore(appContext)

    private val tessieAccount = TessieAccountClient(http)

    /** Keyless OSM/Overpass source for Tesla charging sites. */
    private val superchargers = SuperchargerSource(http)

    /**
     * Verify a token by listing its vehicles. Read-only — it sends no command,
     * so checking can never make the car do something.
     */
    suspend fun checkVehicleToken(token: String): app.shunt.app.ui.VehicleCheckResult =
        when (val result = tessieAccount.check(token)) {
            is ConnectionCheck.Ok -> app.shunt.app.ui.VehicleCheckResult.Reachable(
                result.vehicles.map {
                    app.shunt.app.ui.VehicleOption(it.vin, it.displayName, it.isAwake)
                },
            )
            is ConnectionCheck.BadToken -> app.shunt.app.ui.VehicleCheckResult.BadToken(result.detail)
            is ConnectionCheck.Unreachable -> app.shunt.app.ui.VehicleCheckResult.Unreachable(result.detail)
        }

    /** What the car says it's navigating to. Read-only; never wakes it. */
    suspend fun readCarNavState(token: String, vin: String): app.shunt.app.ui.CarNavState? =
        tessieAccount.activeRoute(token, vin)?.let {
            app.shunt.app.ui.CarNavState(
                destinationName = it.destinationName,
                latitude = it.latitude,
                longitude = it.longitude,
                milesToArrival = it.milesToArrival,
                energyAtArrival = it.energyAtArrival,
                batteryLevel = it.batteryLevel,
                estimatedRangeMiles = it.estimatedRangeMiles,
            )
        }

    /**
     * Try every navigation channel against the car and report which it obeys.
     *
     * This is the experiment behind the whole waypoint design: Shunt steers pin
     * by pin because the chain commands appeared unsupported, but that was only
     * ever seen through one path. Each channel is sent for real and the car's
     * own state read back, so the answer comes from the car rather than from
     * inference. It redirects the car's navigation — the dialog says so.
     *
     * The two probe points are derived from wherever the phone is now, so
     * nothing about anyone's regular destinations is involved.
     */
    suspend fun probeNavCommands(
        token: String,
        vin: String,
        onLine: (app.shunt.app.ui.NavProbeLine) -> Unit,
    ) {
        val origin = locationProvider.currentOrigin()
        if (origin == null) {
            onLine(
                app.shunt.app.ui.NavProbeLine(
                    channel = "probe",
                    sent = "",
                    verdict = "No location yet — the probe needs somewhere nearby to aim at.",
                    landed = false,
                    detail = "",
                ),
            )
            return
        }
        // Two points a few km out on different bearings: near enough to be sane
        // destinations, far enough apart that the read-back can tell them apart.
        val a = app.shunt.solver.geo.destinationPoint(origin, 45.0, PROBE_NEAR_METERS)
        val b = app.shunt.solver.geo.destinationPoint(origin, 135.0, PROBE_FAR_METERS)
        val probe = app.shunt.tesla.NavCapabilityProbe(
            http = http,
            bearerToken = token,
            vin = vin,
            account = tessieAccount,
        )
        runCatching { probe.run(a, b) { onLine(it.toLine()) } }
            .onFailure { e ->
                onLine(
                    app.shunt.app.ui.NavProbeLine(
                        channel = "probe",
                        sent = "",
                        verdict = "Stopped: ${e.message ?: e.toString()}",
                        landed = false,
                        detail = "",
                    ),
                )
            }
    }

    /**
     * Credentials in force: what the user entered, else anything baked in at
     * build time (a developer convenience via local.properties).
     */
    fun effectiveCredentials(): VehicleCredentials {
        val entered = vehicleCredentials.credentials.value
        if (entered.isConfigured) return entered
        return VehicleCredentials(BuildConfig.TESSIE_TOKEN, BuildConfig.TESSIE_VIN)
    }

    /** True when no vehicle is configured, so Go runs against the fake client. */
    fun vehicleUnconfigured(): Boolean = !effectiveCredentials().isConfigured

    /**
     * The single vehicle-client seam. It delegates per call rather than being
     * built once, so saving credentials takes effect immediately — anything
     * holding this reference (the drive monitor, mid-trip) picks up the change
     * without an app restart. With none configured it stays on the fake, so a
     * keyless build and CI still work.
     */
    val vehicleNavClient: VehicleNavClient = object : VehicleNavClient {
        override suspend fun pushRoute(waypoints: List<app.shunt.core.GeoPoint>) =
            delegate().pushRoute(waypoints)

        override suspend fun advanceTo(remaining: List<app.shunt.core.GeoPoint>) =
            delegate().advanceTo(remaining)

        private var cachedFor: VehicleCredentials? = null
        private var cached: VehicleNavClient = FakeVehicleNavClient()

        @Synchronized
        private fun delegate(): VehicleNavClient {
            val creds = effectiveCredentials()
            if (creds != cachedFor) {
                cachedFor = creds
                cached = if (creds.isConfigured) {
                    TessieVehicleNavClient(http = http, bearerToken = creds.token, vin = creds.vin)
                } else {
                    FakeVehicleNavClient()
                }
            }
            return cached
        }
    }

    val favoritesStore = SharedPrefsFavoritesStore(appContext)
    val recentPlacesStore = SharedPrefsRecentPlacesStore(appContext)
    private val locationProvider: LocationProvider =
        AndroidLocationProvider(appContext, favoritesStore)

    /**
     * Drive-session handoff between the plan UI and the foreground service
     * (single process). The activity stashes the plan here on Go before
     * starting the service; the service reports lifecycle back via
     * [driveStatus], which the UI observes to leave the driving phase.
     */
    var activeDrivePlan: DrivePlan? = null
    val driveStatus = MutableStateFlow<DriveStatus>(DriveStatus.Idle)

    /**
     * What Shunt is doing with the car right now, for the driving sheet to
     * show. See [DriveActivity] — until this existed, every waypoint push and
     * charging probe was invisible unless it failed.
     */
    val driveActivity = MutableStateFlow<DriveActivity>(DriveActivity.Watching)

    /**
     * The route actually in force, republished whenever the monitor replaces it
     * — leaving the planned road, or a charging leg. The screen follows this
     * rather than the plan handed over at Go, which stops going stale the
     * moment anything re-plans.
     */
    val liveDrivePlan = MutableStateFlow<DrivePlan?>(null)

    /**
     * The pin the car is aiming at right now, so the map can keep it and the
     * driver in frame together. Null when no drive is running.
     */
    val aimedAt = MutableStateFlow<GeoPoint?>(null)

    /**
     * Legs planned after the driver set off, on their way to the drive monitor.
     *
     * **Unbounded, and it was conflated on a false premise.** The comment here
     * used to say only the newest mattered because each extension carried the
     * whole of what is left — it does not. `DriveMonitor.extend` *appends*:
     * `chain = remainingChain() + next.chain`, `polyline = polyline + next
     * .polyline`. Every leg is a delta, so a dropped one is a hole.
     *
     * That is reachable on any trip long enough to want it. Later legs are
     * planned **from the moment the chooser appears**, and each takes seconds,
     * while the monitor that drains this does not exist until Go is tapped. A
     * driver reading three options for a minute on an eight-leg trip produced
     * several legs into a channel holding one — and the drive then jumped from
     * the end of leg 1 to the start of whichever leg happened to be last,
     * skipping the cameras of everything between. It depended on how long
     * somebody looked at the screen, which is why it would have read as
     * "leg splitting is unreliable" rather than as a bug with a shape.
     */
    val legExtensions = legExtensionChannel()

    private var legJob: Job? = null

    /**
     * Which run of [planRemainingLegs] is the current one.
     *
     * **A cancelled job keeps running until it reaches a suspension point, and a
     * BRouter pass has none** — it is a tight CPU loop bounded only by its own
     * timeout. So `legJob?.cancel()` does not stop the previous run: it finishes
     * the leg it was computing, which can be a minute, and only then unwinds.
     * Everything it wrote on the way out landed on top of the run that replaced
     * it — its `finally` cleared [planningLaterLegs] while the new run was still
     * working, so the pending line vanished, and its leg was appended to the new
     * run's list, which is a leg planned to a trade-off nobody chose.
     *
     * Reported as the line disappearing when switching between the options and
     * again "after calculating a leg" — the delay between the two being exactly
     * how long the abandoned pass took to notice.
     */
    private var legRun = 0

    /**
     * The remaining trip the current run of [planRemainingLegs] was given.
     *
     * How "the same trip again" is recognised, so a re-plan for a different
     * trade-off can keep the legs already drawn while a genuinely new trip
     * cannot inherit the last one's.
     */
    private var legPoints: List<GeoPoint> = emptyList()

    /**
     * Later legs of the trip, as they are planned, for the map to draw.
     *
     * Held here rather than in the plan screen's state because planning outlives
     * that screen: the legs keep arriving after the driving sheet takes over.
     */
    val laterLegs = MutableStateFlow<List<PlannedRoute>>(emptyList())

    /**
     * Whether more legs of the current trip are still being planned.
     *
     * The map draws a dashed line from the end of what is planned to the
     * destination while this is true. It is a distinct fact from "the drawn
     * route stops short", which is why it is tracked rather than inferred: a
     * trip whose planning has *finished* short of the destination — because a
     * leg failed — must not keep promising something is on the way.
     */
    val planningLaterLegs = MutableStateFlow(false)

    /**
     * The first leg's line, shortened because the leg after it doubled back
     * over its tail — or null when no trim was needed, which is the usual case.
     *
     * Held separately because the first leg belongs to the plan screen, not
     * here: this is the container asking for it to be drawn shorter, and the
     * screen decides. See [LegJoin].
     */
    val trimmedLeadPolyline = MutableStateFlow<List<GeoPoint>?>(null)

    /**
     * The shown leg's pins, minus any that stood on the trimmed-away spur.
     *
     * A pin is a target the car gets aimed at, so one left behind on road that
     * has been cut would steer the vehicle back down it. See [LegJoin.pinsOn].
     */
    val trimmedLeadWaypoints = MutableStateFlow<List<GeoPoint>?>(null)

    /**
     * The direct road onward from the end of the **last leg planned**, for the
     * dashed pending line.
     *
     * Every plan already computes this — it is a slice of the spine it routed
     * to choose its own cut — but only the first leg's ever reached the map, so
     * the line kept describing the road onward from a boundary the driver was
     * hundreds of kilometres past. Two visible faults came from that, both
     * reported: on a trip over `SPINE_FULL_LIMIT_METERS` the first leg's slice
     * is mostly the *straight* estimate past where the probe stopped, so the
     * line did not follow roads at all; and the camera-avoiding legs wander off
     * the direct road, so the line left the route sideways to rejoin a chord
     * drawn from somewhere else — the "weird angle".
     *
     * Each leg replaces it with its own, which is what the planner's own comment
     * assumed was happening all along.
     */
    val laterLegDirectAhead = MutableStateFlow<List<GeoPoint>>(emptyList())

    /**
     * For work that outlives a screen but not the process — planning the later
     * legs of a trip in particular, which must survive the plan screen going
     * away when the driving sheet takes over.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Plan the rest of a trip while its first leg is being driven.
     *
     * Runs to the destination rather than one leg at a time: the phone is idle
     * while the car moves, and finishing early means a slow leg has hours of
     * slack rather than minutes. Each result is sent to the monitor, which
     * appends it to what is left of the chain.
     *
     * Everything here is best-effort. A leg that fails to plan leaves the driver
     * on the route they already have, heading for a boundary that will announce
     * itself as unfinished — which is bad, but is not the same as being stranded,
     * and is far better than blocking Go on the whole trip.
     */
    /**
     * Stop planning the rest of a trip and throw away what has been planned.
     *
     * Everything the trip left behind goes at once: the job, the legs the map is
     * drawing, and anything queued for the drive monitor. A cancelled trip that
     * kept a leg in the channel would have handed it to the *next* drive.
     */
    fun cancelRemainingLegs() {
        legRun++
        legJob?.cancel()
        legJob = null
        legPoints = emptyList()
        planningLaterLegs.value = false
        trimmedLeadPolyline.value = null
        trimmedLeadWaypoints.value = null
        laterLegs.value = emptyList()
        laterLegDirectAhead.value = emptyList()
        // Conflated, so at most one can be waiting — but draining is what makes
        // this true regardless of the channel's capacity.
        while (legExtensions.tryReceive().isSuccess) Unit
        liveDrivePlan.value = null
    }

    fun planRemainingLegs(
        points: List<GeoPoint>,
        destination: Destination,
        /**
         * The line of the leg the driver is being shown, so the leg after it
         * can be checked for doubling back over its tail. Empty simply means
         * no trim is attempted at that join.
         */
        leadPolyline: List<GeoPoint> = emptyList(),
        /** That leg's pins, so any standing on a trimmed spur go with it. */
        leadWaypoints: List<GeoPoint> = emptyList(),
        /**
         * Which trade-off the driver picked on the chooser.
         *
         * Later legs used to be planned as fewest-cameras regardless, so a
         * driver who deliberately chose Balanced still got legs that would
         * detour miles around a single camera — and had no way to say
         * otherwise, because the chooser only ever appears for the first leg.
         * Reported as an "unnecessary C detour over a more direct route just to
         * avoid one camera": that is precisely what fewest-cameras means, and
         * precisely what was not asked for.
         */
        preference: RouteChoice = RouteChoice.FEWEST_CAMERAS,
    ) {
        val run = ++legRun
        legJob?.cancel()
        // **Keep the legs already on the map when this is the same trip again.**
        //
        // Switching the chooser from fastest to camera-free re-plans every later
        // leg, and emptying the list first blanked the whole line back to the
        // first boundary for as long as the new ones took — which on a
        // cross-state trip is most of a minute of the map showing a route that
        // stops in open country. Reported as "the whole line disappears when I
        // switch between camera free and fastest".
        //
        // Carried only when the *remaining trip* is identical, which is exactly
        // the switch case: a genuinely new plan has different points, and
        // showing it the last trip's legs would be drawing a road nobody is
        // going to drive. They are replaced one by one as the new legs land and
        // any leftovers dropped when the run finishes, so the stale ones are
        // never the final answer.
        val carried = if (points == legPoints) laterLegs.value else emptyList()
        legPoints = points
        laterLegs.value = carried
        // Belongs to the legs being replaced. The chooser's own `directAhead`
        // takes over until the first new leg lands, which is the same road from
        // the same boundary — the legs after it are what differ.
        laterLegDirectAhead.value = emptyList()
        planningLaterLegs.value = false
        // The trim belongs to the option being replaced, so it cannot be
        // carried: it is a whole polyline for the lead leg, and keeping it would
        // draw the previous choice's route under the new one's label.
        trimmedLeadPolyline.value = null
        trimmedLeadWaypoints.value = null
        if (points.size < 2) {
            legPoints = emptyList()
            return
        }
        planningLaterLegs.value = true
        legJob = appScope.launch(Dispatchers.Default) {
            // This run's own legs. Kept separately from what the map is shown,
            // which may still carry the previous run's tail: the seam and trim
            // below must only ever look at legs this run planned, or a boundary
            // would be repaired against a road from the other trade-off.
            val planned = mutableListOf<PlannedRoute>()
            fun publish() {
                if (run != legRun) return
                laterLegs.value = planned + carried.drop(planned.size)
            }
            // Every exit from the loop below has to clear planningLaterLegs,
            // including cancellation — a flag left set draws a line to the
            // destination that nothing is ever going to fill in. Guarded by
            // [legRun] so an abandoned run cannot clear it out from under its
            // replacement.
            try {
            var points = points
            // The direction the leg before this one arrives at the boundary.
            //
            // **A later leg used to be planned with no heading at all**, and
            // that is a direct generator of the doubling-back this module then
            // spends two repair passes undoing. A boundary is chosen on the
            // *direct* road, and the camera-avoiding continuation often is not
            // on it — so with no cost attached to leaving the way the car
            // arrived, the cheapest route onto that continuation is frequently
            // back down the road just driven. Reported from a real plan as the
            // route leaving the line, going towards a camera, circling it out of
            // range and coming back.
            //
            // It biases rather than forbids: BRouter implements `startDirection`
            // by putting an imaginary previous position about a kilometre back
            // along the bearing so ordinary turn costs apply. So it will not on
            // its own rescue a boundary that is genuinely in the wrong place,
            // and the trim and the seam re-plan both stay.
            var arrivalBearing: Double? = leadPolyline.takeIf { it.size >= 2 }
                ?.let { bearingDegrees(it[it.lastIndex - 1], it[it.lastIndex]) }
            // Where this leg takes over from inside the leg before it, when the
            // leg before it was one of ours. See [LegJoin.handoverInto]: rather
            // than meeting the previous leg at a point chosen on the direct road
            // before either route existed, a leg starts a little way *inside* it
            // and re-decides that stretch as part of choosing its own first one.
            //
            // Null at the lead boundary, and deliberately. The lead leg is the
            // chooser's — it has been shown, and by the time this runs it may
            // have been pushed to the car — so shortening it is a change to a
            // route in progress, which is §6.1 territory. That one boundary
            // keeps the repair machinery below; every boundary after it is
            // planned right rather than repaired.
            var takeover: LegJoin.Handover? = null
            while (points.size >= 2 && isActive && run == legRun) {
                diagnostics.record(DiagnosticLog.Kind.PLAN, "planning the next leg (${points.size} points left)")
                val tookOver = takeover
                // The handover point replaces the boundary as this leg's start.
                val legPoints = tookOver?.let { listOf(it.point) + points.drop(1) } ?: points
                val outcome = runCatching {
                    brouterPlanner.plan(
                        points = legPoints,
                        headingDegrees = tookOver?.bearingDegrees ?: arrivalBearing,
                        maxLegMeters = LegSplitter.MAX_LEG_METERS,
                        // Nobody is watching a spinner for this one. The car has
                        // at least LegSplitter.MIN_LEG_METERS of road left when
                        // this is asked for — an hour at motorway speed — so the
                        // kerbside patience budget is the wrong measure entirely,
                        // and using it is how the last leg of a long trip came
                        // back as the plain fastest road.
                        routeBudgetMillis = BrouterRouter.LEG_PASS_BUDGET_MILLIS,
                    )
                }.getOrNull()
                if (outcome !is PlanOutcome.Routes || outcome.options.isEmpty()) {
                    // **Say which failure it was.** "Failed to plan" covers a
                    // routing error, a missing tile and an empty option list,
                    // and those want completely different responses — the first
                    // is a bug, the second is a download the driver can do, the
                    // third is a budget. A log that cannot tell them apart is a
                    // log that cannot be acted on, which is the whole point of
                    // keeping one.
                    diagnostics.record(
                        DiagnosticLog.Kind.PLAN,
                        when (outcome) {
                            null -> "next leg failed to plan: the planner threw"
                            is PlanOutcome.Failed -> "next leg failed to plan: ${outcome.reason}"
                            is PlanOutcome.NeedsDownload ->
                                "next leg failed to plan: ${outcome.tiles.size} map tile(s) missing"
                            else -> "next leg failed to plan: no options came back"
                        },
                    )
                    return@launch
                }
                // What every pass on this leg came back with, which is the
                // single most useful line in a report about a leg that took
                // cameras: it says whether an option to avoid them existed at
                // all, before anything downstream chose between them. The
                // chooser has logged this for the lead leg since the beginning
                // and later legs never did, which is why the leg into a metro
                // taking cameras took three rounds to pin down.
                diagnostics.record(
                    DiagnosticLog.Kind.PLAN,
                    "leg options: " + outcome.options.joinToString(", ") { o ->
                        "${o.choice} ${o.distanceMeters / 1000}km/${o.camerasPassed}cam" +
                            (if (o.unavoidableAtEndpoints > 0) "/${o.unavoidableAtEndpoints}@ends" else "") +
                            (if (o.hardAvoidanceFailed) "/hard-block-failed" else "")
                    },
                )
                // The trade-off the driver actually picked, carried to every
                // leg after the first. They only get one chooser, so it has to
                // mean something for the whole trip.
                //
                // **Fewest cameras means fewest cameras, not the option with
                // that name on it.** `choice` is the pass that produced the
                // geometry, and the pass names do not always rank the way they
                // read: when the hard block fails and the weighted fallback
                // takes over, the option carrying the FEWEST_CAMERAS label can
                // pass more cameras than the balanced one beside it. Asking for
                // the label there would hand the driver the worse route while
                // reporting it as the best one.
                val chosen = when (preference) {
                    RouteChoice.FEWEST_CAMERAS -> outcome.options.minByOrNull { it.camerasPassed }
                    else -> outcome.options.firstOrNull { it.choice == preference }
                        // That option may not exist on this leg — a stretch
                        // through empty country produces one route and nothing
                        // to choose between. Fall back toward the preference
                        // rather than past it: never hand someone more cameras
                        // than they asked for.
                        ?: outcome.options.minByOrNull { it.camerasPassed }
                } ?: return@launch
                // A leg that comes back holding nothing but the fastest road is
                // not a considered answer — it is every avoidance pass having run
                // out — and taking it silently is indistinguishable from Shunt
                // deciding there was no camera-free route. It is still handed
                // over, because a route is better than a boundary with nothing
                // past it, but it is said out loud. See CLAUDE.md §7.10.
                if (outcome.options.none { it.choice != RouteChoice.FASTEST } && chosen.camerasPassed > 0) {
                    // **Only when it cost something.** A leg through empty
                    // country also comes back with one option — every pass finds
                    // the same clean road and they deduplicate — and saying "NO
                    // avoidance option" about a camera-free leg is noise that
                    // buries the one time it matters. It fired on six clean legs
                    // of a real trip and once on the leg that actually took a
                    // camera, which is the wrong signal-to-noise ratio for the
                    // line that exists to catch exactly that.
                    diagnostics.record(
                        DiagnosticLog.Kind.PLAN,
                        "this leg has NO avoidance option — the fastest road is all that came back, " +
                            "and it passes ${chosen.camerasPassed} camera(s), " +
                            "${chosen.unavoidableAtEndpoints} of them watching an endpoint",
                    )
                }
                // Cut the out-and-back where this leg meets the one before it.
                //
                // A leg boundary is a hard waypoint chosen on the *direct* road,
                // which is not where a camera-avoiding route goes — so the
                // previous leg can drive out to touch it and this one come
                // straight back the same way. Reported from a real plan: "a leg
                // needs to go backwards after it found the way to the next
                // spot". Both legs are correct routes; the spur is the overlap.
                var leg = chosen
                val previousLegs = planned.toList()
                val earlier = previousLegs.lastOrNull()?.polyline
                    ?: trimmedLeadPolyline.value
                    ?: leadPolyline
                // Re-plan the neighbourhood of the boundary first, then fall
                // back to trimming an exact retrace if that finds nothing.
                //
                // **Only where this leg did not take over from the one before
                // it**, which is the lead boundary alone. Where it did, there is
                // no boundary to repair: this leg was planned from a point
                // inside the previous one and that one is cut back to meet it,
                // so the join is a vertex of both lines by construction. Running
                // the seam re-plan there would be three or four graph searches
                // spent proving there is nothing to find.
                // **The seam re-plan is skipped where this leg took over**, and
                // it is the only part that is: it costs three or four graph
                // searches to repair a boundary that no longer exists, because
                // this leg was planned from a point inside the previous one and
                // that one is cut back to meet it.
                //
                // **The trim still runs, everywhere.** It is pure geometry and
                // finds nothing when the join is clean, which makes it free
                // insurance rather than a duplicate — and a handover only makes
                // a retrace unlikely, it does not make one impossible. A route
                // out and back over the same road is the one artifact a driver
                // definitely should not be shown, so it is worth the metres of
                // arithmetic to be sure.
                val seamed = if (tookOver != null) null else rejoinAtBoundary(earlier, chosen.polyline)
                val trim = seamed ?: LegJoin.trimDoubleBack(earlier, chosen.polyline)
                if (trim.changed) {
                    diagnostics.record(
                        DiagnosticLog.Kind.PLAN,
                        if (seamed != null) {
                            "re-planned the leg boundary, saving ${trim.savedMeters.toInt()} m"
                        } else {
                            "trimmed a ${trim.savedMeters.toInt()} m double-back at the leg boundary"
                        },
                    )
                    // Pins go with the road they were placed on. A pin left
                    // out on the removed spur is not untidy, it is a target the
                    // car would be aimed at — steering it back down the very
                    // road the trim deleted. See LegJoin.pinsOn.
                    // Re-measured, because a splice puts road on the route that
                    // neither leg was labelled against. See [relabel].
                    leg = relabel(
                        chosen.copy(
                            polyline = trim.next,
                            waypoints = LegJoin.pinsOn(trim.next, chosen.waypoints),
                        ),
                    )
                    // Shorten the leg before it to match, so the two still meet.
                    if (previousLegs.isEmpty()) {
                        trimmedLeadPolyline.value = trim.previous
                        trimmedLeadWaypoints.value = LegJoin.pinsOn(trim.previous, leadWaypoints)
                    } else {
                        val earlierLeg = previousLegs.last()
                        planned[planned.lastIndex] = relabel(
                            earlierLeg.copy(
                                polyline = trim.previous,
                                waypoints = LegJoin.pinsOn(trim.previous, earlierLeg.waypoints),
                            ),
                        )
                        publish()
                    }
                }

                // **Give the tail of this leg to the next one, before anything
                // is published.** The stretch handed over is re-decided as part
                // of planning the leg after this, so nothing that reaches the
                // map or the car is ever revised — which is what makes handing
                // over safe rather than another change to a route in progress.
                //
                // Only when there *is* a next leg, and only when enough of this
                // one survives to still be a leg. See [LegJoin.handoverInto].
                val handover = if (outcome.remaining.size >= 2) {
                    LegJoin.handoverInto(leg.polyline)
                } else {
                    null
                }
                // The last stretch of this leg, which the next one replaces —
                // real road onward from where the drawn line now ends, so the
                // pending line has something to follow across the join.
                val handedOver = handover
                    ?.let { leg.polyline.subList(it.index, leg.polyline.size) }
                    .orEmpty()
                if (handover != null) {
                    val kept = LegJoin.truncateAt(leg.polyline, handover)
                    leg = relabel(
                        leg.copy(polyline = kept, waypoints = LegJoin.pinsOn(kept, leg.waypoints)),
                    )
                    // The handover point goes with it, so a boundary can be
                    // *found* on the map afterwards. Every artifact reported so
                    // far — the spur, the C, the loop around a camera, the pair
                    // of parallel lines — has come down to "is this at a leg
                    // boundary or not", and a log that cannot answer that turns
                    // every report into reading pixels. Locations are only
                    // written when the person exporting turns them on.
                    diagnostics.record(
                        DiagnosticLog.Kind.PLAN,
                        "handing ${(LegJoin.HANDOVER_METERS / 1000).toInt()} km of this leg to the next one",
                        listOf(handover.point.lat to handover.point.lon),
                    )
                }
                takeover = handover

                val legPlan = DrivePlan(
                    destination = destination,
                    // This leg's own end, not the trip's. `points.last()` is the
                    // final destination, so on a trip that takes three legs it
                    // put a waypoint hundreds of kilometres past the end of the
                    // leg being appended — the car would have been aimed at the
                    // destination the moment the extension landed.
                    chain = leg.waypoints +
                        (handover?.point ?: outcome.remaining.firstOrNull() ?: legPoints.last()),
                    // **This leg's own cameras, not the ones it was planned
                    // with.** `chosen` is the route before the trim, the seam
                    // and the handover reshaped it; `leg` is what the driver is
                    // actually being sent along, and it is what the monitor has
                    // to warn about.
                    cameras = leg.passedCameras,
                    polyline = leg.polyline,
                    // What is left starts where the next leg starts, which after
                    // a handover is inside this one rather than at the cut.
                    remaining = handover
                        ?.let { listOf(it.point) + outcome.remaining.drop(1) }
                        ?: outcome.remaining,
                )
                // Not from a run that has been replaced: the drive monitor
                // appends whatever arrives here, and a leg planned to a
                // trade-off the driver moved off would be spliced onto the one
                // they are actually on.
                if (run != legRun) return@launch
                legExtensions.trySend(legPlan)
                // Onto the map as well as into the drive. The line growing
                // toward the destination is the visible difference between
                // "still working" and "gave up", and it has to happen from a
                // standstill as readily as while moving.
                planned += leg
                publish()
                // The road onward from *this* leg's end, replacing whatever the
                // pending line was drawn along before it. The handed-over tail
                // comes first: it is road this leg actually routed, and without
                // it the line would jump the width of the handover in a straight
                // chord before picking the direct road up again.
                // **The road part only.** Every later leg's spine is a probe
                // bounded just past its own leg window, so most of its
                // `directAhead` is the straight estimate — and publishing that
                // replaced the chooser's road, which on a trip whose full spine
                // ran the whole way reached the destination. The plan screen
                // fills in beyond this from the chooser's slice. See F-46.
                laterLegDirectAhead.value =
                    handedOver + outcome.directAhead.take(outcome.directAheadRoadPoints)
                // The way this leg arrives at the next boundary, for the leg
                // after it. Taken from the trimmed line where a trim fired, so
                // it describes road that is still in the plan. A handover
                // carries its own bearing and takes precedence.
                arrivalBearing = leg.polyline.takeIf { it.size >= 2 }
                    ?.let { bearingDegrees(it[it.lastIndex - 1], it[it.lastIndex]) }
                diagnostics.record(
                    DiagnosticLog.Kind.PLAN,
                    "next leg ready: ${leg.distanceMeters / 1000} km, ${leg.camerasPassed} cameras",
                )
                if (outcome.remaining.size < 2) return@launch
                points = outcome.remaining
            }
            } finally {
                // Only the current run may speak for the trip. An abandoned one
                // reaches here long after it stopped being the answer — see
                // [legRun] — and clearing the flag then took the pending line
                // away while its replacement was still working.
                if (run == legRun) {
                    planningLaterLegs.value = false
                    // Whatever of the previous run's legs is still standing goes
                    // now: planning has stopped, so anything not planned by this
                    // run is not going to be replaced, and a leg from the other
                    // trade-off left on the map would be read as this one's.
                    laterLegs.value = planned.toList()
                }
            }
        }
    }

    /**
     * Re-measure which cameras a leg passes, against the line it **ends up
     * with**.
     *
     * **Every route Shunt shows is labelled by measuring it, and three things
     * here change a leg's line after that measurement was taken**: the trim
     * cuts a spur off it, the handover cuts its tail off, and the seam re-plan
     * splices in road that *neither* leg drove. The first two can only remove
     * road, so a stale label over-reports and errs safe. The third does not:
     * a camera on a spliced seam was drawn nowhere, counted nowhere and
     * announced never, on a leg the driver was told is camera-free.
     *
     * Reported exactly that way — "it labeled a route with a camera as camera
     * free again with an avoidable camera" — and this is the one rule the whole
     * app rests on: **a route is described by what it passes, never by what it
     * was planned against.** Re-measuring costs one cached camera lookup and
     * only happens when something actually moved the line.
     *
     * A lookup that fails leaves the old label rather than inventing a new one.
     * That is the status quo, and it is said out loud in the log — silently
     * showing a count nothing measured is what this exists to stop.
     */
    private suspend fun relabel(leg: PlannedRoute): PlannedRoute {
        if (leg.polyline.size < 2) return leg
        val bbox = BoundingBox.of(leg.polyline).expand(BrouterPlanner.NEARBY_CAMERA_METERS)
        val cameras = runCatching { camerasFor(bbox) }.getOrNull()
        if (cameras == null) {
            diagnostics.record(
                DiagnosticLog.Kind.PLAN,
                "could not re-check cameras after reshaping a leg — its count may be stale",
            )
            return leg
        }
        val scale = cameraRangePercent / 100.0
        val byLocation = cameras.associateBy { it.location }
        val index = CameraIndex(cameras.map { CameraVision(it.location, it.directionDegrees, scale) })
        val passed = index.seeing(leg.polyline).mapNotNull { byLocation[it.location] }
        if (passed.size > leg.passedCameras.size) {
            diagnostics.record(
                DiagnosticLog.Kind.PLAN,
                "reshaping this leg put it past ${passed.size - leg.passedCameras.size} more camera(s)",
            )
        }
        return leg.copy(
            passedCameras = passed,
            nearbyCameras = index.within(leg.polyline, BrouterPlanner.NEARBY_CAMERA_METERS)
                .mapNotNull { byLocation[it.location] },
        )
    }

    /**
     * Route the few kilometres either side of a leg boundary again, without the
     * boundary, and take the result only if it is better on **both** counts.
     *
     * The gain is real — the boundary is a constraint nobody asked for — but so
     * is the risk, because this is the one place a route is assembled from two
     * plans rather than produced by one. So the bar is deliberately high:
     *
     * - **Shorter**, by more than `MIN_SEAM_GAIN_METERS`, or the join was fine.
     * - **No more cameras**, measured against the same set the legs were planned
     *   against. A shorter join that picks up a camera is not an improvement on
     *   this app, it is the opposite of the point.
     * - **Drivable without pins.** The replacement is the *fastest* path through
     *   the window, which is what the car takes when left alone — see below for
     *   why that is the safe choice rather than the lazy one.
     *
     * Returns null on anything unexpected, which leaves the caller trimming as
     * before. A join that is merely imperfect is a far better outcome than one
     * assembled wrongly.
     */
    private suspend fun rejoinAtBoundary(
        earlier: List<GeoPoint>,
        next: List<GeoPoint>,
    ): LegJoin.Trimmed? = runCatching {
        val seam = LegJoin.seamOf(earlier, next) ?: return null
        val bbox = BoundingBox.of(listOf(seam.from, seam.to))
            .expand(BrouterPlanner.ROUTE_BBOX_MARGIN_METERS)
        val cameras = camerasFor(bbox)
        val scale = cameraRangePercent / 100.0
        val visions = cameras.map { CameraVision(it.location, it.directionDegrees, scale) }

        val replanned = withContext(Dispatchers.Default) {
            brouterRouter.route(RouteRequest(listOf(seam.from, seam.to), visions))
        }
        // **The plain fastest road through the window, and that is the whole
        // trick.**
        //
        // The obvious choice is the fewest-cameras option, and it is wrong here.
        // A replacement seam is new road that carries **no pins** — the pins that
        // were in this window belonged to the geometry being replaced, and
        // producing new ones would mean running the refiner over the seam, which
        // is a routing pass per candidate on a leg being planned in the
        // background. Without pins the car drives the window its own way, so a
        // seam that is only camera-free *because of avoidance routing* would be
        // exactly the road the car does not take.
        //
        // Splicing the fastest path removes that gap entirely: it is what the
        // car does unaided, so it needs no pins to be honoured. The safety comes
        // from the test below instead — it is accepted only if the road the car
        // would take anyway is no worse than what it drives today.
        val fastest = replanned.firstOrNull { it.choice == RouteChoice.FASTEST } ?: return null

        // What the old join passes, so "no worse" is measured rather than
        // assumed. Both windows come from routes already planned against this
        // same camera set, so this is like for like.
        val index = CameraIndex(visions)
        val before = index.seeing(earlier.subList(seam.fromIndex, earlier.size)).size +
            index.seeing(next.subList(0, seam.toIndex + 1)).size
        if (fastest.distinctCamerasPassed > before) return null

        LegJoin.spliceSeam(earlier, next, seam, fastest.polyline) ?: return null
    }.getOrNull()

    /**
     * Every known camera in a map viewport, for the DeFlock-style display.
     * Reuses the same cached DeFlock source the router draws on, so panning the
     * map is cheap once tiles are warm.
     */
    val viewportCameras: suspend (BoundingBox) -> List<MapCamera> = { bbox ->
        camerasFor(bbox).map { it.toMapCamera(cameraRangePercent / 100.0) }
    }

    init {
        // Evict routing tiles unused for over six months so cached maps don't
        // grow without bound; the areas you still drive get touched on each use.
        Thread {
            runCatching {
                val cutoff = System.currentTimeMillis() - TILE_TTL_DAYS * 24L * 60 * 60 * 1000
                tileSource.pruneUnusedSince(cutoff)
            }
            // Diagnostic exports used to be written into the cache and handed to
            // a share sheet. They are saved to a file the user picks now, and
            // any copy left behind is a snapshot of a log that has since
            // expired — quietly outliving the week the log itself promises.
            runCatching {
                File(appContext.cacheDir, "diagnostics").deleteRecursively()
            }
        }.start()
    }

    private fun planViewModel(): PlanViewModel = PlanViewModel(
        onLaterLegsNeeded = { points, destination, lead, leadPins, preference ->
            planRemainingLegs(points, destination, lead, leadPins, preference)
        },
        onLaterLegsAbandoned = { cancelRemainingLegs() },
        log = { message, points ->
            diagnostics.record(
                DiagnosticLog.Kind.PLAN,
                message,
                points.map { it.lat to it.lon },
            )
        },
        search = SuggestionSearch { query, at -> placeSearch.suggest(query, at) },
        planner = RoutePlanner { points, onProgress, heading ->
            // The whole plan, not just the routing engine. Camera counting and
            // waypoint extraction are heavy geometry over routes tens of
            // thousands of points long, and they were running on the caller's
            // thread — which is the main one — so a long trip froze the UI and
            // tripped Android's "isn't responding" dialog.
            withContext(Dispatchers.Default) {
                // Cut a long trip into legs and hand back only the first, so the
                // driver waits ten seconds rather than two minutes. The rest is
                // planned by [planRemainingLegs] once they set off. See
                // LegSplitter for why the boundary lands where it does.
                brouterPlanner.plan(
                    points = points,
                    onProgress = onProgress,
                    headingDegrees = heading,
                    maxLegMeters = LegSplitter.MAX_LEG_METERS,
                )
            }.also { outcome ->
                // Keep the tiles we actually route through fresh against eviction.
                if (outcome is app.shunt.solver.brouter.PlanOutcome.Routes) {
                    val bbox = BoundingBox.of(points)
                        .expand(BrouterPlanner.ROUTE_BBOX_MARGIN_METERS)
                    tileSource.markUsed(bbox)
                }
            }
        },
        tileDownloader = TileDownloader { origin, destination, onProgress ->
            downloadTripTiles(origin, destination, onProgress)
        },
        location = locationProvider,
        cameras = CameraGateway { around ->
            cameraSource.camerasIn(BoundingBox.around(around, CAMERA_WARM_RADIUS_METERS)).freshness
        },
        favoritesStore = favoritesStore,
        vehicle = vehicleNavClient,
        recentPlaces = recentPlacesStore,
        placeNamer = { point -> nominatimSearch.reverse(point)?.title },
        // A camera-avoiding detour can outrun a battery the direct route would
        // have been fine on, and the car's own planner never sees that route —
        // so the check has to happen here, before the driver sets off.
        rangeReader = { readVehicleRange() },
        chargeStopFinder = { route, reachable -> findChargeStop(route, reachable) },
        chargerListing = { route -> listChargers(route) },
    )

    /** The car's remaining range, or null when no car is connected / readable. */
    private suspend fun readVehicleRange(): app.shunt.app.plan.RangeReading? {
        val credentials = effectiveCredentials()
        if (!credentials.isConfigured) return null
        val state = tessieAccount.activeRoute(credentials.token, credentials.vin) ?: return null
        val miles = state.estimatedRangeMiles ?: return null
        return app.shunt.app.plan.RangeReading(miles, state.batteryLevel)
    }

    /**
     * A Tesla charging site on the way, far enough along the route to be worth
     * stopping at but still comfortably reachable. Searched within a corridor
     * around the route so the answer is somewhere you were driving anyway.
     */
    private suspend fun findChargeStop(
        route: List<app.shunt.core.GeoPoint>,
        reachableMeters: Double,
    ): List<app.shunt.app.plan.Destination> {
        if (route.size < 2) return emptyList()
        val candidates = superchargers.alongRoute(route, CHARGER_CORRIDOR_METERS)
        return rankChargeStops(route, candidates, reachableMeters, CHARGER_CORRIDOR_METERS)
            .map { app.shunt.app.plan.Destination(it.name, it.location) }
    }

    /**
     * A fresh camera-aware plan from [from] to [destination], for the drive
     * monitor when the vehicle has left its route. Returns null if no route can
     * be produced (including when camera data can't be vetted) — the caller
     * must then tell the driver there is no avoidance in force rather than
     * carry on as if there were.
     */
    /**
     * Re-plan from where the car actually is, keeping it off [blocked] — the
     * stretch of road the driver has just refused.
     */
    suspend fun replanFrom(
        from: app.shunt.core.GeoPoint,
        destination: app.shunt.app.plan.Destination,
        headingDegrees: Double? = null,
        blocked: List<app.shunt.core.GeoPoint> = emptyList(),
    ): DrivePlan? = planLeg(from, emptyList(), destination, headingDegrees, blocked)

    /**
     * A camera-aware plan from [from] through [via] to [destination]. Used both
     * to recover from leaving the route and to route the legs around a charging
     * stop the car inserted. Null means no route could be produced — including
     * when camera data couldn't be vetted, which must never be reported as a
     * clean route.
     */
    suspend fun planLeg(
        from: app.shunt.core.GeoPoint,
        via: List<app.shunt.core.GeoPoint>,
        destination: app.shunt.app.plan.Destination,
        /** Bearing of travel, so a mid-drive leg can't begin with a U-turn. */
        headingDegrees: Double? = null,
        /** Road the driver refused; kept out of every option on this plan only. */
        blocked: List<app.shunt.core.GeoPoint> = emptyList(),
    ): DrivePlan? {
        val points = listOf(from) + via + destination.location
        val outcome = runCatching {
            brouterPlanner.plan(
                points,
                headingDegrees = headingDegrees,
                // Every leg planned here is planned with the car moving, so both
                // budgets are the mid-drive ones: an answer that arrives after
                // the junction is no answer.
                refineBudgetMillis = BrouterPlanner.REPLAN_REFINE_BUDGET_MILLIS,
                blocked = blocked,
                routeBudgetMillis = BrouterRouter.REPLAN_PASS_BUDGET_MILLIS,
            )
        }.getOrNull()
        val chosen = (outcome as? app.shunt.solver.brouter.PlanOutcome.Routes)
            ?.options
            // Prefer the camera-free option when the detour exists; that is the
            // whole point of re-planning mid-drive.
            ?.minByOrNull { it.camerasPassed }
            ?: return null
        return DrivePlan(
            destination = destination,
            chain = chosen.waypoints + destination.location,
            cameras = chosen.passedCameras,
            polyline = chosen.polyline,
            stopPoints = via.toSet(),
            destinationOnly = true,
        )
    }

    /**
     * Every Tesla charging site near the route, reachable or not. The map shows
     * these so a driver can overrule the automatic pick — which rests on a
     * derate, a reserve and a guess at what a stop puts back, and will
     * sometimes be wrong in a way only the person driving can see.
     */
    private suspend fun listChargers(
        route: List<app.shunt.core.GeoPoint>,
    ): List<app.shunt.app.plan.Destination> {
        if (route.size < 2) return emptyList()
        return superchargers.alongRoute(route, CHARGER_CORRIDOR_METERS)
            .map { app.shunt.app.plan.Destination(it.name, it.location) }
    }

    /**
     * Watches for charging stops the car inserts by itself, or null when that
     * can't mean anything: no credentials (nothing to read), or a car that took
     * the full shaped chain, whose active route just echoes our own waypoints.
     *
     * A trip being steered pin by pin used to be excluded too, on the grounds
     * that the car is aimed a few miles up the road and so says nothing about
     * the trip. The first half of that is true and the conclusion was not: it
     * means the question costs a re-assert instead of being free, which
     * [ChargeStopCoordinator] already knows how to do and already rations. What
     * the exclusion actually bought was silence — on exactly the long trips
     * where the car inserts a Supercharger, Shunt watched for one only when it
     * happened not to be steering, which is to say almost never.
     *
     * The old reasoning leaned on steering being chosen only when the trip has
     * range to spare. That gate treats an unknown range as plenty, and "not
     * short" includes tight; neither is a promise the car won't stop to charge.
     */
    fun chargeStopCoordinator(plan: DrivePlan): app.shunt.app.drive.ChargeStopCoordinator? {
        if (!plan.destinationOnly) return null
        val credentials = effectiveCredentials()
        if (!credentials.isConfigured) return null
        return app.shunt.app.drive.ChargeStopCoordinator(
            vehicle = vehicleNavClient,
            readActiveRoute = { tessieAccount.activeRoute(credentials.token, credentials.vin) },
            planLeg = { from, via, to, heading -> planLeg(from, via, to, heading) },
            steering = plan.steerByWaypoints,
        )
    }

    /**
     * How many searches over the road graph this device may run at once.
     *
     * `blocked` and `balanced` are independent and are most of a long plan, so
     * overlapping them is the largest remaining speed-up — measured on a 615 km
     * trip, the routing stage fell from 38.5 s to 24.2 s and the three options
     * came back identical to the metre.
     *
     * The cost is memory: each search builds its own BRouter tile cache, and the
     * same measurement put peak heap up from 230 MB to 302 MB. That is fine on a
     * device with room and fatal on one without, and an OOM part-way through
     * planning is a far worse failure than a slow plan — so this asks the
     * platform what it has rather than assuming. [ActivityManager.getMemoryClass]
     * is the heap ceiling in MB that this app is actually held to.
     *
     * Two lanes only. Three was never measured and each one costs another tile
     * cache; anyone raising it should re-run the benchmark in CLAUDE.md §8 on a
     * real device first.
     */
    private fun concurrentRoutingPasses(): Int {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return 1
        if (am.isLowRamDevice) return 1
        return if (am.memoryClass >= CONCURRENT_ROUTING_HEAP_MB) 2 else 1
    }

    /** Download every tile this trip needs, reporting overall 0f..1f progress. */
    private suspend fun downloadTripTiles(
        origin: app.shunt.core.GeoPoint,
        destination: app.shunt.core.GeoPoint,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val bbox = BoundingBox.of(listOf(origin, destination))
            .expand(BrouterPlanner.ROUTE_BBOX_MARGIN_METERS)
        val missing = tileSource.missingTiles(bbox)
        if (missing.isEmpty()) return true
        missing.forEachIndexed { index, tile ->
            val ok = tileSource.download(tile) { soFar, total ->
                val fraction = if (total > 0) (soFar.toFloat() / total).coerceIn(0f, 1f) else 0f
                onProgress((index + fraction) / missing.size)
            }
            if (!ok) return false
            onProgress((index + 1f) / missing.size)
        }
        return true
    }

    fun planViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = planViewModel() as T
    }

    private companion object {
        /** How far out the navigation probe aims its two test points. */
        const val PROBE_NEAR_METERS = 3_000.0
        const val PROBE_FAR_METERS = 6_000.0

        /** Warm camera cache within this radius of the origin on app open. */
        const val CAMERA_WARM_RADIUS_METERS = 5_000.0

        /** Routing tiles unused for this long are pruned (~6 months). */
        const val TILE_TTL_DAYS = 183L

        /**
         * Heap ceiling (MB) a device needs before two routes are searched at once.
         *
         * **This was 384 and never fired.** `getMemoryClass` is not the device's
         * RAM — it is the per-app Java heap ceiling, and 256 MB is a common value
         * on phones with 8 GB or more. Sized from a peak-usage reading taken with
         * a lazy garbage collector and no ceiling to push against, 384 ruled out
         * hardware that runs this comfortably, and the concurrency shipped
         * switched off on a real phone without anything looking wrong.
         *
         * Re-measured properly instead — the 615 km plan run *under* a 256 MB
         * cap, which is the question that actually matters: it completed, peaked
         * at 235 MB, returned the same three routes, and still took the routing
         * stage from 44.5 s to 24.1 s. The earlier 302 MB was uncollected
         * garbage, not demand.
         *
         * There is a real safety net under this, which is what makes 256 a
         * reasonable line rather than a brave one: a search that runs out of
         * memory is caught like any other failure, and costs the *option* rather
         * than the app — the breakdown then names the pass that dropped out.
         */
        const val CONCURRENT_ROUTING_HEAP_MB = 256
    }
}

/** A probe result flattened for the settings dialog. */
private fun app.shunt.tesla.NavCapabilityProbe.Step.toLine(): app.shunt.app.ui.NavProbeLine =
    app.shunt.app.ui.NavProbeLine(
        channel = channel,
        sent = sent,
        verdict = verdict + (carDestinationName?.let { " (car says: $it)" } ?: ""),
        landed = landed,
        detail = response,
    )

/** A DeFlock/OSM camera reduced to what the map needs, with a friendly label. */
private fun Camera.toMapCamera(rangeScale: Double = 1.0): MapCamera {
    val manufacturer = tags["manufacturer"] ?: tags["brand"]
    val operator = tags["operator"]
    // An invented camera says so in the one place a user reads about a
    // specific camera. Without this, practice mode shows something that looks
    // exactly like a Flock unit and the driver has been told a falsehood about
    // where they are watched.
    val title = if (PracticeCameras.isPractice(this)) {
        "Practice camera (not real)"
    } else {
        manufacturer ?: operator ?: "ALPR camera"
    }
    val subtitle = buildList {
        if (PracticeCameras.isPractice(this@toMapCamera)) add("Invented by Shunt for testing")
        else if (manufacturer != null && operator != null) add("Operated by $operator")
        (tags["surveillance:type"] ?: tags["camera:type"])?.let { add(it) }
    }.joinToString(" · ").ifBlank { null }
    return MapCamera(
        rangeScale = rangeScale,
        id = id,
        lat = location.lat,
        lon = location.lon,
        directionDegrees = directionDegrees,
        title = title,
        subtitle = subtitle,
    )
}
