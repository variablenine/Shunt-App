package app.shunt.app.plan

import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.PlanOutcome
import app.shunt.solver.brouter.PlannedRoute
import app.shunt.solver.brouter.RouteChoice
import app.shunt.solver.brouter.TileId
import app.shunt.solver.camera.Camera
import app.shunt.solver.camera.Freshness
import app.shunt.solver.here.Suggestion
import app.shunt.tesla.FakeVehicleNavClient
import app.shunt.tesla.PushResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModelTest {

    private val origin = GeoPoint(44.5133, -88.0133)
    private val dest = GeoPoint(45.0906, -87.6431)

    private fun plannedRoute(
        choice: RouteChoice,
        seconds: Int,
        cameras: List<Camera> = emptyList(),
        added: Int = 0,
        waypoints: List<GeoPoint> = listOf(GeoPoint(44.8, -87.9)),
    ) = PlannedRoute(
        choice = choice,
        polyline = listOf(origin, dest),
        waypoints = waypoints,
        passedCameras = cameras,
        distanceMeters = 30_000,
        estimatedSeconds = seconds,
        exposureMeters = 0,
        addedSecondsVsFastest = added,
    )

    private val fastest = plannedRoute(RouteChoice.FASTEST, 600)
    private val withCameras = plannedRoute(
        RouteChoice.FASTEST, 900,
        cameras = listOf(Camera(1, GeoPoint(44.7, -88.0)), Camera(2, GeoPoint(44.9, -87.8))),
    )

    private fun routes(vararg options: PlannedRoute): PlanOutcome = PlanOutcome.Routes(options.toList())

    private class InMemoryFavorites(var favorites: Favorites = Favorites()) : FavoritesStore {
        override fun load() = favorites
        override fun save(favorites: Favorites) { this.favorites = favorites }
    }

    private fun vm(
        scope: kotlinx.coroutines.CoroutineScope,
        suggestions: List<Suggestion> = emptyList(),
        outcome: PlanOutcome = routes(fastest),
        planner: RoutePlanner = RoutePlanner { _, _ -> outcome },
        tileDownloader: TileDownloader = TileDownloader { _, _, _ -> true },
        originValue: GeoPoint? = origin,
        freshness: Freshness = Freshness.NETWORK,
        vehicle: app.shunt.tesla.VehicleNavClient = FakeVehicleNavClient(),
        favoritesStore: FavoritesStore = InMemoryFavorites(),
    ) = PlanViewModel(
        search = { _, _ -> suggestions },
        planner = planner,
        tileDownloader = tileDownloader,
        location = { originValue },
        cameras = { freshness },
        favoritesStore = favoritesStore,
        vehicle = vehicle,
        scope = scope,
    )

    @Test
    fun `onOpen warms camera data and records freshness`() = runTest {
        val model = vm(this, freshness = Freshness.BUNDLED)
        model.onOpen()
        advanceUntilIdle()
        assertEquals(Freshness.BUNDLED, model.state.value.cameraDataFreshness)
        assertTrue(model.state.value.usingOfflineCameraData)
    }

    @Test
    fun `typing debounces then shows suggestions`() = runTest {
        val suggestions = listOf(Suggestion("Lambeau Field", dest, "place"))
        val model = vm(this, suggestions = suggestions)
        model.onQueryChange("Lam")
        model.onQueryChange("Lambeau") // supersedes the first before debounce fires
        advanceUntilIdle()
        assertEquals(suggestions, model.state.value.suggestions)
        assertEquals("Lambeau", model.state.value.query)
    }

    @Test
    fun `search failure surfaces instead of blanking silently`() = runTest {
        val model = PlanViewModel(
            search = { _, _ -> throw java.io.IOException("offline") },
            planner = { _, _ -> routes(fastest) },
            tileDownloader = { _, _, _ -> true },
            location = { origin },
            cameras = { Freshness.NETWORK },
            favoritesStore = InMemoryFavorites(),
            vehicle = FakeVehicleNavClient(),
            scope = this,
        )
        model.onQueryChange("Lambeau")
        advanceUntilIdle()
        assertTrue(model.state.value.searchFailed)
        assertTrue(model.state.value.suggestions.isEmpty())
        model.onQueryChange("")
        assertTrue(!model.state.value.searchFailed)
    }

    @Test
    fun `blank query clears suggestions immediately`() = runTest {
        val model = vm(this, suggestions = listOf(Suggestion("X", dest, "place")))
        model.onQueryChange("X"); advanceUntilIdle()
        assertTrue(model.state.value.suggestions.isNotEmpty())
        model.onQueryChange("")
        assertTrue(model.state.value.suggestions.isEmpty())
    }

    @Test
    fun `selecting a suggestion routes to options`() = runTest {
        val model = vm(this, suggestions = listOf(Suggestion("Lambeau Field", dest, "place")))
        model.onQueryChange("Lambeau"); advanceUntilIdle()
        model.onSuggestionSelected(0)
        advanceUntilIdle()
        val solved = assertIs<Phase.Solved>(model.state.value.phase)
        assertEquals("Lambeau Field", solved.destination.title)
        assertEquals(0, solved.chosen.camerasPassed)
        assertTrue(model.state.value.suggestions.isEmpty(), "suggestions clear once routing")
    }

    @Test
    fun `a route passing cameras is carried on the chosen option`() = runTest {
        val store = InMemoryFavorites(Favorites(home = Destination("Home", dest)))
        val model = vm(this, outcome = routes(withCameras), favoritesStore = store)
        model.onFavoriteSelected(FavoriteSlot.HOME)
        advanceUntilIdle()
        val solved = assertIs<Phase.Solved>(model.state.value.phase)
        assertEquals(2, solved.chosen.camerasPassed)
    }

    @Test
    fun `onSelectRoute switches the chosen option`() = runTest {
        val fewest = plannedRoute(RouteChoice.FEWEST_CAMERAS, 1200, added = 600)
        val model = vm(this, suggestions = listOf(Suggestion("X", dest, "place")), outcome = routes(fastest, fewest))
        model.onQueryChange("X"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        var solved = assertIs<Phase.Solved>(model.state.value.phase)
        assertEquals(RouteChoice.FASTEST, solved.chosen.choice)
        model.onSelectRoute(1)
        solved = assertIs<Phase.Solved>(model.state.value.phase)
        assertEquals(RouteChoice.FEWEST_CAMERAS, solved.chosen.choice)
    }

    @Test
    fun `a missing tile prompts a download, then routes after it succeeds`() = runTest {
        var calls = 0
        val planner = RoutePlanner { _, _ ->
            calls++
            if (calls == 1) PlanOutcome.NeedsDownload(listOf(TileId(-90, 45))) else routes(fastest)
        }
        var downloaded = false
        val model = vm(
            this,
            suggestions = listOf(Suggestion("X", dest, "place")),
            planner = planner,
            tileDownloader = { _, _, onProgress -> onProgress(1f); downloaded = true; true },
        )
        model.onQueryChange("X"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        assertIs<Phase.NeedTile>(model.state.value.phase)
        model.onDownloadTile(); advanceUntilIdle()
        assertTrue(downloaded)
        assertIs<Phase.Solved>(model.state.value.phase)
    }

    @Test
    fun `a failed tile download surfaces on the prompt for retry`() = runTest {
        val model = vm(
            this,
            suggestions = listOf(Suggestion("X", dest, "place")),
            planner = { _, _ -> PlanOutcome.NeedsDownload(listOf(TileId(-90, 45))) },
            tileDownloader = { _, _, _ -> false },
        )
        model.onQueryChange("X"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        model.onDownloadTile(); advanceUntilIdle()
        val need = assertIs<Phase.NeedTile>(model.state.value.phase)
        assertTrue(need.failed)
        assertTrue(!need.downloading)
    }

    @Test
    fun `unset favorite is a no-op`() = runTest {
        val model = vm(this)
        model.onFavoriteSelected(FavoriteSlot.WORK)
        advanceUntilIdle()
        assertIs<Phase.Browsing>(model.state.value.phase)
    }

    @Test
    fun `missing origin surfaces an error instead of routing`() = runTest {
        val model = vm(this, suggestions = listOf(Suggestion("X", dest, "place")), originValue = null)
        model.onQueryChange("X"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        assertIs<Phase.Error>(model.state.value.phase)
    }

    @Test
    fun `Go pushes the waypoint chain plus destination and enters Driving`() = runTest {
        val fake = FakeVehicleNavClient()
        val model = vm(this, suggestions = listOf(Suggestion("Dest", dest, "place")), vehicle = fake)
        model.onQueryChange("Dest"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        model.onGo(); advanceUntilIdle()
        val driving = assertIs<Phase.Driving>(model.state.value.phase)
        val call = fake.calls().single()
        assertIs<FakeVehicleNavClient.Call.PushRoute>(call)
        // fastest.waypoints (1) + destination = 2 points, destination last.
        assertEquals(2, call.waypoints.size)
        assertEquals(dest, call.waypoints.last())
        assertEquals(call.waypoints, driving.plan.chain)
    }

    @Test
    fun `cancelling a drive returns to browsing`() = runTest {
        val model = vm(this, suggestions = listOf(Suggestion("Dest", dest, "place")))
        model.onQueryChange("Dest"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        model.onGo(); advanceUntilIdle()
        assertIs<Phase.Driving>(model.state.value.phase)
        model.onStopDrive()
        assertIs<Phase.Browsing>(model.state.value.phase)
    }

    @Test
    fun `the chosen route's cameras are carried to the monitor`() = runTest {
        val store = InMemoryFavorites(Favorites(home = Destination("Home", dest)))
        val model = vm(this, outcome = routes(withCameras), favoritesStore = store)
        model.onFavoriteSelected(FavoriteSlot.HOME); advanceUntilIdle()
        model.onGo(); advanceUntilIdle()
        val driving = assertIs<Phase.Driving>(model.state.value.phase)
        assertEquals(2, driving.plan.cameras.size)
    }

    @Test
    fun `push failure is surfaced with retryable flag and can be retried`() = runTest {
        val flaky = FakeVehicleNavClient(
            FakeVehicleNavClient.Behavior(
                failOnCalls = setOf(1),
                failure = PushResult.Failed("vehicle asleep", retryable = true),
            ),
        )
        val model = vm(this, suggestions = listOf(Suggestion("Dest", dest, "place")), vehicle = flaky)
        model.onQueryChange("Dest"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        model.onGo(); advanceUntilIdle()
        val failed = assertIs<Phase.PushFailed>(model.state.value.phase)
        assertTrue(failed.retryable)
        assertEquals("vehicle asleep", failed.reason)
        model.onRetryPush(); advanceUntilIdle()
        assertIs<Phase.Driving>(model.state.value.phase)
    }

    @Test
    fun `saving a favorite persists and updates state`() = runTest {
        val store = InMemoryFavorites()
        val model = vm(this, favoritesStore = store)
        val home = Destination("Home", dest)
        model.onSaveFavorite(FavoriteSlot.HOME, home)
        assertEquals(home, model.state.value.favorites.home)
        assertEquals(home, store.load().home)
    }

    @Test
    fun `dismiss returns to browsing and clears the query`() = runTest {
        val model = vm(this, suggestions = listOf(Suggestion("Dest", dest, "place")))
        model.onQueryChange("Dest"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        model.onDismissResult()
        assertIs<Phase.Browsing>(model.state.value.phase)
        assertEquals("", model.state.value.query)
    }
}
