package app.shunt.app.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.shunt.BuildConfig
import app.shunt.app.plan.CameraGateway
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
import app.shunt.tesla.VehicleNavClient
import java.io.File
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
    private val hereApiKey = BuildConfig.HERE_API_KEY

    private val cameraSource = DeFlockCameraSource(
        http = http,
        cacheDir = File(appContext.cacheDir, "deflock"),
    )
    private val routingApi = HereRoutingClient(http, hereApiKey)
    private val autosuggest = HereAutosuggest(http, hereApiKey)
    private val routeSolver = RouteSolver(
        api = routingApi,
        cameras = { bbox -> cameraSource.camerasIn(bbox).cameras },
    )

    val vehicleNavClient: VehicleNavClient by lazy {
        // --- one-line vehicle-client swap ---
        FakeVehicleNavClient()
        // e.g. TessieVehicleNavClient(http, tokenProvider, vin)  // Part B
        // -------------------------------------
    }

    val favoritesStore = SharedPrefsFavoritesStore(appContext)
    private val locationProvider: LocationProvider =
        AndroidLocationProvider(appContext, favoritesStore)

    /** True when no HERE key is configured — the UI warns instead of failing silently. */
    val hereKeyMissing: Boolean get() = hereApiKey.isBlank()

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
