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
import app.shunt.solver.geo.BoundingBox
import app.shunt.solver.here.HereAutosuggest
import app.shunt.tesla.FakeVehicleNavClient
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

    /** On-device settings (the user-entered HERE key). */
    val settings = SettingsStore(appContext)

    /** Runtime HERE key: the in-app value if set, otherwise the build-time one. */
    fun effectiveHereKey(): String = settings.hereApiKey.value.ifBlank { BuildConfig.HERE_API_KEY }

    private val cameraSource = DeFlockCameraSource(
        http = http,
        cacheDir = File(appContext.cacheDir, "deflock"),
    )

    // HERE now powers search only; routing is native/offline via BRouter.
    private val autosuggest = HereAutosuggest(http, { effectiveHereKey() })

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
        route = { origin, destination, cams ->
            withContext(Dispatchers.Default) { brouterRouter.route(origin, destination, cams) }
        },
        missingTiles = { bbox -> tileSource.missingTiles(bbox) },
        camerasIn = { bbox -> cameraSource.camerasIn(bbox).cameras },
    )

    /**
     * The single vehicle-client seam. The production [TessieVehicleNavClient]
     * is used when a Tessie token + VIN are configured; otherwise the app
     * runs against the fake, so keyless builds and CI still work. Flipping to
     * the real client is the one construction below — everything downstream
     * depends only on [VehicleNavClient].
     */
    val vehicleNavClient: VehicleNavClient by lazy {
        val token = BuildConfig.TESSIE_TOKEN
        val vin = BuildConfig.TESSIE_VIN
        if (token.isNotBlank() && vin.isNotBlank()) {
            TessieVehicleNavClient(http = http, bearerToken = token, vin = vin)
        } else {
            FakeVehicleNavClient()
        }
    }

    val favoritesStore = SharedPrefsFavoritesStore(appContext)
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

    /** True when no HERE key is configured — the UI warns and offers to add one. */
    fun hereKeyMissing(): Boolean = effectiveHereKey().isBlank()

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
        search = SuggestionSearch { query, at -> autosuggest.suggest(query, at) },
        planner = RoutePlanner { origin, destination ->
            brouterPlanner.plan(origin, destination).also { outcome ->
                // Keep the tiles we actually route through fresh against eviction.
                if (outcome is app.shunt.solver.brouter.PlanOutcome.Routes) {
                    val bbox = BoundingBox.of(listOf(origin, destination))
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
    )

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
