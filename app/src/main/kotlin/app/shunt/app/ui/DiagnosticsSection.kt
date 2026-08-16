package app.shunt.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.shunt.app.diag.DiagnosticLog

/**
 * The bug-report corner: choose how much of the log to send and whether it says
 * where you drove, then hand it to an email app.
 *
 * The two controls are the whole point rather than decoration. A log of a
 * navigation app is a record of somebody's movements, so the person sending it
 * has to be the one who decides what is in it — and has to be told plainly what
 * each choice means, at the moment they make it, not in a settings page they
 * read once.
 */
@Composable
fun DiagnosticsSection(
    entryCount: Int,
    onExport: (DiagnosticLog.Export) -> Unit,
    onClear: () -> Unit,
) {
    var window by remember { mutableStateOf(DiagnosticLog.Window.DAY) }
    var includeLocations by remember { mutableStateOf(false) }

    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text("Report a problem", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Shunt keeps a week of what it did on this phone — nothing is ever sent " +
            "anywhere on its own. Export it and attach it to an email so the " +
            "problem can be found.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(10.dp))
    Text("How much to include", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in DiagnosticLog.Window.entries) {
            FilterChip(
                selected = window == option,
                onClick = { window = option },
                label = { Text("Last ${option.label}") },
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Include where you drove", style = MaterialTheme.typography.bodyMedium)
            Text(
                // Said at the point of decision, and said in terms of what it
                // costs rather than what it enables: this is the toggle that
                // turns a bug report into a travel history.
                if (includeLocations) {
                    "The file will contain coordinates of your route. Only send it to someone you trust."
                } else {
                    "Coordinates are removed. Some route problems can't be diagnosed without them."
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (includeLocations) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Switch(checked = includeLocations, onCheckedChange = { includeLocations = it })
    }

    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            enabled = entryCount > 0,
            onClick = { onExport(DiagnosticLog.Export(window = window, includeLocations = includeLocations)) },
        ) {
            Text(if (entryCount > 0) "Export log ($entryCount entries)" else "Nothing logged yet")
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onClear) { Text("Delete log") }
    }
}

/**
 * Practice mode: invent a field of cameras so avoidance can be exercised where
 * the real ones have been taken out.
 *
 * Separated from the diagnostics block above because it is the opposite kind of
 * thing — that one *reports* what happened, this one *changes* what the app
 * believes about the world. Anything that does that has to be hard to leave on
 * by accident, which is why it says what it costs and why the map carries a
 * banner for as long as it is on.
 */
@Composable
fun PracticeCamerasSetting(enabled: Boolean, onChange: (Boolean) -> Unit) {
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text("Practice cameras", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (enabled) {
                    "ON — routes are avoiding cameras that do not exist. Turn this off before driving for real."
                } else {
                    "Add a made-up, always-identical set of cameras so you can test avoidance " +
                        "somewhere the real ones have been removed. They are marked as not real everywhere."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

/**
 * How far a camera is treated as seeing, as a percentage of the built-in
 * estimate.
 *
 * Nobody publishes the read range of an ALPR, and it varies with the lens, the
 * mounting height, the traffic speed and the weather — so the figure in the app
 * is a *policy* about standoff rather than a measurement, and it is worth being
 * able to turn. Higher plans wider detours around the same cameras; lower
 * accepts closer passes for shorter trips.
 */
@Composable
fun CameraRangeSetting(percent: Int, onChange: (Int) -> Unit) {
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text("How far cameras see", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        when {
            percent > 100 -> "$percent% — routes give cameras a wider berth, and detour more to do it."
            percent < 100 -> "$percent% — routes pass closer to cameras, and detour less."
            else -> "$percent% — the built-in estimate."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Applies to routing, the camera counts, the drive warnings and the cones " +
            "on the map together, so they always agree.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = percent.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        valueRange = MIN_CAMERA_RANGE_PERCENT.toFloat()..MAX_CAMERA_RANGE_PERCENT.toFloat(),
        steps = 0,
    )
}

/** Bounds on the camera-range setting; a scale of zero would disable avoidance. */
const val MIN_CAMERA_RANGE_PERCENT = 25
const val MAX_CAMERA_RANGE_PERCENT = 400
