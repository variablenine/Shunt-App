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
import app.shunt.solver.camera.DeFlockCameraSource
import app.shunt.solver.geo.BoundingBox
import app.shunt.solver.here.HereAutosuggest
import app.shunt.solver.here.HereRoutingClient
import app.shunt.solver.routing.RouteSolver
import app.shunt.tesla.FakeVehicleNavClient
import app.shunt.tesla.TessieVehicleNavClient
import app.shunt.tesla.VehicleNavClient
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val routingApi = HereRoutingClient(http, { effectiveHereKey() })
    private val autosuggest = HereAutosuggest(http, { effectiveHereKey() })
    private val routeSolver = RouteSolver(
        api = routingApi,
        cameras = { bbox -> cameraSource.camerasIn(bbox).cameras },
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

    private fun planViewModel(): PlanViewModel = PlanViewModel(
        search = SuggestionSearch { query, at -> autosuggest.suggest(query, at) },
        planner = RoutePlanner { origin, destination -> routeSolver.solve(origin, destination) },
        location = locationProvider,
        cameras = CameraGateway { around ->
            cameraSource.camerasIn(BoundingBox.around(around, CAMERA_WARM_RADIUS_METERS)).freshness
        },
        favoritesStore = favoritesStore,
        vehicle = vehicleNavClient,
    )

    fun planViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = planViewModel() as T
    }

    private companion object {
        /** Warm camera cache within this radius of the origin on app open. */
        const val CAMERA_WARM_RADIUS_METERS = 5_000.0
    }
}
