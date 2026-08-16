package app.shunt.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shunt.app.plan.Destination
import app.shunt.app.plan.FavoriteSlot
import app.shunt.app.plan.Favorites
import app.shunt.app.plan.Phase
import app.shunt.app.plan.PlanUiState
import app.shunt.core.GeoPoint
import app.shunt.solver.brouter.PlannedRoute
import app.shunt.solver.geo.BoundingBox
import app.shunt.solver.search.Suggestion

/** Everything the vehicle-settings dialog needs, or null to hide the entry point. */
data class VehicleSettingsUi(
    val token: String,
    val vin: String,
    val encryptedStorage: Boolean,
    val onSave: (token: String, vin: String) -> Unit,
    val onClear: () -> Unit,
    val onTestConnection: (suspend (String) -> VehicleCheckResult)? = null,
    val onReadCarState: (suspend (token: String, vin: String) -> CarNavState?)? = null,
    /** Finds out which navigation commands this car obeys. Redirects its nav. */
    val onProbeNav: (suspend (token: String, vin: String, onLine: (NavProbeLine) -> Unit) -> Unit)? = null,
    /** The diagnostic-log corner, or null to leave it out. */
    val diagnostics: DiagnosticsUi? = null,
    /** The practice-camera switch, or null to leave it out. */
    val practice: PracticeUi? = null,
    /** The camera-range slider, or null to leave it out. */
    val cameraRange: CameraRangeUi? = null,
)

/** Callbacks the plan screen raises; wired to PlanViewModel in MainActivity. */
class PlanActions(
    val onQueryChange: (String) -> Unit,
    val onSuggestionSelected: (Int) -> Unit,
    val onSuggestionAddedAsStop: (Int) -> Unit,
    val onRemoveStop: (Int) -> Unit,
    val onFavoriteSelected: (FavoriteSlot) -> Unit,
    val onGo: () -> Unit,
    val onSelectRoute: (Int) -> Unit,
    val onDownloadTile: () -> Unit,
    val onRetryPush: () -> Unit,
    val onDismiss: () -> Unit,
    val onSaveHome: (Destination) -> Unit,
    val onSaveWork: (Destination) -> Unit,
    val onMapLongPress: (GeoPoint) -> Unit,
    val onChargeFirst: () -> Unit,
    val onChargeAlternative: (Int) -> Unit,
    val onRecentSelected: (Int) -> Unit,
    val onChargerPicked: (GeoPoint) -> Unit,
)

@Composable
fun PlanScreen(
    state: PlanUiState,
    actions: PlanActions,
    modifier: Modifier = Modifier,
    cameraViewportFetcher: (suspend (BoundingBox) -> List<MapCamera>)? = null,
    vehicleSettings: VehicleSettingsUi? = null,
    /** Names the charging stop the car inserted mid-drive, when it has. */
    chargingVia: String? = null,
    /** What Shunt is doing with the car right now, for the driving sheet. */
    driveActivity: app.shunt.app.drive.DriveActivity = app.shunt.app.drive.DriveActivity.Watching,
) {
    // The chosen leg, plus every later leg that has been planned so far. The
    // line grows toward the destination as they land rather than appearing whole
    // at the end, which is the visible difference between "still working" and
    // "gave up".
    val overlay = routeOverlay(state.phase).withLaterLegs(state.laterLegs)
    val laterLegLines = state.laterLegs.map { it.polyline }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showVehicleSettings by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        RouteMap(
            routePolyline = overlay.polyline,
            laterLegLines = laterLegLines,
            passedCameras = overlay.passedCameras,
            steeringWaypoints = overlay.waypoints,
            routeCameras = overlay.nearbyCameras,
            chargers = state.chargersOnRoute.mapIndexed { index, place ->
                MapCharger(index.toLong(), place.location.lat, place.location.lon, place.title)
            },
            onChargerSelected = { actions.onChargerPicked(it.let { c -> GeoPoint(c.lat, c.lon) }) },
            modifier = Modifier.fillMaxSize(),
            cameraFetcher = cameraViewportFetcher,
            // Only while browsing: a long press mid-drive would abandon the trip.
            onLongPress = actions.onMapLongPress.takeIf { state.phase is Phase.Browsing },
            destination = destinationOf(state.phase),
        )

        // Whether the search panel is expanded over the map.
        //
        // Held here rather than inside the panel because the scrim below needs
        // it too: the results used to be shown whenever there were any, so there
        // was no way to put them away — tapping the map did nothing, and the
        // list sat over the route until the query was cleared by hand.
        var searchOpen by remember { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current
        fun closeSearch() {
            searchOpen = false
            focusManager.clearFocus()
        }

        // Tapping off the panel puts it away. The scrim only exists while the
        // panel is open, so it never stands between a finger and the map the
        // rest of the time.
        if (searchOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                    .pointerInput(Unit) { detectTapGestures { closeSearch() } },
            )
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (vehicleSettings?.practice?.enabled == true) {
                // Loud, and on the map rather than buried in settings: a driver
                // who forgets this is on is being shown a route that avoids
                // cameras which are not there, and trusting it.
                Banner("Practice cameras are ON — these are invented, not real ALPRs.")
                Spacer(Modifier.height(8.dp))
            }
            if (state.usingOfflineCameraData) {
                Banner("Using offline camera snapshot — data may be out of date.")
                Spacer(Modifier.height(8.dp))
            }
            if (state.phase is Phase.Browsing) {
                SearchAndFavorites(
                    state = state,
                    actions = actions,
                    open = searchOpen,
                    onOpen = { searchOpen = true },
                    onClose = { closeSearch() },
                    onOpenVehicleSettings = vehicleSettings?.let { { showVehicleSettings = true } },
                )
            }
        }

        if (showVehicleSettings && vehicleSettings != null) {
            VehicleSettingsDialog(
                currentToken = vehicleSettings.token,
                currentVin = vehicleSettings.vin,
                encryptedStorage = vehicleSettings.encryptedStorage,
                onSave = vehicleSettings.onSave,
                onClear = vehicleSettings.onClear,
                onTestConnection = vehicleSettings.onTestConnection,
                onReadCarState = vehicleSettings.onReadCarState,
                onProbeNav = vehicleSettings.onProbeNav,
                diagnostics = vehicleSettings.diagnostics,
                practice = vehicleSettings.practice,
                cameraRange = vehicleSettings.cameraRange,
                onDismiss = { showVehicleSettings = false },
            )
        }

        if (state.phase !is Phase.Browsing) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                ResultSheet(
                    phase = state.phase,
                    chargingVia = chargingVia,
                    driveActivity = driveActivity,
                    rangeCheck = state.rangeCheck,
                    findingChargeStop = state.findingChargeStop,
                    chargeStopSearchFailed = state.chargeStopSearchFailed,
                    chargeStopAlternatives = state.chargeStopAlternatives,
                    onChargeFirst = actions.onChargeFirst,
                    onChargeAlternative = actions.onChargeAlternative,
                    onGo = actions.onGo,
                    onSelectRoute = actions.onSelectRoute,
                    onDownloadTile = actions.onDownloadTile,
                    onRetryPush = actions.onRetryPush,
                    onDismiss = actions.onDismiss,
                    onSaveHome = {
                        actions.onSaveHome(it)
                        scope.launch { snackbarHostState.showSnackbar("Saved as Home") }
                    },
                    onSaveWork = {
                        actions.onSaveWork(it)
                        scope.launch { snackbarHostState.showSnackbar("Saved as Work") }
                    },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
    }
}

/**
 * The search bar, and what it expands into.
 *
 * Two states rather than one crowded panel. **Closed** it is a single rounded
 * bar over the map with the favourite chips under it — the map is the point of
 * this screen and everything else was competing with it. **Open** it takes the
 * space it needs for results and dims the map behind, which is both easier to
 * read and the thing that makes "tap away to dismiss" obvious.
 */
@Composable
private fun SearchAndFavorites(
    state: PlanUiState,
    actions: PlanActions,
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onOpenVehicleSettings: (() -> Unit)?,
) {
    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(if (open) 20.dp else 28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            if (state.stops.isNotEmpty()) {
                StopsList(state.stops, actions.onRemoveStop)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (open) onClose() else onOpen() }) {
                    Icon(
                        if (open) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Search,
                        contentDescription = if (open) "Close search" else null,
                    )
                }
                TextField(
                    value = state.query,
                    onValueChange = { actions.onQueryChange(it); onOpen() },
                    singleLine = true,
                    placeholder = { Text("Where to?") },
                    // Borderless: the Surface is already the visible container,
                    // and a text field drawing its own outline inside it read as
                    // a box inside a box.
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { if (it.isFocused) onOpen() },
                )
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { actions.onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                } else if (onOpenVehicleSettings != null && !open) {
                    IconButton(onClick = onOpenVehicleSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Vehicle settings")
                    }
                }
            }

            if (open) {
                SearchResults(state, actions)
            }
        }
    }

    val favorites = state.favorites
    if (!open && (favorites.home != null || favorites.work != null)) {
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            favorites.home?.let {
                FavoriteChip("Home", Icons.Filled.Home) { actions.onFavoriteSelected(FavoriteSlot.HOME) }
            }
            favorites.work?.let {
                FavoriteChip("Work", Icons.Filled.LocationOn) { actions.onFavoriteSelected(FavoriteSlot.WORK) }
            }
        }
    }
}

/**
 * What the open search shows: places already visited that match, then whatever
 * the geocoders found, then an honest account of why there is nothing.
 *
 * Recents lead deliberately. They are instant, they work with no signal, and
 * they cannot be missing — which is the opposite of every other row here.
 */
@Composable
private fun SearchResults(state: PlanUiState, actions: PlanActions) {
    val recents = state.recentsShown
    HorizontalDivider()
    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
        if (recents.isNotEmpty()) {
            item { SectionLabel(if (state.query.isBlank()) "Recent" else "Recent, matching") }
            itemsIndexed(recents) { index, place ->
                PlaceRow(
                    title = place.title,
                    subtitle = "Been here before",
                    icon = Icons.Filled.Refresh,
                    onClick = { actions.onRecentSelected(index) },
                )
                HorizontalDivider()
            }
        }
        if (state.suggestions.isNotEmpty()) {
            if (recents.isNotEmpty()) item { SectionLabel("Search results") }
            itemsIndexed(state.suggestions) { index, suggestion ->
                PlaceRow(
                    title = suggestion.title,
                    subtitle = suggestion.resultType,
                    icon = Icons.Filled.LocationOn,
                    onClick = { actions.onSuggestionSelected(index) },
                    onAddStop = { actions.onSuggestionAddedAsStop(index) },
                )
                HorizontalDivider()
            }
        }
        item {
            when {
                state.searching -> SearchStatus("Searching…")
                state.searchFailed && state.query.isNotBlank() -> SearchStatus(
                    "Couldn't reach search — check your connection.",
                    color = MaterialTheme.colorScheme.error,
                )
                // Distinguish "no such place in the map data" from a silent
                // blank, so an unmatched query reads as a result rather than as
                // a broken search.
                // Not a dead end, and it must not read as one. The map data
                // may simply not name this place — press and hold it on the map
                // and it is named, routed to, and in Recent from then on, which
                // is the only fix that works for a place OSM has not got.
                state.query.isNotBlank() && state.suggestions.isEmpty() && recents.isEmpty() ->
                    SearchStatus(
                        "No match in the map data. Try a fuller name or a nearby town — " +
                            "or press and hold the spot on the map, and it'll be in Recent next time.",
                    )
                state.query.isBlank() && recents.isEmpty() ->
                    SearchStatus("Search for somewhere, or press and hold the map to route there.")
                else -> Unit
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SearchStatus(message: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        message,
        modifier = Modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}

/**
 * One place in the list. [onAddStop] adds the + button that queues it as a stop
 * rather than the destination; recents don't get one, because a place you have
 * driven to before is a destination, not a waypoint.
 */
@Composable
private fun PlaceRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    onAddStop: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp).width(20.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (onAddStop != null) {
            IconButton(onClick = onAddStop) {
                Icon(Icons.Filled.Add, contentDescription = "Add \"$title\" as a stop")
            }
        }
    }
}

/** The stops queued before the destination, in order, each removable. */
@Composable
private fun StopsList(stops: List<Destination>, onRemove: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp)) {
        Text(
            "Stops on the way",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        stops.forEachIndexed { index, stop ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${index + 1}. ${stop.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(index) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove ${stop.title}")
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun FavoriteChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, Modifier.width(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(),
    )
}

@Composable
private fun Banner(message: String, onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth()
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
    ) {
        Text(
            message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** What the map draws for the current phase: the line, passed cameras, and pins. */
private data class RouteOverlay(
    val polyline: List<GeoPoint> = emptyList(),
    val passedCameras: List<GeoPoint> = emptyList(),
    /** The waypoints the car will be steered through. */
    val waypoints: List<GeoPoint> = emptyList(),
    /** Cameras near the route, drawn at any zoom so the avoidance is visible. */
    val nearbyCameras: List<GeoPoint> = emptyList(),
)

/**
 * Where the trip is going, as soon as that is known — which is well before a
 * route exists. Drawn as a pin so a long press or a search result shows on the
 * map instantly instead of after the seconds it takes to plan.
 */
private fun destinationOf(phase: Phase): GeoPoint? = when (phase) {
    is Phase.Solving -> phase.destination.location
    is Phase.NeedTile -> phase.destination.location
    is Phase.Solved -> phase.destination.location
    is Phase.Pushing -> phase.destination.location
    is Phase.PushFailed -> phase.destination.location
    is Phase.Driving -> phase.plan.destination.location
    else -> null
}

/** The overlay with any later legs of the trip appended. */
private fun RouteOverlay.withLaterLegs(legs: List<PlannedRoute>): RouteOverlay {
    if (legs.isEmpty()) return this
    // Deliberately *not* merging the lines: those are drawn as separate
    // features so no straight segment is ever drawn between two legs. Only the
    // cameras and pins are merged, which have no continuity to break.
    return copy(
        passedCameras = passedCameras + legs.flatMap { leg -> leg.passedCameras.map { it.location } },
        waypoints = waypoints + legs.flatMap { it.waypoints },
        nearbyCameras = nearbyCameras + legs.flatMap { leg -> leg.nearbyCameras.map { it.location } },
    )
}

private fun routeOverlay(phase: Phase): RouteOverlay {
    val option: PlannedRoute? = when (phase) {
        is Phase.Solved -> phase.chosen
        is Phase.Pushing -> phase.option
        is Phase.PushFailed -> phase.option
        else -> null
    }
    if (option != null) {
        return RouteOverlay(
            option.polyline,
            option.passedCameras.map { it.location },
            option.waypoints,
            option.nearbyCameras.map { it.location },
        )
    }
    // The driving phase carries a prebuilt plan (polyline + cameras + chain).
    if (phase is Phase.Driving) {
        return RouteOverlay(
            phase.plan.polyline,
            phase.plan.cameras.map { it.location },
            // The last chain entry is the destination itself, not a shaping pin.
            phase.plan.chain.dropLast(1),
        )
    }
    return RouteOverlay()
}
