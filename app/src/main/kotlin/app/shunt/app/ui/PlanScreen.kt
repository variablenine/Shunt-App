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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/** Callbacks the plan screen raises; wired to PlanViewModel in MainActivity. */
class PlanActions(
    val onQueryChange: (String) -> Unit,
    val onSuggestionSelected: (Int) -> Unit,
    val onFavoriteSelected: (FavoriteSlot) -> Unit,
    val onGo: () -> Unit,
    val onSelectRoute: (Int) -> Unit,
    val onDownloadTile: () -> Unit,
    val onRetryPush: () -> Unit,
    val onDismiss: () -> Unit,
    val onSaveHome: (Destination) -> Unit,
    val onSaveWork: (Destination) -> Unit,
)

@Composable
fun PlanScreen(
    state: PlanUiState,
    actions: PlanActions,
    modifier: Modifier = Modifier,
    cameraViewportFetcher: (suspend (BoundingBox) -> List<MapCamera>)? = null,
) {
    val (polyline, cameras) = routeOverlay(state.phase)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        RouteMap(
            routePolyline = polyline,
            passedCameras = cameras,
            modifier = Modifier.fillMaxSize(),
            cameraFetcher = cameraViewportFetcher,
        )

        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            if (state.usingOfflineCameraData) {
                Banner("Using offline camera snapshot — data may be out of date.")
                Spacer(Modifier.height(8.dp))
            }
            if (state.phase is Phase.Browsing) {
                SearchAndFavorites(state, actions)
            }
        }

        if (state.phase !is Phase.Browsing) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                ResultSheet(
                    phase = state.phase,
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
private fun SearchAndFavorites(state: PlanUiState, actions: PlanActions) {
    Surface(tonalElevation = 2.dp, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQueryChange,
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Where to?") },
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.suggestions.isNotEmpty()) {
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(state.suggestions) { suggestion ->
                        SuggestionRow(suggestion) {
                            actions.onSuggestionSelected(state.suggestions.indexOf(suggestion))
                        }
                        HorizontalDivider()
                    }
                }
            } else if (state.searchFailed && state.query.isNotBlank()) {
                Text(
                    "Couldn't reach search — check your connection.",
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

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
private fun SuggestionRow(suggestion: Suggestion, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(suggestion.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            suggestion.resultType,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
