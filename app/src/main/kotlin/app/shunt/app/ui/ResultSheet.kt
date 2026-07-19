package app.shunt.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shunt.app.plan.Destination
import app.shunt.app.plan.Phase
import app.shunt.app.ui.theme.safeColor
import app.shunt.solver.camera.Camera
import app.shunt.solver.routing.SolveResult

/**
 * The result card — the most important screen in the app. It states which
 * outcome occurred, the added time versus fastest, and the waypoint count;
 * and for a minimum-exposure fallback it makes unmissable that no camera-free
 * route exists, with the count and locations of the cameras the route passes.
 * The user still taps Go — but they tap it knowing what they're accepting.
 */
@Composable
fun ResultSheet(
    phase: Phase,
    onGo: () -> Unit,
    onRetryPush: () -> Unit,
    onDismiss: () -> Unit,
    onSaveHome: (Destination) -> Unit,
    onSaveWork: (Destination) -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            when (phase) {
                is Phase.Solving -> SolvingContent(phase.destination)
                is Phase.Solved -> SolvedContent(phase, onGo, onDismiss, onSaveHome, onSaveWork)
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
        Text("Finding the safest route to ${destination.title}…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SolvedContent(
    phase: Phase.Solved,
    onGo: () -> Unit,
    onDismiss: () -> Unit,
    onSaveHome: (Destination) -> Unit,
    onSaveWork: (Destination) -> Unit,
) {
    when (val result = phase.result) {
        is SolveResult.Clean -> CleanHeader(result)
        is SolveResult.MinimumExposure -> MinimumExposureHeader(result)
        is SolveResult.Failed -> ErrorContent(result.reason, onDismiss)
    }

    if (phase.result !is SolveResult.Failed) {
        Spacer(Modifier.height(16.dp))
        Text(
            "to ${phase.destination.title}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onGo, modifier = Modifier.weight(1f)) { Text("Go") }
            OutlinedButton(onClick = onDismiss) { Text("Back") }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { onSaveHome(phase.destination) }) { Text("Save as Home") }
            TextButton(onClick = { onSaveWork(phase.destination) }) { Text("Save as Work") }
        }
    }
}

@Composable
private fun CleanHeader(result: SolveResult.Clean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = safeColor(), modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text("Camera-free route", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    Text(formatAddedTime(result.addedSecondsVsFastest), style = MaterialTheme.typography.bodyLarge)
    Text(
        "${formatDuration(result.route.durationSeconds)} · ${waypointSummary(result.waypoints.size)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MinimumExposureHeader(result: SolveResult.MinimumExposure) {
    // Unmissable warning banner: this is the whole point of the fallback.
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "No camera-free route exists",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "This route passes ${cameraCount(result.passedCameras.size)}.",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "It passes the fewest cameras of any route found.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "${formatAddedTime(result.addedSecondsVsFastest)} · ${formatDuration(result.route.durationSeconds)} · ${waypointSummary(result.waypoints.size)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Text("Cameras on this route", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    result.passedCameras.forEachIndexed { i, camera -> CameraRow(i + 1, camera) }
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

private fun waypointSummary(count: Int): String =
    if (count == 0) "direct" else "$count waypoint${if (count == 1) "" else "s"}"
