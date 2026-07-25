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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
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
     * Verifies the token by listing the account's vehicles. Read-only: it sends
     * no command, so it can't make the car do anything.
     */
    onTestConnection: (suspend (String) -> VehicleCheckResult)? = null,
) {
    var token by remember { mutableStateOf(currentToken) }
    var vin by remember { mutableStateOf(currentVin) }
    var revealToken by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<VehicleCheckResult?>(null) }
    val scope = rememberCoroutineScope()

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
