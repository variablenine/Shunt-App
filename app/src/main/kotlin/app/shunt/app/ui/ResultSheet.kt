package app.shunt.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shunt.app.plan.Destination
import app.shunt.app.plan.Phase
import app.shunt.app.ui.theme.safeColor
import app.shunt.solver.brouter.PlannedRoute
import app.shunt.solver.brouter.RouteChoice
import app.shunt.solver.camera.Camera

/**
 * The result card — the most important screen in the app. It offers the route
 * options (fastest → fewest cameras) and, for the selected one, states the
 * added time, distance, and — unmissably — the cameras it passes, so the user
 * taps Go knowing exactly what they're accepting.
 */
@Composable
fun ResultSheet(
    phase: Phase,
    onGo: () -> Unit,
    onSelectRoute: (Int) -> Unit,
    onDownloadTile: () -> Unit,
    onRetryPush: () -> Unit,
    onDismiss: () -> Unit,
    onSaveHome: (Destination) -> Unit,
    onSaveWork: (Destination) -> Unit,
) {
    // Never let the sheet cover the whole screen — keep the route visible above it.
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = maxSheetHeight),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            when (phase) {
                is Phase.Solving -> SolvingContent(phase.destination)
                is Phase.NeedTile -> NeedTileContent(phase, onDownloadTile, onDismiss)
                is Phase.Solved -> SolvedContent(phase, onGo, onSelectRoute, onDismiss, onSaveHome, onSaveWork)
                is Phase.Pushing -> PushingContent(phase.destination)
                is Phase.Driving -> DrivingContent(phase.destination, phase.plan.cameras.size, onDismiss)
                is Phase.PushFailed -> PushFailedContent(phase, onRetryPush, onDismiss)
                is Phase.Error -> ErrorContent(phase.message, onDismiss)
                Phase.Browsing -> Unit
            }
        }
    }
}

@Composable
private fun SolvingContent(destination: Destination) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(14.dp))
        Text("Finding routes to ${destination.title}…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NeedTileContent(phase: Phase.NeedTile, onRetry: () -> Unit, onDismiss: () -> Unit) {
    if (phase.failed) {
        Text("Couldn't get the offline map", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Shunt routes on your device and needs this area's map once. The " +
                "download failed — check your connection and try again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("Retry") }
            OutlinedButton(onClick = onDismiss) { Text("Back") }
        }
        return
    }
    // Auto-download in progress — no prompt, just get the map and route.
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(14.dp))
        Text(
            "Getting the offline map for this area…",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    Spacer(Modifier.height(14.dp))
    LinearProgressIndicator(
        progress = { phase.progress.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "${(phase.progress * 100).toInt()}% · one-time, then routing here works offline",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SolvedContent(
    phase: Phase.Solved,
    onGo: () -> Unit,
    onSelectRoute: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSaveHome: (Destination) -> Unit,
    onSaveWork: (Destination) -> Unit,
) {
    Text(
        "to ${phase.destination.title}",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(12.dp))

    // Choose a route. With only one option (no cameras nearby) this is a single
    // card; otherwise it's the fastest → fewest-cameras spectrum.
    phase.options.forEachIndexed { index, option ->
        RouteOptionCard(option, selected = index == phase.selected) { onSelectRoute(index) }
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(4.dp))
    SelectedRouteDetail(phase.chosen)

    Spacer(Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onGo, modifier = Modifier.weight(1f)) { Text("Go") }
        OutlinedButton(onClick = onDismiss) { Text("Back") }
    }
    Spacer(Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { onSaveHome(phase.destination) }) { Text("Save as Home") }
        TextButton(onClick = { onSaveWork(phase.destination) }) { Text("Save as Work") }
    }
}

@Composable
private fun RouteOptionCard(option: PlannedRoute, selected: Boolean, onClick: () -> Unit) {
    val cameraFree = option.camerasPassed == 0
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(choiceLabel(option.choice), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatDuration(option.estimatedSeconds)} · ${formatKm(option.distanceMeters)}" +
                        addedTimeSuffix(option.addedSecondsVsFastest),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CameraBadge(option.camerasPassed, cameraFree)
        }
    }
}

@Composable
private fun CameraBadge(count: Int, cameraFree: Boolean) {
    if (cameraFree) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = safeColor(), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("camera-free", style = MaterialTheme.typography.labelMedium, color = safeColor())
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                cameraCount(count),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SelectedRouteDetail(option: PlannedRoute) {
    if (option.camerasPassed == 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = safeColor(), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "This route passes no cameras.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        return
    }
    // Cameras on the selected route — the exposure the user is accepting.
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Passes ${cameraCount(option.camerasPassed)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "You'll be alerted on approach to each while driving.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            // Cap the list so a camera-heavy route doesn't push everything (and
            // the map) off-screen — it scrolls within this bounded box instead.
            Column(
                modifier = Modifier
                    .heightIn(max = 190.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                option.passedCameras.forEachIndexed { i, camera -> CameraRow(i + 1, camera) }
            }
        }
    }
}

@Composable
private fun CameraRow(number: Int, camera: Camera) {
    val manufacturer = camera.tags["manufacturer"] ?: camera.tags["operator"]
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$number",
            modifier = Modifier
                .size(22.dp)
                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(50))
                .padding(top = 1.dp),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            buildString {
                append("%.5f, %.5f".format(camera.location.lat, camera.location.lon))
                if (manufacturer != null) append("  ·  $manufacturer")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun PushingContent(destination: Destination) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(14.dp))
        Text("Sending route to your vehicle…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DrivingContent(destination: Destination, cameraCount: Int, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = safeColor(), modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text("Driving to ${destination.title}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        if (cameraCount > 0) {
            "Route sent. Monitoring — you'll be alerted on approach to each of the ${cameraCount(cameraCount)}."
        } else {
            "Route sent. Monitoring your drive; waypoints advance automatically."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Alerts are haptic and audible — no need to watch the screen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel drive") }
}

@Composable
private fun PushFailedContent(phase: Phase.PushFailed, onRetryPush: () -> Unit, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text("Couldn't send route", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    Text(phase.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (phase.retryable) {
            Button(onClick = onRetryPush, modifier = Modifier.weight(1f)) { Text("Retry") }
        }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Back") }
    }
}

@Composable
private fun ErrorContent(message: String, onDismiss: () -> Unit) {
    Text("Couldn't plan this trip", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(16.dp))
    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

private fun choiceLabel(choice: RouteChoice): String = when (choice) {
    RouteChoice.FASTEST -> "Fastest"
    RouteChoice.BALANCED -> "Balanced"
    RouteChoice.FEWEST_CAMERAS -> "Fewest cameras"
}

private fun formatKm(meters: Int): String = "%.1f km".format(meters / 1000.0)

private fun addedTimeSuffix(addedSeconds: Int): String {
    if (addedSeconds <= 0) return ""
    val minutes = (addedSeconds / 60.0).let { if (it < 1) 1 else it.toInt() }
    return "  ·  +$minutes min"
}
