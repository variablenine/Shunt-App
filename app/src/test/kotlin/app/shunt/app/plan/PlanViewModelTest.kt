package app.shunt.app.plan

import app.shunt.core.GeoPoint
import app.shunt.solver.camera.Camera
import app.shunt.solver.camera.Freshness
import app.shunt.solver.here.Suggestion
import app.shunt.solver.routing.Route
import app.shunt.solver.routing.SolveResult
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

    private fun route(seconds: Int) =
        Route(listOf(origin, dest), durationSeconds = seconds, lengthMeters = 30_000)

    private val clean = SolveResult.Clean(route(600), addedSecondsVsFastest = 120, waypoints = listOf(GeoPoint(44.8, -87.9)))
    private val minExposure = SolveResult.MinimumExposure(
        route(900),
        passedCameras = listOf(Camera(1, GeoPoint(44.7, -88.0)), Camera(2, GeoPoint(44.9, -87.8))),
        addedSecondsVsFastest = 300,
        waypoints = emptyList(),
    )

    private class InMemoryFavorites(var favorites: Favorites = Favorites()) : FavoritesStore {
        override fun load() = favorites
        override fun save(favorites: Favorites) { this.favorites = favorites }
    }

    private fun vm(
        scope: kotlinx.coroutines.CoroutineScope,
        suggestions: List<Suggestion> = emptyList(),
        result: SolveResult = clean,
        originValue: GeoPoint? = origin,
        freshness: Freshness = Freshness.NETWORK,
        vehicle: app.shunt.tesla.VehicleNavClient = FakeVehicleNavClient(),
        favoritesStore: FavoritesStore = InMemoryFavorites(),
    ) = PlanViewModel(
        search = { _, _ -> suggestions },
        planner = { _, _ -> result },
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
            planner = { _, _ -> clean },
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
        // Clearing the query resets the error.
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
    fun `selecting a suggestion solves to a clean result`() = runTest {
        val model = vm(this, suggestions = listOf(Suggestion("Lambeau Field", dest, "place")), result = clean)
        model.onQueryChange("Lambeau"); advanceUntilIdle()
        model.onSuggestionSelected(0)
        advanceUntilIdle()
        val solved = assertIs<Phase.Solved>(model.state.value.phase)
        assertEquals("Lambeau Field", solved.destination.title)
        assertIs<SolveResult.Clean>(solved.result)
        assertTrue(model.state.value.suggestions.isEmpty(), "suggestions clear once solving")
    }

    @Test
    fun `favorite one-tap solves to minimum exposure`() = runTest {
        val store = InMemoryFavorites(Favorites(home = Destination("Home", dest)))
        val model = vm(this, result = minExposure, favoritesStore = store)
        model.onFavoriteSelected(FavoriteSlot.HOME)
        advanceUntilIdle()
        val solved = assertIs<Phase.Solved>(model.state.value.phase)
        val result = assertIs<SolveResult.MinimumExposure>(solved.result)
        assertEquals(2, result.passedCameras.size)
    }

    @Test
    fun `unset favorite is a no-op`() = runTest {
        val model = vm(this)
        model.onFavoriteSelected(FavoriteSlot.WORK)
        advanceUntilIdle()
        assertIs<Phase.Browsing>(model.state.value.phase)
    }

    @Test
    fun `missing origin surfaces an error instead of solving`() = runTest {
        val model = vm(this, originValue = null)
        model.onFavoriteSelected(FavoriteSlot.HOME) // no favorite, no-op; use suggestion path
        val model2 = vm(this, suggestions = listOf(Suggestion("X", dest, "place")), originValue = null)
        model2.onQueryChange("X"); advanceUntilIdle()
        model2.onSuggestionSelected(0); advanceUntilIdle()
        assertIs<Phase.Error>(model2.state.value.phase)
    }

    @Test
    fun `Go pushes the waypoint chain plus destination and enters Driving`() = runTest {
        val fake = FakeVehicleNavClient()
        val model = vm(this, suggestions = listOf(Suggestion("Dest", dest, "place")), result = clean, vehicle = fake)
        model.onQueryChange("Dest"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        model.onGo(); advanceUntilIdle()
        val driving = assertIs<Phase.Driving>(model.state.value.phase)
        val call = fake.calls().single()
        assertIs<FakeVehicleNavClient.Call.PushRoute>(call)
        // clean.waypoints (1) + destination = 2 points, destination last.
        assertEquals(2, call.waypoints.size)
        assertEquals(dest, call.waypoints.last())
        // The plan the monitor will run matches what was pushed.
        assertEquals(call.waypoints, driving.plan.chain)
    }

    @Test
    fun `cancelling a drive returns to browsing`() = runTest {
        val model = vm(this, suggestions = listOf(Suggestion("Dest", dest, "place")), result = clean)
        model.onQueryChange("Dest"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        model.onGo(); advanceUntilIdle()
        assertIs<Phase.Driving>(model.state.value.phase)
        model.onStopDrive()
        assertIs<Phase.Browsing>(model.state.value.phase)
    }

    @Test
    fun `minimum-exposure plan carries the passed cameras to the monitor`() = runTest {
        val store = InMemoryFavorites(Favorites(home = Destination("Home", dest)))
        val model = vm(this, result = minExposure, favoritesStore = store)
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
        // Second attempt (call #2) succeeds.
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
