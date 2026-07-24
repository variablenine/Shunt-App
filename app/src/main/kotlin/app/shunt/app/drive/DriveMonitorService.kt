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

        val monitor = DriveMonitor(
            vehicle = container.vehicleNavClient,
            alerter = AndroidAlerter(this),
            onStatus = { status ->
                container.driveStatus.value = status
                if (status is DriveStatus.Arrived) stopSelf()
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
        scope.cancel()
        val container = (application as ShuntApplication).container
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
