package app.shunt.app.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.shunt.BuildConfig
import app.shunt.app.drive.DriveActivity
import app.shunt.app.drive.DriveStatus
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
import app.shunt.solver.charging.CHARGER_CORRIDOR_METERS
import app.shunt.solver.charging.SuperchargerSource
import app.shunt.solver.charging.rankChargeStops
import app.shunt.solver.geo.BoundingBox
import app.shunt.app.diag.DiagnosticLog
import app.shunt.core.GeoPoint
import app.shunt.app.plan.Destination
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
        val real = cameraSource.camerasIn(bbox).cameras
        if (!practiceCameras) return real
        // Snapped onto real roads using the tiles already on disk for routing,
        // which also thins them out to where the roads are — so a practice field
        // is dense in town and sparse in the country, like the real thing.
        return real + PracticeCameras.inBox(bbox) { points, meters ->
            brouterRouter.snapToRoads(points, meters)
        }
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
     * Legs planned after the driver set off, on their way to the drive monitor.
     *
     * Conflated because only the newest matters: each extension carries the
     * whole of what is left, not a delta, so a monitor that misses one and takes
     * the next has lost nothing.
     */
    val legExtensions = Channel<DrivePlan>(Channel.CONFLATED)

    private var legJob: Job? = null

    /**
     * Later legs of the trip, as they are planned, for the map to draw.
     *
     * Held here rather than in the plan screen's state because planning outlives
     * that screen: the legs keep arriving after the driving sheet takes over.
     */
    val laterLegs = MutableStateFlow<List<PlannedRoute>>(emptyList())

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
        legJob?.cancel()
        legJob = null
        laterLegs.value = emptyList()
        // Conflated, so at most one can be waiting — but draining is what makes
        // this true regardless of the channel's capacity.
        while (legExtensions.tryReceive().isSuccess) Unit
        liveDrivePlan.value = null
    }

    fun planRemainingLegs(points: List<GeoPoint>, destination: Destination) {
        legJob?.cancel()
        laterLegs.value = emptyList()
        if (points.size < 2) return
        legJob = appScope.launch(Dispatchers.Default) {
            var points = points
            while (points.size >= 2 && isActive) {
                diagnostics.record(DiagnosticLog.Kind.PLAN, "planning the next leg (${points.size} points left)")
                val outcome = runCatching {
                    brouterPlanner.plan(
                        points = points,
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
                    diagnostics.record(DiagnosticLog.Kind.PLAN, "next leg failed to plan")
                    return@launch
                }
                // The same preference the driver expressed on the chooser, which
                // for every leg after the first is "as few cameras as possible" —
                // they already accepted the trade when they picked it.
                val chosen = outcome.options.minByOrNull { it.camerasPassed } ?: return@launch
                // A leg that comes back holding nothing but the fastest road is
                // not a considered answer — it is every avoidance pass having run
                // out — and taking it silently is indistinguishable from Shunt
                // deciding there was no camera-free route. It is still handed
                // over, because a route is better than a boundary with nothing
                // past it, but it is said out loud. See CLAUDE.md §7.10.
                if (outcome.options.none { it.choice != RouteChoice.FASTEST }) {
                    diagnostics.record(
                        DiagnosticLog.Kind.PLAN,
                        "next leg has NO avoidance option — the fastest road is all that came back " +
                            "(${chosen.camerasPassed} cameras)",
                    )
                }
                val legPlan = DrivePlan(
                    destination = destination,
                    // This leg's own end, not the trip's. `points.last()` is the
                    // final destination, so on a trip that takes three legs it
                    // put a waypoint hundreds of kilometres past the end of the
                    // leg being appended — the car would have been aimed at the
                    // destination the moment the extension landed.
                    chain = chosen.waypoints + (outcome.remaining.firstOrNull() ?: points.last()),
                    cameras = chosen.passedCameras,
                    polyline = chosen.polyline,
                    remaining = outcome.remaining,
                )
                legExtensions.trySend(legPlan)
                // Onto the map as well as into the drive. The line growing
                // toward the destination is the visible difference between
                // "still working" and "gave up", and it has to happen from a
                // standstill as readily as while moving.
                laterLegs.value = laterLegs.value + chosen
                diagnostics.record(
                    DiagnosticLog.Kind.PLAN,
                    "next leg ready: ${chosen.distanceMeters / 1000} km, ${chosen.camerasPassed} cameras",
                )
                if (outcome.remaining.size < 2) return@launch
                points = outcome.remaining
            }
        }
    }

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
        }.start()
    }

    private fun planViewModel(): PlanViewModel = PlanViewModel(
        onLaterLegsNeeded = { points, destination -> planRemainingLegs(points, destination) },
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
