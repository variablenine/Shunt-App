package app.shunt.app.plan

import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.PlanOutcome
import app.shunt.solver.brouter.PlannedRoute
import app.shunt.solver.brouter.RouteChoice
import app.shunt.solver.brouter.TileId
import app.shunt.solver.camera.Camera
import app.shunt.solver.camera.Freshness
import app.shunt.solver.charging.RangeCheck
import app.shunt.solver.search.Suggestion
import app.shunt.tesla.FakeVehicleNavClient
import app.shunt.tesla.PushResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModelTest {

    private val origin = GeoPoint(39.5133, -98.0133)
    private val dest = GeoPoint(40.0906, -97.6431)

    private fun plannedRoute(
        choice: RouteChoice,
        seconds: Int,
        cameras: List<Camera> = emptyList(),
        added: Int = 0,
        waypoints: List<GeoPoint> = listOf(GeoPoint(39.8, -97.9)),
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
        cameras = listOf(Camera(1, GeoPoint(39.7, -98.0)), Camera(2, GeoPoint(39.9, -97.8))),
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
        planner: RoutePlanner = RoutePlanner { _, _, _ -> outcome },
        tileDownloader: TileDownloader = TileDownloader { _, _, _ -> true },
        originValue: GeoPoint? = origin,
        freshness: Freshness = Freshness.NETWORK,
        vehicle: app.shunt.tesla.VehicleNavClient = FakeVehicleNavClient(),
        favoritesStore: FavoritesStore = InMemoryFavorites(),
        placeNamer: PlaceNamer? = null,
        rangeReader: VehicleRangeReader? = null,
        chargeStopFinder: ChargeStopFinder? = null,
        onLaterLegsNeeded: (
            (List<GeoPoint>, Destination, List<GeoPoint>, List<GeoPoint>, RouteChoice) -> Unit
        )? = null,
    ) = PlanViewModel(
        search = { _, _ -> suggestions },
        planner = planner,
        tileDownloader = tileDownloader,
        location = { originValue },
        cameras = { freshness },
        favoritesStore = favoritesStore,
        vehicle = vehicle,
        placeNamer = placeNamer,
        rangeReader = rangeReader,
        chargeStopFinder = chargeStopFinder,
        onLaterLegsNeeded = onLaterLegsNeeded,
        scope = scope,
    )

    @Test
    fun `charger lookup uses the displayed usable range without a second haircut`() = runTest {
        var reachable = 0.0
        val long = fastest.copy(distanceMeters = 300_000)
        val charger = Destination("Charging site", GeoPoint(39.7, -97.9))
        val model = vm(
            this,
            suggestions = listOf(Suggestion("Destination", dest, "place")),
            outcome = routes(long),
            rangeReader = VehicleRangeReader { RangeReading(180.0, 50) },
            chargeStopFinder = ChargeStopFinder { _, meters ->
                reachable = meters
                listOf(charger)
            },
        )
        model.onQueryChange("Destination"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        val usable = requireNotNull(model.state.value.rangeCheck).usableMeters

        model.onChargeFirst(); advanceUntilIdle()

        assertEquals(usable, reachable, 0.01)
        assertEquals(charger, model.state.value.stops.first())
    }

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
        val suggestions = listOf(Suggestion("Civic Center", dest, "place"))
        val model = vm(this, suggestions = suggestions)
        model.onQueryChange("Civ")
        model.onQueryChange("Civic") // supersedes the first before debounce fires
        advanceUntilIdle()
        assertEquals(suggestions, model.state.value.suggestions)
        assertEquals("Civic", model.state.value.query)
    }

    @Test
    fun `a keystroke that supersedes an in-flight search is not a search failure`() = runTest {
        // Reported from use: typing raises "Couldn't reach search — check your
        // connection", and it stays up until a character is added and removed.
        //
        // Nothing failed. The next keystroke cancels the search in flight, and
        // `runCatching` catches CancellationException like any other throwable —
        // so the act of typing was reporting itself as a network error. The
        // existing debounce test misses it because superseding *before* the
        // debounce fires cancels inside `delay`, which is outside the catch.
        var inFlight = 0
        val model = PlanViewModel(
            search = { _, _ ->
                inFlight++
                kotlinx.coroutines.delay(1_000) // a real round trip
                listOf(Suggestion("Civic Center", dest, "place"))
            },
            planner = { _, _, _ -> routes(fastest) },
            tileDownloader = { _, _, _ -> true },
            location = { origin },
            cameras = { Freshness.NETWORK },
            favoritesStore = InMemoryFavorites(),
            vehicle = FakeVehicleNavClient(),
            scope = this,
        )

        model.onQueryChange("Civ")
        advanceTimeBy(500) // past the debounce: the request is now out
        assertEquals(1, inFlight, "the search has to be in flight for this to test anything")
        model.onQueryChange("Civic") // supersedes it mid-request

        // Straight away, while the replacement search is still debouncing. The
        // banner is transient, which is why it took a real person typing to
        // notice it and why asserting only the settled state misses it.
        advanceTimeBy(10)
        assertTrue(!model.state.value.searchFailed, "typing is not a connection failure")

        advanceUntilIdle()
        assertTrue(!model.state.value.searchFailed)
        assertEquals(1, model.state.value.suggestions.size, "and the newer search still answers")
    }

    @Test
    fun `a superseded search cannot overwrite the newer one's results`() = runTest {
        // The other half: even without the error, a stale answer must not land
        // on top of a fresher one just because it finished later.
        var call = 0
        val model = PlanViewModel(
            search = { query, _ ->
                call++
                // The first search is slow, the second quick — so the stale one
                // completes last and would win on arrival order alone.
                kotlinx.coroutines.delay(if (call == 1) 5_000 else 10)
                listOf(Suggestion("result for $query", dest, "place"))
            },
            planner = { _, _, _ -> routes(fastest) },
            tileDownloader = { _, _, _ -> true },
            location = { origin },
            cameras = { Freshness.NETWORK },
            favoritesStore = InMemoryFavorites(),
            vehicle = FakeVehicleNavClient(),
            scope = this,
        )

        model.onQueryChange("Civ")
        advanceTimeBy(500)
        model.onQueryChange("Civic")
        advanceUntilIdle()

        assertEquals(
            listOf("result for Civic"),
            model.state.value.suggestions.map { it.title },
            "the box says Civic, so the list must be Civic's",
        )
    }

    @Test
    fun `search failure surfaces instead of blanking silently`() = runTest {
        val model = PlanViewModel(
            search = { _, _ -> throw java.io.IOException("offline") },
            planner = { _, _, _ -> routes(fastest) },
            tileDownloader = { _, _, _ -> true },
            location = { origin },
            cameras = { Freshness.NETWORK },
            favoritesStore = InMemoryFavorites(),
            vehicle = FakeVehicleNavClient(),
            scope = this,
        )
        model.onQueryChange("Civic")
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
    fun `an empty result set is a settled no-match, not a failure or a spinner`() = runTest {
        // The geocoder reached fine but the map data has no such place: the UI
        // shows "no matching places", not "searching" and not "couldn't reach".
        val model = vm(this, suggestions = emptyList())
        model.onQueryChange("nowheresville"); advanceUntilIdle()
        val s = model.state.value
        assertTrue(s.suggestions.isEmpty())
        assertTrue(!s.searching, "search settled")
        assertTrue(!s.searchFailed, "reachable, just no matches")
    }

    @Test
    fun `selecting a suggestion routes to options`() = runTest {
        val model = vm(this, suggestions = listOf(Suggestion("Civic Center", dest, "place")))
        model.onQueryChange("Civic"); advanceUntilIdle()
        model.onSuggestionSelected(0)
        advanceUntilIdle()
        val solved = assertIs<Phase.Solved>(model.state.value.phase)
        assertEquals("Civic Center", solved.destination.title)
        assertEquals(0, solved.chosen.camerasPassed)
        assertTrue(model.state.value.suggestions.isEmpty(), "suggestions clear once routing")
    }

    @Test
    fun `planning progress reaches the solving phase for the progress bar`() = runTest {
        val planner = RoutePlanner { _, onProgress, _ ->
            onProgress(0.3f, "Planning routes")
            onProgress(0.85f, "Checking the final route for cameras")
            routes(fastest)
        }
        val seen = mutableListOf<Pair<Float, String>>()
        val model = vm(this, suggestions = listOf(Suggestion("X", dest, "place")), planner = planner)
        model.onQueryChange("X"); advanceUntilIdle()
        // Capture the solving phase as it updates.
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            model.state.collect { s ->
                (s.phase as? Phase.Solving)?.let { seen += it.progress to it.step }
            }
        }
        model.onSuggestionSelected(0); advanceUntilIdle()
        job.cancel()
        assertTrue(seen.any { it.first == 0.85f }, "progress updates were: $seen")
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
        assertEquals(RouteChoice.FEWEST_CAMERAS, solved.chosen.choice)
        model.onSelectRoute(0)
        solved = assertIs<Phase.Solved>(model.state.value.phase)
        assertEquals(RouteChoice.FASTEST, solved.chosen.choice)
    }

    @Test
    fun `the chooser opens on the camera-free option, not the fastest road`() = runTest {
        // The whole point of the app, and it also governs every later leg: the
        // selection is the trade-off later legs are planned to, so opening on
        // FASTEST planned the rest of a long trip as the plain fastest road.
        val balanced = plannedRoute(RouteChoice.BALANCED, 800, added = 200)
        val fewest = plannedRoute(RouteChoice.FEWEST_CAMERAS, 1200, added = 600)
        val model = vm(
            this,
            suggestions = listOf(Suggestion("X", dest, "place")),
            outcome = routes(fastest, balanced, fewest),
        )
        model.onQueryChange("X"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        val solved = assertIs<Phase.Solved>(model.state.value.phase)
        assertEquals(RouteChoice.FEWEST_CAMERAS, solved.chosen.choice)
    }

    @Test
    fun `later legs are asked for against the camera-free option`() = runTest {
        // What the driver never chose still has to mean something: they are
        // shown one chooser for the first leg, and every leg after it is planned
        // to whatever that chooser is sitting on.
        val balanced = plannedRoute(RouteChoice.BALANCED, 800, added = 200)
        val fewest = plannedRoute(RouteChoice.FEWEST_CAMERAS, 1200, added = 600)
        var asked: RouteChoice? = null
        val model = vm(
            this,
            suggestions = listOf(Suggestion("X", dest, "place")),
            outcome = PlanOutcome.Routes(
                listOf(fastest, balanced, fewest),
                remaining = listOf(GeoPoint(39.9, -97.8), dest),
            ),
            onLaterLegsNeeded = { _, _, _, _, preference -> asked = preference },
        )
        model.onQueryChange("X"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        assertEquals(RouteChoice.FEWEST_CAMERAS, asked)
    }

    @Test
    fun `with nothing named, the chooser opens on whatever passes fewest cameras`() = runTest {
        // A leg through empty country comes back with one route and nothing to
        // choose between, so the named option may simply not exist.
        val clean = plannedRoute(RouteChoice.BALANCED, 900)
        val model = vm(
            this,
            suggestions = listOf(Suggestion("X", dest, "place")),
            outcome = routes(withCameras, clean),
        )
        model.onQueryChange("X"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        val solved = assertIs<Phase.Solved>(model.state.value.phase)
        assertEquals(0, solved.chosen.camerasPassed)
    }

    @Test
    fun `a missing tile is downloaded automatically, then routes without a prompt`() = runTest {
        var calls = 0
        val planner = RoutePlanner { _, _, _ ->
            calls++
            if (calls == 1) PlanOutcome.NeedsDownload(listOf(TileId(-100, 35))) else routes(fastest)
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
        assertTrue(downloaded, "download should start on its own")
        assertIs<Phase.Solved>(model.state.value.phase)
    }

    @Test
    fun `a failed auto-download surfaces a retry state`() = runTest {
        val model = vm(
            this,
            suggestions = listOf(Suggestion("X", dest, "place")),
            planner = { _, _, _ -> PlanOutcome.NeedsDownload(listOf(TileId(-100, 35))) },
            tileDownloader = { _, _, _ -> false },
        )
        model.onQueryChange("X"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        val need = assertIs<Phase.NeedTile>(model.state.value.phase)
        assertTrue(need.failed)
        assertTrue(!need.downloading)
    }

    @Test
    fun `a download that never resolves the tile errors instead of looping`() = runTest {
        // Planner always says NeedsDownload; downloader claims success. The guard
        // must stop after one download attempt rather than recurse forever.
        val model = vm(
            this,
            suggestions = listOf(Suggestion("X", dest, "place")),
            planner = { _, _, _ -> PlanOutcome.NeedsDownload(listOf(TileId(-100, 35))) },
            tileDownloader = { _, _, _ -> true },
        )
        model.onQueryChange("X"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        assertIs<Phase.Error>(model.state.value.phase)
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
    fun `a car that takes only a destination is aimed at the first waypoint`() = runTest {
        // The car collapsed the chain to its last point — the destination — so
        // the shape was lost. Point it at the first pin instead: that is the
        // only way the avoidance reaches a single-destination car at all.
        val fake = FakeVehicleNavClient()
        fake.enqueueResult(PushResult.DestinationOnly("one destination only"))
        val model = vm(this, suggestions = listOf(Suggestion("Dest", dest, "place")), vehicle = fake)
        model.onQueryChange("Dest"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        model.onGo(); advanceUntilIdle()

        val driving = assertIs<Phase.Driving>(model.state.value.phase)
        assertTrue(driving.plan.steerByWaypoints, "the monitor must know to steer pin by pin")
        val pushes = fake.calls().filterIsInstance<FakeVehicleNavClient.Call.PushRoute>()
        assertEquals(listOf(fastest.waypoints.first()), pushes.last().waypoints)
    }

    @Test
    fun `a car known to take only a destination is never sent the destination again`() = runTest {
        // Sending the whole chain first collapses to the destination, so the car
        // spends the next few seconds navigating the unshaped route — and
        // planning charging for it — before being redirected. Once we know what
        // this car does, go straight to the pin.
        val fake = FakeVehicleNavClient()
        fake.enqueueResult(PushResult.DestinationOnly("one destination only"))
        val store = InMemoryFavorites(Favorites(home = Destination("Home", dest)))
        val model = vm(this, vehicle = fake, favoritesStore = store)
        model.onFavoriteSelected(FavoriteSlot.HOME); advanceUntilIdle()
        model.onGo(); advanceUntilIdle()

        model.onStopDrive()
        fake.reset()
        fake.enqueueResult(PushResult.DestinationOnly("one destination only"))
        model.onFavoriteSelected(FavoriteSlot.HOME); advanceUntilIdle()
        model.onGo(); advanceUntilIdle()

        val pushes = fake.calls().filterIsInstance<FakeVehicleNavClient.Call.PushRoute>()
        assertEquals(
            listOf(listOf(fastest.waypoints.first())),
            pushes.map { it.waypoints },
            "the second trip should have gone straight to the first pin",
        )
        assertTrue(assertIs<Phase.Driving>(model.state.value.phase).plan.steerByWaypoints)
    }

    @Test
    fun `a trip that will not make it on this charge keeps the destination in the car`() = runTest {
        // Steering points the car a few miles up the road, and a car aiming
        // there plans no charging for the real trip. On a trip that needs a
        // charge, knowing where the charger is beats holding the shape.
        val fake = FakeVehicleNavClient()
        fake.enqueueResult(PushResult.DestinationOnly("one destination only"))
        val model = vm(
            this,
            suggestions = listOf(Suggestion("Dest", dest, "place")),
            outcome = routes(fastest.copy(distanceMeters = 300_000)),
            vehicle = fake,
            rangeReader = VehicleRangeReader { RangeReading(100.0, 40) },
        )
        model.onQueryChange("Dest"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        assertEquals(RangeCheck.Level.SHORT, model.state.value.rangeCheck?.level)

        model.onGo(); advanceUntilIdle()

        val driving = assertIs<Phase.Driving>(model.state.value.phase)
        assertTrue(!driving.plan.steerByWaypoints, "a trip needing a charge must not be steered")
        assertTrue(driving.plan.destinationOnly, "the car still holds the destination")
        val pushes = fake.calls().filterIsInstance<FakeVehicleNavClient.Call.PushRoute>()
        assertEquals(listOf(dest), pushes.single().waypoints.takeLast(1))
        assertEquals(1, pushes.size, "no second push should have redirected it")
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

    @Test
    fun `long-pressing the map routes to that point with its resolved name`() = runTest {
        val pressed = GeoPoint(39.7, -98.2)
        val model = vm(this, placeNamer = { "Prairie Road" })
        model.onMapLongPress(pressed)
        advanceUntilIdle()
        val solved = assertIs<Phase.Solved>(model.state.value.phase)
        assertEquals("Prairie Road", solved.destination.title)
        assertEquals(pressed, solved.destination.location)
    }

    @Test
    fun `a point that cannot be named still routes`() = runTest {
        // Open country, a lake, or the namer being unreachable must not stop the
        // routing — the name is a nicety, the route is the request.
        val model = vm(this, placeNamer = { throw java.io.IOException("offline") })
        model.onMapLongPress(GeoPoint(39.7, -98.2))
        advanceUntilIdle()
        val solved = assertIs<Phase.Solved>(model.state.value.phase)
        assertEquals(PlanViewModel.DROPPED_PIN, solved.destination.title)
    }

    @Test
    fun `with no namer configured the point still routes`() = runTest {
        val model = vm(this, placeNamer = null)
        model.onMapLongPress(GeoPoint(39.7, -98.2))
        advanceUntilIdle()
        assertIs<Phase.Solved>(model.state.value.phase)
    }

    // ---- Stops on the way -----------------------------------------------

    @Test
    fun `a stop is queued without routing, then routed through in order`() = runTest {
        var plannedPoints: List<GeoPoint>? = null
        val planner = RoutePlanner { points, _, _ -> plannedPoints = points; routes(fastest) }
        val stop = GeoPoint(39.6, -97.95)
        val model = vm(
            this,
            suggestions = listOf(Suggestion("Coffee", stop, "cafe")),
            planner = planner,
        )
        model.onQueryChange("Coffee"); advanceUntilIdle()
        model.onSuggestionAddedAsStop(0)
        advanceUntilIdle()

        // Queuing a stop must not start routing — more stops may follow.
        assertIs<Phase.Browsing>(model.state.value.phase)
        assertEquals(1, model.state.value.stops.size)
        assertEquals("", model.state.value.query, "the query clears, ready for the next")

        // Now pick a destination: the trip routes origin → stop → destination.
        model.onQueryChange("X"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        assertEquals(listOf(origin, stop, stop), plannedPoints, "stops must be routed through, in order")
    }

    @Test
    fun `stops can be removed before the trip is planned`() = runTest {
        val model = vm(this, suggestions = listOf(Suggestion("A", dest, "place")))
        model.onQueryChange("A"); advanceUntilIdle()
        model.onSuggestionAddedAsStop(0)
        model.onQueryChange("A"); advanceUntilIdle()
        model.onSuggestionAddedAsStop(0)
        advanceUntilIdle()
        assertEquals(2, model.state.value.stops.size)
        model.onRemoveStop(0)
        assertEquals(1, model.state.value.stops.size)
        // Out-of-range removals are ignored rather than crashing.
        model.onRemoveStop(9)
        assertEquals(1, model.state.value.stops.size)
    }

    @Test
    fun `the drive plan marks the driver's stops so they are not shed like shaping pins`() = runTest {
        val stop = GeoPoint(39.6, -97.95)
        val model = vm(this, suggestions = listOf(Suggestion("Coffee", stop, "cafe")))
        model.onQueryChange("Coffee"); advanceUntilIdle()
        model.onSuggestionAddedAsStop(0); advanceUntilIdle()
        model.onQueryChange("X"); advanceUntilIdle()
        model.onSuggestionSelected(0); advanceUntilIdle()
        model.onGo(); advanceUntilIdle()

        val driving = assertIs<Phase.Driving>(model.state.value.phase)
        assertTrue(
            stop in driving.plan.stopPoints,
            "the stop must be flagged, or the monitor drops it before the car gets there",
        )
    }
}
