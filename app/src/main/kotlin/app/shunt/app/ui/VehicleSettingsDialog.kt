package app.shunt.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Where the user supplies their own Tessie credentials, so a shipped APK need
 * not embed anyone's. Without them the app is fully usable — it just drives the
 * fake vehicle client instead of a real car.
 */
@Composable
fun VehicleSettingsDialog(
    currentToken: String,
    currentVin: String,
    encryptedStorage: Boolean,
    onSave: (token: String, vin: String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * The bug-report corner. Absent, the dialog is exactly what it was — this
     * is the only settings surface in the app, so the log export lives here
     * rather than growing a second one.
     */
    diagnostics: DiagnosticsUi? = null,
    /** The practice-camera switch, or null to leave it out. */
    practice: PracticeUi? = null,
    /** The camera-range slider, or null to leave it out. */
    cameraRange: CameraRangeUi? = null,
    /**
     * Verifies the token by listing the account's vehicles. Read-only: it sends
     * no command, so it can't make the car do anything.
     */
    onTestConnection: (suspend (String) -> VehicleCheckResult)? = null,
    /** Reads what the car currently thinks it's navigating to. Read-only. */
    onReadCarState: (suspend (token: String, vin: String) -> CarNavState?)? = null,
    /**
     * Tries every navigation channel against the car and reports which ones it
     * actually obeys. NOT read-only — it redirects the car's navigation.
     */
    onProbeNav: (suspend (token: String, vin: String, onLine: (NavProbeLine) -> Unit) -> Unit)? = null,
) {
    var token by remember { mutableStateOf(currentToken) }
    var vin by remember { mutableStateOf(currentVin) }
    var revealToken by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<VehicleCheckResult?>(null) }
    var navState by remember { mutableStateOf<CarNavState?>(null) }
    var readingNav by remember { mutableStateOf(false) }
    var navRead by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    val probeLines = remember { mutableStateListOf<NavProbeLine>() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect your Tesla") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Shunt sends routes to your car through Tessie, using your own " +
                        "account. Leave this empty and everything else still works — " +
                        "planning, the map, and drive alerts — with no car attached.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    singleLine = true,
                    label = { Text("Tessie access token") },
                    placeholder = { Text("Paste your token") },
                    visualTransformation = if (revealToken) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { revealToken = !revealToken }) {
                    Text(if (revealToken) "Hide token" else "Show token")
                }
                Text(
                    "Get it from tessie.com → Settings → API. It can command your " +
                        "car, so treat it like a key.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = vin,
                    onValueChange = { vin = it },
                    singleLine = true,
                    label = { Text("Vehicle VIN") },
                    placeholder = { Text("5YJ…") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Shown in the Tesla app under the car's details — or just tap " +
                        "Test connection below and Shunt will fill it in.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (onTestConnection != null) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        enabled = token.isNotBlank() && !testing,
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                val result = onTestConnection(token)
                                testResult = result
                                // One car on the account: fill the VIN in rather
                                // than making them copy it across by hand.
                                if (result is VehicleCheckResult.Reachable &&
                                    result.vehicles.size == 1 && vin.isBlank()
                                ) {
                                    vin = result.vehicles.single().vin
                                }
                                testing = false
                            }
                        },
                    ) { Text(if (testing) "Checking…" else "Test connection") }

                    testResult?.let { ConnectionResultText(it, onPickVin = { vin = it }) }
                }

                if (onReadCarState != null) {
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        enabled = token.isNotBlank() && vin.isNotBlank() && !readingNav,
                        onClick = {
                            readingNav = true
                            scope.launch {
                                navState = onReadCarState(token, vin)
                                navRead = true
                                readingNav = false
                            }
                        },
                    ) { Text(if (readingNav) "Reading…" else "What is my car navigating to?") }
                    if (navRead) CarNavStateText(navState)
                }

                if (onProbeNav != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Navigation command probe",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        "Sends every kind of navigation command to your car in turn and " +
                            "reads back where it actually ends up aiming — the only way to " +
                            "find out what this car really accepts. It WILL change what your " +
                            "car is navigating to, several times, so run it parked. Takes " +
                            "about a minute.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        enabled = token.isNotBlank() && vin.isNotBlank() && !probing,
                        onClick = {
                            probing = true
                            probeLines.clear()
                            scope.launch {
                                onProbeNav(token, vin) { probeLines += it }
                                probing = false
                            }
                        },
                    ) { Text(if (probing) "Probing…" else "Probe navigation commands") }

                    probeLines.forEach { NavProbeLineText(it) }
                    if (probeLines.isNotEmpty() && !probing) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(reportOf(probeLines))) }) {
                            Text("Copy report")
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    if (encryptedStorage) {
                        "Stored encrypted on this device only, and excluded from backups. " +
                            "Shunt has no server — nothing is ever sent anywhere but Tessie."
                    } else {
                        "Stored on this device only (this device's secure keystore was " +
                            "unavailable, so it is kept in app-private storage instead) and " +
                            "excluded from backups. Shunt has no server."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                TeslaWipWarning()

                if (cameraRange != null) {
                    Spacer(Modifier.height(16.dp))
                    CameraRangeSetting(cameraRange.percent, cameraRange.onChange)
                }

                if (practice != null) {
                    Spacer(Modifier.height(16.dp))
                    PracticeCamerasSetting(practice.enabled, practice.onChange)
                }

                if (diagnostics != null) {
                    Spacer(Modifier.height(16.dp))
                    DiagnosticsSection(
                        entryCount = diagnostics.entryCount,
                        onExport = diagnostics.onExport,
                        onClear = diagnostics.onClear,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(token, vin); onDismiss() },
                enabled = token.isBlank() == vin.isBlank(), // both or neither
            ) { Text("Save") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                if (currentToken.isNotBlank() || currentVin.isNotBlank()) {
                    TextButton(onClick = { onClear(); onDismiss() }) { Text("Disconnect car") }
                }
            }
        },
    )
}

/** What a read-only credential check found, for the dialog to state plainly. */
sealed interface VehicleCheckResult {
    data class Reachable(val vehicles: List<VehicleOption>) : VehicleCheckResult
    data class BadToken(val detail: String) : VehicleCheckResult
    data class Unreachable(val detail: String) : VehicleCheckResult
}

/** One vehicle the token can see. */
data class VehicleOption(val vin: String, val name: String, val awake: Boolean)

@Composable
private fun ConnectionResultText(result: VehicleCheckResult, onPickVin: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Spacer(Modifier.height(6.dp))
    when (result) {
        is VehicleCheckResult.Reachable -> {
            if (result.vehicles.isEmpty()) {
                Text(
                    "Connected, but this token can't see any vehicles.",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.error,
                )
            } else {
                Text(
                    "Connected. Found ${result.vehicles.size} vehicle" +
                        if (result.vehicles.size == 1) ":" else "s — tap one to use it:",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                )
                result.vehicles.forEach { v ->
                    TextButton(onClick = { onPickVin(v.vin) }) {
                        Text(
                            "${v.name} · ${if (v.awake) "awake" else "asleep"}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        is VehicleCheckResult.BadToken -> Text(
            "Couldn't sign in: ${result.detail}. Check the token was copied whole.",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.error,
        )
        is VehicleCheckResult.Unreachable -> Text(
            "Couldn't reach Tessie: ${result.detail}.",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.error,
        )
    }
}

/** What the car reports about its current navigation, for the diagnostic readout. */
data class CarNavState(
    val destinationName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val milesToArrival: Double?,
    val energyAtArrival: Double?,
    val batteryLevel: Int?,
    val estimatedRangeMiles: Double?,
)

/**
 * Shows what the car says it's doing. The point of this readout is to settle a
 * question no documentation answers: when Tesla's planner inserts a charging
 * stop, does the reported destination become the *supercharger* or stay the
 * *final destination*? Set a long trip in the car and read this to find out.
 */
@Composable
private fun CarNavStateText(state: CarNavState?) {
    val scheme = MaterialTheme.colorScheme
    Spacer(Modifier.height(6.dp))
    if (state == null) {
        Text(
            "Couldn't read the car's state.",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.error,
        )
        return
    }
    val lines = buildList {
        if (state.latitude != null && state.longitude != null) {
            add("Navigating to: ${state.destinationName ?: "(unnamed)"}")
            add("  at ${state.latitude}, ${state.longitude}")
            state.milesToArrival?.let { add("  ${"%.1f".format(it)} mi to arrival") }
            state.energyAtArrival?.let { add("  ${"%.0f".format(it)}% battery on arrival") }
        } else {
            add("The car isn't navigating anywhere right now.")
        }
        state.batteryLevel?.let { add("Battery: $it%") }
        state.estimatedRangeMiles?.let { add("Estimated range: ${"%.0f".format(it)} mi") }
    }
    Text(
        lines.joinToString("\n"),
        style = MaterialTheme.typography.labelSmall,
        color = scheme.onSurfaceVariant,
    )
}

/**
 * One channel's result from the navigation probe, flattened for display.
 * [landed] is the only line that matters: it means the car's own state moved to
 * the point this channel sent, which an HTTP success on its own never proves.
 */
data class NavProbeLine(
    val channel: String,
    val sent: String,
    val verdict: String,
    val landed: Boolean,
    val detail: String,
)

@Composable
private fun NavProbeLineText(line: NavProbeLine) {
    Spacer(Modifier.height(6.dp))
    Text(
        "${if (line.landed) "\u2713" else "\u00d7"} ${line.channel}",
        style = MaterialTheme.typography.labelMedium,
        color = if (line.landed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        line.verdict,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The whole run as plain text, for pasting somewhere it can be acted on. */
private fun reportOf(lines: List<NavProbeLine>): String = buildString {
    appendLine("Shunt navigation command probe")
    for (line in lines) {
        appendLine()
        appendLine("${line.channel}: ${line.verdict}")
        appendLine("  sent: ${line.sent}")
        appendLine("  response: ${line.detail}")
    }
}

/** What the diagnostics corner of the settings dialog needs. */
data class DiagnosticsUi(
    val entryCount: Int,
    val onExport: (app.shunt.app.diag.DiagnosticLog.Export) -> Unit,
    val onClear: () -> Unit,
)

/** The practice-camera switch and its current state. */
data class PracticeUi(val enabled: Boolean, val onChange: (Boolean) -> Unit)

/** How far cameras are treated as seeing, as a percentage. */
data class CameraRangeUi(val percent: Int, val onChange: (Int) -> Unit)
