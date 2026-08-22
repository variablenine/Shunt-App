package app.shunt.app.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.shunt.R
import app.shunt.app.ShuntApplication
import app.shunt.app.diag.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the drive monitor. It must be started from the
 * Go tap while the activity is visible: a foreground service that needs a
 * while-in-use permission (location) cannot be started from the background,
 * even with Companion Device Manager exemptions. Started on Go, stopped on
 * arrival or cancel — nothing runs when the user isn't driving.
 */
class DriveMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var monitorJob: Job? = null

    /**
     * Owned by the service, not the alerter, because a text-to-speech engine is
     * a bound service connection: it has to be shut down when the drive ends or
     * it outlives the thing that needed it. Created on the first drive and
     * released in [onDestroy].
     */
    private var speech: SpokenAlerts? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createStatusChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val container = (application as ShuntApplication).container
        val plan = container.activeDrivePlan
        // startForegroundService obligates a startForeground call within ~5s,
        // so promote first — even on the (defensive) missing-plan path.
        startForegroundStatus(plan?.destination?.title ?: "your trip")
        if (plan == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        container.liveDrivePlan.value = plan
        container.diagnostics.record(
            DiagnosticLog.Kind.DRIVE,
            "drive started: ${plan.chain.size} waypoints, ${plan.cameras.size} cameras to warn about, " +
                if (plan.steerByWaypoints) "steering pin by pin" else "car holds the destination",
            locations = plan.chain.map { it.lat to it.lon },
        )
        if (speech == null) speech = SpokenAlerts(this)
        val monitor = DriveMonitor(
            vehicle = container.vehicleNavClient,
            alerter = AndroidAlerter(this, speech),
            onActivity = {
                container.driveActivity.value = it
                // The activity line is exactly the running commentary a bug
                // report needs, and it already exists — this just keeps it.
                container.diagnostics.record(DiagnosticLog.Kind.DRIVE, describe(it))
            },
            onStatus = { status ->
                container.driveStatus.value = status
                if (status is DriveStatus.Arrived) stopSelf()
            },
            // For the map's follow camera: it frames the driver and this point
            // together, so the stretch being driven is the stretch on screen.
            onAim = { container.aimedAt.value = it },
            // Leaving the route voids the camera avoidance, so recover it:
            // re-plan on-device from where the car actually is.
            replan = { from, heading, blocked ->
                container.replanFrom(from, plan.destination, heading, blocked)
            },
            // The car may add a Supercharger to the trip on its own; if it
            // does, route that leg too rather than letting it drive there
            // unvetted. Null on cars that took the full shaped chain.
            charging = container.chargeStopCoordinator(plan),
            // Republish the route in force so the map draws what is being
            // driven, not the line that was abandoned.
            extensions = container.legExtensions,
            onPlanChanged = {
                container.liveDrivePlan.value = it
                container.diagnostics.record(
                    DiagnosticLog.Kind.DRIVE,
                    "route in force replaced: ${it.chain.size} waypoints, ${it.cameras.size} cameras",
                    locations = it.chain.map { p -> p.lat to p.lon },
                )
            },
        )
        monitorJob?.cancel()
        monitorJob = scope.launch {
            runCatching { monitor.run(plan, locationUpdates(this@DriveMonitorService)) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        speech?.shutdown()
        speech = null
        scope.cancel()
        val container = (application as ShuntApplication).container
        container.driveActivity.value = DriveActivity.Watching
        if (container.driveStatus.value !is DriveStatus.Arrived) {
            container.driveStatus.value = DriveStatus.Idle
        }
        super.onDestroy()
    }

    private fun startForegroundStatus(destinationTitle: String) {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_drive_monitor)
            .setContentTitle("Shunt — monitoring drive")
            .setContentText("Guiding you to $destinationTitle")
            // Expanded, this is on screen for the whole drive — the one place
            // the caveat is guaranteed to be visible while actually driving.
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Guiding you to $destinationTitle.\n\n" +
                        "Tesla/FSD support is a work in progress — the route sent to the car " +
                        "is unproven. Stay attentive and ready to take over.",
                ),
            )
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, STATUS_NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(STATUS_NOTIF_ID, notification)
        }
    }

    private fun createStatusChannel() {
        val channel = NotificationChannel(
            CHANNEL_STATUS, "Drive monitor", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Ongoing while a drive is being monitored." }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_STATUS = "drive_status"
        private const val STATUS_NOTIF_ID = 42
        private const val ACTION_STOP = "app.shunt.action.STOP_DRIVE"

        /** Start from the foreground (the Go tap), with the plan already in the container. */
        fun start(context: Context) {
            val intent = Intent(context, DriveMonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DriveMonitorService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}

/** One short line per activity, for the log. */
private fun describe(activity: DriveActivity): String = when (activity) {
    DriveActivity.Watching -> "watching for cameras"
    is DriveActivity.SendingWaypoint -> "sending waypoint ${activity.number} of ${activity.total}"
    is DriveActivity.RetryingWaypoint ->
        "retrying waypoint ${activity.number} of ${activity.total} — the car could not be reached"
    DriveActivity.CheckingCharging -> "asking the car about charging"
    DriveActivity.Replanning -> "re-planning"
    DriveActivity.StoodDown -> "stood down — no longer commanding the car"
}
