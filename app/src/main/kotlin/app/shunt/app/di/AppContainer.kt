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
    )
    private val brouterPlanner = BrouterPlanner(
        route = { points, cams ->
            withContext(Dispatchers.Default) { brouterRouter.route(points, cams) }
        },
        missingTiles = { bbox -> tileSource.missingTiles(bbox) },
        camerasIn = { bbox -> cameraSource.camerasIn(bbox).cameras },
        diagnostics = { routingDiagnostic() },
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
        planner = RoutePlanner { points, onProgress ->
            // The whole plan, not just the routing engine. Camera counting and
            // waypoint extraction are heavy geometry over routes tens of
            // thousands of points long, and they were running on the caller's
            // thread — which is the main one — so a long trip froze the UI and
            // tripped Android's "isn't responding" dialog.
            withContext(Dispatchers.Default) { brouterPlanner.plan(points, onProgress) }.also { outcome ->
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
    suspend fun replanFrom(
        from: app.shunt.core.GeoPoint,
        destination: app.shunt.app.plan.Destination,
    ): DrivePlan? = planLeg(from, emptyList(), destination)

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
    ): DrivePlan? {
        val points = listOf(from) + via + destination.location
        val outcome = runCatching { brouterPlanner.plan(points) }.getOrNull()
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
     * the full shaped chain (its active route just echoes our own waypoints).
     */
    fun chargeStopCoordinator(plan: DrivePlan): app.shunt.app.drive.ChargeStopCoordinator? {
        if (!plan.destinationOnly) return null
        val credentials = effectiveCredentials()
        if (!credentials.isConfigured) return null
        return app.shunt.app.drive.ChargeStopCoordinator(
            vehicle = vehicleNavClient,
            readActiveRoute = { tessieAccount.activeRoute(credentials.token, credentials.vin) },
            planLeg = { from, via, to -> planLeg(from, via, to) },
        )
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
        /** Warm camera cache within this radius of the origin on app open. */
        const val CAMERA_WARM_RADIUS_METERS = 5_000.0

        /** Routing tiles unused for this long are pruned (~6 months). */
        const val TILE_TTL_DAYS = 183L

    }
}

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
