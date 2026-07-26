package app.shunt.app.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
) {
    val (polyline, cameras) = routeOverlay(state.phase)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showVehicleSettings by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        RouteMap(
            routePolyline = polyline,
            passedCameras = cameras,
            modifier = Modifier.fillMaxSize(),
            cameraFetcher = cameraViewportFetcher,
            // Only while browsing: a long press mid-drive would abandon the trip.
            onLongPress = actions.onMapLongPress.takeIf { state.phase is Phase.Browsing },
        )

        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            if (state.usingOfflineCameraData) {
                Banner("Using offline camera snapshot — data may be out of date.")
                Spacer(Modifier.height(8.dp))
            }
            if (state.phase is Phase.Browsing) {
                SearchAndFavorites(
                    state,
                    actions,
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
                onDismiss = { showVehicleSettings = false },
            )
        }

        if (state.phase !is Phase.Browsing) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                ResultSheet(
                    phase = state.phase,
                    chargingVia = chargingVia,
                    rangeCheck = state.rangeCheck,
                    findingChargeStop = state.findingChargeStop,
                    chargeStopSearchFailed = state.chargeStopSearchFailed,
                    onChargeFirst = actions.onChargeFirst,
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

@Composable
private fun SearchAndFavorites(
    state: PlanUiState,
    actions: PlanActions,
    onOpenVehicleSettings: (() -> Unit)?,
) {
    Surface(tonalElevation = 2.dp, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (state.stops.isNotEmpty()) {
                StopsList(state.stops, actions.onRemoveStop)
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = actions.onQueryChange,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Where to?") },
                    modifier = Modifier.weight(1f),
                )
                if (onOpenVehicleSettings != null) {
                    IconButton(onClick = onOpenVehicleSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Vehicle settings")
                    }
                }
            }

            if (state.suggestions.isNotEmpty()) {
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(state.suggestions) { suggestion ->
                        val index = state.suggestions.indexOf(suggestion)
                        SuggestionRow(
                            suggestion = suggestion,
                            onClick = { actions.onSuggestionSelected(index) },
                            onAddStop = { actions.onSuggestionAddedAsStop(index) },
                        )
                        HorizontalDivider()
                    }
                }
            } else if (state.searching) {
                SearchStatus("Searching…")
            } else if (state.searchFailed && state.query.isNotBlank()) {
                SearchStatus(
                    "Couldn't reach search — check your connection.",
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (state.query.isNotBlank()) {
                // Distinguish "no such place in the map data" from a silent blank,
                // so an unmatched query reads as a result, not a broken search.
                SearchStatus("No matching places found. Try a fuller name or a nearby town.")
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Tip: press and hold anywhere on the map to route there.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val favorites = state.favorites
            if (favorites.home != null || favorites.work != null) {
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
    }
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

@Composable
private fun SuggestionRow(suggestion: Suggestion, onClick: () -> Unit, onAddStop: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 12.dp),
        ) {
            Text(suggestion.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                suggestion.resultType,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Tapping the row goes there; the + queues it as a stop on the way.
        IconButton(onClick = onAddStop) {
            Icon(Icons.Filled.Add, contentDescription = "Add \"${suggestion.title}\" as a stop")
        }
    }
}

/** The stops queued before the destination, in order, each removable. */
@Composable
private fun StopsList(stops: List<Destination>, onRemove: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(index) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove ${stop.title}")
                }
            }
        }
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

/** The route line + passed-camera points to draw for the current phase. */
private fun routeOverlay(phase: Phase): Pair<List<GeoPoint>, List<GeoPoint>> {
    val option: PlannedRoute? = when (phase) {
        is Phase.Solved -> phase.chosen
        is Phase.Pushing -> phase.option
        is Phase.PushFailed -> phase.option
        else -> null
    }
    if (option != null) return option.polyline to option.passedCameras.map { it.location }
    // The driving phase carries a prebuilt plan (polyline + cameras).
    if (phase is Phase.Driving) {
        return phase.plan.polyline to phase.plan.cameras.map { it.location }
    }
    return emptyList<GeoPoint>() to emptyList()
}
