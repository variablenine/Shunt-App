package app.shunt.app.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.shunt.BuildConfig
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
    )

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
        camerasIn = { bbox -> cameraSource.camerasIn(bbox).cameras },
        diagnostics = { routingDiagnostic() },
        lastPassTimings = { brouterRouter.lastPassTimings },
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
     * The route actually in force, republished whenever the monitor replaces it
     * — leaving the planned road, or a charging leg. The screen follows this
     * rather than the plan handed over at Go, which stops going stale the
     * moment anything re-plans.
     */
    val liveDrivePlan = MutableStateFlow<DrivePlan?>(null)

    /**
     * Every known camera in a map viewport, for the DeFlock-style display.
     * Reuses the same cached DeFlock source the router draws on, so panning the
     * map is cheap once tiles are warm.
     */
    val viewportCameras: suspend (BoundingBox) -> List<MapCamera> = { bbox ->
        cameraSource.camerasIn(bbox).cameras.map { it.toMapCamera() }
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
        search = SuggestionSearch { query, at -> placeSearch.suggest(query, at) },
        planner = RoutePlanner { points, onProgress, heading ->
            // The whole plan, not just the routing engine. Camera counting and
            // waypoint extraction are heavy geometry over routes tens of
            // thousands of points long, and they were running on the caller's
            // thread — which is the main one — so a long trip froze the UI and
            // tripped Android's "isn't responding" dialog.
            withContext(Dispatchers.Default) {
                brouterPlanner.plan(points, onProgress, heading)
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
private fun Camera.toMapCamera(): MapCamera {
    val manufacturer = tags["manufacturer"] ?: tags["brand"]
    val operator = tags["operator"]
    val title = manufacturer ?: operator ?: "ALPR camera"
    val subtitle = buildList {
        if (manufacturer != null && operator != null) add("Operated by $operator")
        (tags["surveillance:type"] ?: tags["camera:type"])?.let { add(it) }
    }.joinToString(" · ").ifBlank { null }
    return MapCamera(
        id = id,
        lat = location.lat,
        lon = location.lon,
        directionDegrees = directionDegrees,
        title = title,
        subtitle = subtitle,
    )
}
