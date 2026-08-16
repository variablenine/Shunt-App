package app.shunt.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import android.view.WindowManager
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
import app.shunt.app.diag.DiagnosticExport
import app.shunt.app.ui.DiagnosticsUi
import app.shunt.app.ui.CameraRangeUi
import app.shunt.app.ui.PracticeUi
import app.shunt.app.ui.VehicleSettingsUi
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
                val driveActivity by container.driveActivity.collectAsStateWithLifecycle()

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

                // Follow the route the monitor is actually driving: it replaces
                // it on off-route recovery and on charging legs, and without
                // this the map keeps showing the abandoned one.
                val livePlan by container.liveDrivePlan.collectAsStateWithLifecycle()
                LaunchedEffect(livePlan) {
                    livePlan?.let { vm.onRouteReplanned(it) }
                }

                // Later legs of a long trip, drawn on the map as they land so
                // the line grows to the destination — from a standstill as
                // readily as while driving.
                val laterLegs by container.laterLegs.collectAsStateWithLifecycle()
                val planningLaterLegs by container.planningLaterLegs.collectAsStateWithLifecycle()
                val trimmedLead by container.trimmedLeadPolyline.collectAsStateWithLifecycle()
                val aimedAt by container.aimedAt.collectAsStateWithLifecycle()
                val trimmedLeadPins by container.trimmedLeadWaypoints.collectAsStateWithLifecycle()

                // Keep the screen on **while actually navigating**, and only
                // then.
                //
                // Reported from real use: the screen times out and the app then
                // fails *silently* — no alert, nothing on screen, which is the
                // dangerous part rather than the inconvenience. A navigation app
                // is looked at in glances, so the phone's idea of "idle" is
                // wrong about it by construction: nobody taps the screen while
                // driving.
                //
                // Scoped to a running drive rather than to the app being open,
                // because the rest of the time Shunt is a map somebody is
                // browsing and holding the screen awake for that is just a flat
                // battery. The flag rather than a wake lock: the system scopes
                // it to this window, so it cannot outlive the app or leak, and
                // DisposableEffect clears it the moment the drive ends.
                //
                // **Not the whole answer.** The monitor is a foreground service
                // and should survive the screen going off regardless; whatever
                // actually breaks when it does is still unexplained, and keeping
                // the screen lit hides it. See docs/verification.md C6.
                val navigating = state.phase is app.shunt.app.plan.Phase.Driving ||
                    state.phase is app.shunt.app.plan.Phase.Pushing
                DisposableEffect(navigating) {
                    if (navigating) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }

                LaunchedEffect(driveStatus) {
                    if (driveStatus is DriveStatus.Arrived) {
                        vm.onArrived()
                        container.driveStatus.value = DriveStatus.Idle
                    }
                }

                val credentials by container.vehicleCredentials.credentials.collectAsStateWithLifecycle()
                // Mirrored into composition so the banner and the switch move
                // together; the container holds the persisted truth.
                var practiceOn by remember { mutableStateOf(container.practiceCameras) }
                var cameraRange by remember { mutableStateOf(container.cameraRangePercent) }

                PlanScreen(
                    state = state.copy(
                        laterLegs = laterLegs,
                        planningLaterLegs = planningLaterLegs,
                        trimmedLeadPolyline = trimmedLead,
                        aimedAt = aimedAt,
                        trimmedLeadWaypoints = trimmedLeadPins,
                    ),
                    cameraViewportFetcher = container.viewportCameras,
                    chargingVia = (driveStatus as? DriveStatus.Driving)?.chargingVia,
                    driveActivity = driveActivity,
                    vehicleSettings = VehicleSettingsUi(
                        token = credentials.token,
                        vin = credentials.vin,
                        encryptedStorage = container.vehicleCredentials.usingEncryptedStorage,
                        onSave = container.vehicleCredentials::save,
                        onClear = container.vehicleCredentials::clear,
                        onTestConnection = { token -> container.checkVehicleToken(token) },
                        onReadCarState = { t, v -> container.readCarNavState(t, v) },
                        onProbeNav = { t, v, onLine -> container.probeNavCommands(t, v, onLine) },
                        diagnostics = DiagnosticsUi(
                            entryCount = container.diagnostics.entries().size,
                            onExport = { options ->
                                // The user is the transport: this opens a share
                                // sheet with a file. Nothing is sent by Shunt.
                                DiagnosticExport.shareIntent(this@MainActivity, container.diagnostics, options)
                                    ?.let { startActivity(Intent.createChooser(it, "Send diagnostic log")) }
                            },
                            onClear = { container.diagnostics.clear() },
                        ),
                        cameraRange = CameraRangeUi(
                            percent = cameraRange,
                            onChange = { cameraRange = it; container.cameraRangePercent = it },
                        ),
                        practice = PracticeUi(
                            enabled = practiceOn,
                            onChange = { practiceOn = it; container.practiceCameras = it },
                        ),
                    ),
                    actions = PlanActions(
                        onQueryChange = vm::onQueryChange,
                        onSuggestionSelected = vm::onSuggestionSelected,
                        onSuggestionAddedAsStop = vm::onSuggestionAddedAsStop,
                        onRemoveStop = vm::onRemoveStop,
                        onFavoriteSelected = vm::onFavoriteSelected,
                        onGo = vm::onGo,
                        onSelectRoute = vm::onSelectRoute,
                        onDownloadTile = vm::onDownloadTile,
                        onRetryPush = vm::onRetryPush,
                        onDismiss = vm::onDismissResult,
                        onSaveHome = { vm.onSaveFavorite(FavoriteSlot.HOME, it) },
                        onSaveWork = { vm.onSaveFavorite(FavoriteSlot.WORK, it) },
                        onMapLongPress = vm::onMapLongPress,
                        onChargeFirst = vm::onChargeFirst,
                        onChargeAlternative = vm::onChargeAlternative,
                        onRecentSelected = vm::onRecentSelected,
                        onChargerPicked = vm::onChargerPicked,
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
