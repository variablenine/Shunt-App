package app.shunt.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shunt.app.di.AppContainer
import app.shunt.app.drive.DriveMonitorService
import app.shunt.app.drive.DriveStatus
import app.shunt.app.plan.FavoriteSlot
import app.shunt.app.plan.Phase
import app.shunt.app.plan.PlanViewModel
import app.shunt.app.ui.CrashScreen
import app.shunt.app.ui.PlanActions
import app.shunt.app.ui.PlanScreen
import app.shunt.app.ui.theme.ShuntTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ShuntApplication).container

        val diagnostics = getSharedPreferences(ShuntApplication.DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)

        setContent {
            ShuntTheme {
                // If the previous run crashed, show the stack trace first so it
                // can be reported, rather than silently starting up again.
                var lastCrash by remember {
                    mutableStateOf(diagnostics.getString(ShuntApplication.KEY_LAST_CRASH, null))
                }
                if (lastCrash != null) {
                    CrashScreen(details = lastCrash!!) {
                        diagnostics.edit().remove(ShuntApplication.KEY_LAST_CRASH).apply()
                        lastCrash = null
                    }
                    return@ShuntTheme
                }

                val context = LocalContext.current
                val vm: PlanViewModel = viewModel(factory = container.planViewModelFactory())
                val state by vm.state.collectAsStateWithLifecycle()
                val driveStatus by container.driveStatus.collectAsStateWithLifecycle()
                val storedHereKey by container.settings.hereApiKey.collectAsStateWithLifecycle()

                // Refresh camera data on open; schedule no background work.
                LaunchedEffect(Unit) { vm.onOpen() }

                // Ask for location (and notifications on Android 13+) up front,
                // so routing uses the real current location and drive alerts can
                // fire — instead of silently falling back to the Home favorite.
                // Only prompts for what isn't already granted.
                val startupPermissionLauncher =
                    rememberLauncherForActivityResult(RequestMultiplePermissions()) { /* re-read on use */ }
                LaunchedEffect(Unit) {
                    val missing = requiredDrivePermissions().filterNot { hasPermission(context, it) }
                    if (missing.isNotEmpty()) startupPermissionLauncher.launch(missing.toTypedArray())
                }

                // Start the foreground drive-monitor when the plan enters the
                // driving phase (from the Go tap, so we're in the foreground);
                // request while-in-use location if it isn't granted yet.
                val isDriving = state.phase is Phase.Driving
                val permissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { grants ->
                    val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    if (fineGranted) DriveMonitorService.start(context) else vm.onStopDrive()
                }

                LaunchedEffect(isDriving) {
                    if (isDriving) {
                        container.activeDrivePlan = (state.phase as Phase.Driving).plan
                        if (hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                            DriveMonitorService.start(context)
                        } else {
                            permissionLauncher.launch(requiredDrivePermissions().toTypedArray())
                        }
                    }
                }
                // Stop the service when leaving the driving phase (cancel or arrival).
                DisposableEffect(isDriving) {
                    onDispose { if (isDriving) DriveMonitorService.stop(context) }
                }

                LaunchedEffect(driveStatus) {
                    if (driveStatus is DriveStatus.Arrived) {
                        vm.onArrived()
                        container.driveStatus.value = DriveStatus.Idle
                    }
                }

                // storedHereKey is read so recomposition re-evaluates the flag.
                val hereKeyMissing = remember(storedHereKey) { container.hereKeyMissing() }

                PlanScreen(
                    state = state,
                    hereKeyMissing = hereKeyMissing,
                    hereApiKey = storedHereKey,
                    actions = PlanActions(
                        onQueryChange = vm::onQueryChange,
                        onSuggestionSelected = vm::onSuggestionSelected,
                        onFavoriteSelected = vm::onFavoriteSelected,
                        onGo = vm::onGo,
                        onRetryPush = vm::onRetryPush,
                        onDismiss = vm::onDismissResult,
                        onSaveHome = { vm.onSaveFavorite(FavoriteSlot.HOME, it) },
                        onSaveWork = { vm.onSaveFavorite(FavoriteSlot.WORK, it) },
                        onSaveHereKey = container.settings::setHereApiKey,
                    ),
                )
            }
        }
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Location is required to monitor; notifications are nice-to-have (haptics work regardless). */
private fun requiredDrivePermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
