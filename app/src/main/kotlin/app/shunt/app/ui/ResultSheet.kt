package app.shunt.app.ui

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shunt.app.plan.Destination
import app.shunt.app.drive.DriveActivity
import app.shunt.app.plan.Phase
import app.shunt.app.ui.theme.safeColor
import app.shunt.solver.brouter.PlannedRoute
import app.shunt.solver.brouter.RouteChoice
import app.shunt.solver.camera.Camera
import app.shunt.solver.charging.RangeCheck

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
    /** The charging stop the car added on its own, when it has added one. */
    chargingVia: String? = null,
    /** What Shunt is doing with the car right now — see [DriveActivity]. */
    driveActivity: DriveActivity = DriveActivity.Watching,
    /** How the chosen route compares with the car's range; null = no claim. */
    rangeCheck: RangeCheck? = null,
    findingChargeStop: Boolean = false,
    chargeStopSearchFailed: Boolean = false,
    chargeStopAlternatives: List<Destination> = emptyList(),
    onChargeFirst: () -> Unit = {},
    onChargeAlternative: (Int) -> Unit = {},
    /** Legs planned after this one, so the totals can grow as they land. */
    laterLegs: List<PlannedRoute> = emptyList(),
    /** Whether more legs are still coming. */
    planningLaterLegs: Boolean = false,
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
                is Phase.Solving -> SolvingContent(phase)
                is Phase.NeedTile -> NeedTileContent(phase, onDownloadTile, onDismiss)
                is Phase.Solved -> SolvedContent(
                    phase, onGo, onSelectRoute, onDismiss, onSaveHome, onSaveWork,
                    rangeCheck, findingChargeStop, chargeStopSearchFailed,
                    chargeStopAlternatives, onChargeFirst, onChargeAlternative,
                    laterLegs, planningLaterLegs,
                )
                is Phase.Pushing -> PushingContent(phase.destination)
                is Phase.Driving -> DrivingContent(phase, chargingVia, driveActivity, onDismiss)
                is Phase.PushFailed -> PushFailedContent(phase, onRetryPush, onDismiss)
                is Phase.Error -> ErrorContent(phase.message, onDismiss)
                Phase.Browsing -> Unit
            }
        }
    }
}

@Composable
private fun SolvingContent(phase: Phase.Solving) {
    Text(
        "Finding routes to ${phase.destination.title}…",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(12.dp))
    // A determinate bar: a cross-state plan runs several routing passes over a
    // wide camera set, so an unexplained spinner reads as a hang.
    val progress by animateFloatAsState(
        targetValue = phase.progress.coerceIn(0f, 1f),
        label = "plan-progress",
    )
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        phase.step,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    rangeCheck: RangeCheck?,
    findingChargeStop: Boolean,
    chargeStopSearchFailed: Boolean,
    chargeStopAlternatives: List<Destination>,
    onChargeFirst: () -> Unit,
    onChargeAlternative: (Int) -> Unit,
    laterLegs: List<PlannedRoute>,
    planningLaterLegs: Boolean,
) {
    Text(
        "to ${phase.destination.title}",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (phase.remaining.isNotEmpty()) {
        // These options cover the first leg only, and every number below is a
        // number about that leg. Presenting them as the whole trip would be a
        // plain lie — the distance, the time and the camera count are all short.
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    "Showing the first part of the trip",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                // Just the fact. The old wording explained *why* Shunt splits a
                // trip, which is interesting exactly once and is in the way
                // every time after that — and it is in the way in a car.
                Text(
                    phase.wholeTripMeters?.let { "${it / 1000} km total · rest planned as you drive" }
                        ?: "Rest of the trip planned as you drive",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )

                // What the whole trip looks like so far, growing as each leg
                // lands. Reported as missing: "the fastest and fewest cameras
                // menu should update information as legs are calculated because
                // right now it only gives info on the first leg."
                //
                // Kept out of the option cards themselves, and that is not
                // squeamishness — it is the only honest place for it. Those
                // cards are a *choice* between three routes for this leg, while
                // the later legs are planned once, as few-cameras as they can
                // be, and they start from the same boundary whichever card is
                // picked. Adding their distance into "Fastest" would describe a
                // trip nobody is being offered.
                val selected = phase.options.getOrNull(phase.selected)
                if (selected != null && (laterLegs.isNotEmpty() || planningLaterLegs)) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.25f))
                    Spacer(Modifier.height(8.dp))

                    val legs = listOf(selected) + laterLegs
                    val metres = legs.sumOf { it.distanceMeters.toLong() }
                    val seconds = legs.sumOf { it.estimatedSeconds.toLong() }
                    val cameras = legs.sumOf { it.camerasPassed }
                    Text(
                        if (planningLaterLegs) "Whole trip so far" else "Whole trip",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        "${formatDuration(seconds.toInt())} · ${metres / 1000} km · " +
                            (if (cameras == 0) "camera-free" else cameraCount(cameras)) +
                            "  (${legs.size} " + (if (legs.size == 1) "leg" else "legs") + ")",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (cameras == 0) safeColor() else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (planningLaterLegs) {
                        Text(
                            "still planning…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
    if (phase.carriedForward) {
        // Said plainly, because the alternative is a route that looks fully
        // considered and is not. The counts below are true — every option is
        // measured against the whole camera set — but the search that produced
        // them ran against a narrower one before it ran out of time.
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    "Planning ran out of time",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "There may be a better route. Camera counts are still exact.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    // Choose a route. With only one option (no cameras nearby) this is a single
    // card; otherwise it's the fastest → fewest-cameras spectrum.
    phase.options.forEachIndexed { index, option ->
        RouteOptionCard(
            option,
            selected = index == phase.selected,
            partial = phase.remaining.isNotEmpty(),
        ) { onSelectRoute(index) }
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(4.dp))
    SelectedRouteDetail(phase.chosen, partial = phase.remaining.isNotEmpty())

    phase.timings?.let {
        Spacer(Modifier.height(12.dp))
        PlanningTimeBreakdown(it)
    }

    // Range comes after the route detail and before Go: it's about the option
    // just chosen, and it's the last thing worth knowing before setting off.
    if (rangeCheck != null) {
        Spacer(Modifier.height(12.dp))
        RangeWarning(
            rangeCheck, findingChargeStop, chargeStopSearchFailed,
            chargeStopAlternatives, onChargeFirst, onChargeAlternative,
        )
    }

    // Immediately above Go — the moment the user decides to hand this to the car.
    Spacer(Modifier.height(14.dp))
    TeslaWipWarning()

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

/**
 * The battery warning for the route about to be driven.
 *
 * Shunt's job is to route *around* things, which makes its routes longer than
 * the ones a car's own trip planner costs out — and the car only ever sees the
 * destination, never the detour. So a trip that Tesla would happily start can
 * still run the battery out on the road Shunt picked. Nothing else in the app
 * is in a position to notice that, so it's said here, next to the option that
 * causes it, with a way to fix it in one tap.
 */
/**
 * Where the planning time went.
 *
 * **Temporary.** A long route takes far longer to plan than is usable, and the
 * only machine that can say which part is slow is a real phone with real map
 * tiles. Remove this, and [app.shunt.solver.brouter.PlanTimings] behind it, once
 * that is fixed — it is a measurement, not a feature.
 */
@Composable
private fun PlanningTimeBreakdown(timings: app.shunt.solver.brouter.PlanTimings) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Planned in ${formatSeconds(timings.totalMillis)}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            timings.stages.forEach { stage ->
                TimingRow(stage.label, stage.millis, scheme.onSurfaceVariant)
            }
            if (timings.routingPasses.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Each search over the road graph",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(4.dp))
                timings.routingPasses.forEach { pass ->
                    TimingRow("  ${pass.label}", pass.millis, scheme.onSurfaceVariant.copy(alpha = 0.8f))
                }
            }
        }
    }
}

@Composable
private fun TimingRow(label: String, millis: Long, color: androidx.compose.ui.graphics.Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
        Text(
            formatSeconds(millis),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

/** "0.4 s" / "12.7 s" / "2 m 05 s" — readable at a glance, no more precision than earned. */
private fun formatSeconds(millis: Long): String {
    val seconds = millis / 1000.0
    return if (seconds < 60) {
        "${(seconds * 10).toLong() / 10.0} s"
    } else {
        val whole = millis / 1000
        "${whole / 60} m ${(whole % 60).toString().padStart(2, '0')} s"
    }
}

@Composable
private fun RangeWarning(
    check: RangeCheck,
    findingChargeStop: Boolean,
    searchFailed: Boolean,
    alternatives: List<Destination>,
    onChargeFirst: () -> Unit,
    onChargeAlternative: (Int) -> Unit,
) {
    if (check.level == RangeCheck.Level.FINE) return
    val short = check.level == RangeCheck.Level.SHORT
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (short) scheme.errorContainer else scheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val onColor = if (short) scheme.onErrorContainer else scheme.onSecondaryContainer
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (short) scheme.error else onColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        check.detourIsTheProblem -> "This detour outruns your battery"
                        short && check.hasChargingStops -> "Still short, even with the charging stop"
                        short -> "Not enough range for this trip"
                        check.hasChargingStops -> "Tight between charges"
                        else -> "This will be tight on range"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = onColor,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    if (check.hasChargingStops) {
                        // With a charging stop the total stopped being the
                        // question: what matters is whether each leg fits.
                        append("Split by ${check.legMeters.size - 1} charging stop")
                        if (check.legMeters.size > 2) append("s")
                        append(", the longest leg is ${formatKm(check.legMeters.max())}")
                        append(" against about ${formatKm(check.chargedUsableMeters.toInt())}")
                        append(" of usable range from a charge.")
                    } else {
                        append("This route is ${formatKm(check.routeMeters)}")
                        check.batteryPercent?.let { append(" and you're at $it%") }
                        append(". Allowing for real-world driving that's about ")
                        append("${formatKm(check.usableMeters.toInt())} of usable range")
                        if (check.detourIsTheProblem) {
                            append(" — the shortest option (${formatKm(check.shortestOptionMeters)}) ")
                            append("would make it, but the camera-avoiding one won't")
                        }
                        append(".")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = onColor,
            )
            if (short) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (check.hasChargingStops) {
                        "Even split like this a leg runs past what one charge covers. " +
                            "Add another stop, or start with more in the battery."
                    } else {
                        "Your car plans charging for the direct route, so it may not " +
                            "add a stop for this one. Charge before you go."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = onColor,
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = onChargeFirst,
                    enabled = !findingChargeStop,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (findingChargeStop) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Finding a charger on the way…")
                    } else {
                        Text(
                            if (check.hasChargingStops) "Add another charging stop"
                            else "Add a charging stop on the way",
                        )
                    }
                }
                if (searchFailed) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "No charging site found on this route in OpenStreetMap. " +
                            "Add one as a stop yourself, or charge before setting off.",
                        style = MaterialTheme.typography.labelSmall,
                        color = onColor,
                    )
                }
                if (alternatives.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Other reachable charging sites", style = MaterialTheme.typography.labelMedium)
                    alternatives.forEachIndexed { index, alternative ->
                        TextButton(onClick = { onChargeAlternative(index) }) {
                            Text(alternative.title)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteOptionCard(
    option: PlannedRoute,
    selected: Boolean,
    /**
     * Whether these cards describe one leg of a longer trip.
     *
     * The badge says "camera-free", and on a split trip that is a claim about
     * **this leg** while the trip as a whole may pass plenty — which reads as a
     * flat contradiction next to the whole-trip line right above it. Reported
     * exactly that way: "it says camera free even though as a whole we end up on
     * a route that isn't camera free." So the badge says which it is talking
     * about whenever the two can differ.
     */
    partial: Boolean,
    onClick: () -> Unit,
) {
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
            CameraBadge(option.camerasPassed, cameraFree, partial)
        }
    }
}

@Composable
private fun CameraBadge(count: Int, cameraFree: Boolean, partial: Boolean = false) {
    if (cameraFree) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = safeColor(), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (partial) "leg is camera-free" else "camera-free",
                style = MaterialTheme.typography.labelMedium,
                color = safeColor(),
            )
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
private fun SelectedRouteDetail(option: PlannedRoute, partial: Boolean = false) {
    if (option.camerasPassed == 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = safeColor(), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (partial) "This leg passes no cameras." else "This route passes no cameras.",
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
                // A phrase each, not a paragraph. The number above is the
                // message; this only has to say what kind of number it is.
                when {
                    option.hardAvoidanceFailed -> "No camera-free route found"
                    option.unavoidableAtEndpoints >= option.camerasPassed ->
                        "At your start or destination — unavoidable"
                    option.unavoidableAtEndpoints > 0 ->
                        "${option.unavoidableAtEndpoints} at your start or destination"
                    else -> "You'll be warned on approach"
                },
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
    Spacer(Modifier.height(14.dp))
    TeslaWipWarning()
}

@Composable
private fun DrivingContent(
    phase: Phase.Driving,
    chargingVia: String?,
    activity: DriveActivity,
    onCancel: () -> Unit,
) {
    val destination = phase.destination
    val cameraCount = phase.plan.cameras.size
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = safeColor(), modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text("Driving to ${destination.title}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
    if (chargingVia != null) {
        // The car quietly added a leg the driver never asked for. Say so, and
        // say that Shunt is routing it rather than leaving it to the car.
        Spacer(Modifier.height(10.dp))
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Charging first at $chargingVia",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Your car added this stop itself. Shunt has planned the route to " +
                        "it with camera avoidance, and will route the rest of the trip " +
                        "once you're charged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
    // What it is doing at this instant. Reported from a real drive: "it's
    // impossible right now to tell what the app is doing" — all of this was
    // already happening, just silently unless it failed.
    Spacer(Modifier.height(10.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (activity !is DriveActivity.Watching && activity !is DriveActivity.StoodDown) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                when (activity) {
                    is DriveActivity.Watching -> "Watching for cameras"
                    is DriveActivity.SendingWaypoint ->
                        "Sending waypoint ${activity.number} of ${activity.total} to the car"
                    is DriveActivity.CheckingCharging -> "Asking your car about charging"
                    is DriveActivity.Replanning -> "Re-planning from here"
                    is DriveActivity.StoodDown -> "Not steering — the car is yours"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    if (phase.plan.steerByWaypoints) {
        // The car takes one destination, so it is being walked along the route
        // a pin at a time. The car's own screen will name somewhere a few miles
        // away rather than the destination — that is the mechanism working, and
        // the driver has to know it before it worries them.
        Spacer(Modifier.height(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Guiding your car waypoint by waypoint",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Your car only accepts one destination at a time, so Shunt sends it " +
                        "the next point on the camera-avoiding route and moves that point " +
                        "along as you drive. Your car will show somewhere nearby rather " +
                        "than ${destination.title} until the last leg — that's expected. " +
                        "It won't plan charging for the whole trip while it's being guided " +
                        "this way, so watch your range.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    } else if (phase.destinationOnly) {
        // The car took the destination but not the shaped route, so it is
        // navigating its own way — which may go past cameras this route
        // avoided. Say so plainly; the phone alerts still follow OUR route.
        Spacer(Modifier.height(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Your car only accepted the destination",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "It's navigating there its own way, NOT along the camera-avoiding " +
                        "route shown here — so it may drive past cameras this route " +
                        "avoids. Shunt still warns you on approach to every camera on " +
                        "the planned route. Follow the map, not the car's directions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }

    // Compact form: this is the screen that's up while actually driving.
    Spacer(Modifier.height(12.dp))
    TeslaWipWarning(compact = true)
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
