package app.shunt.app.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.shunt.R
import kotlin.math.roundToInt

/**
 * Local, connectivity-free alerts: escalating haptics plus a notification.
 * On a 2am rural drive with no signal these still fire — that is the whole
 * point of the fallback. Messages are terse ("Camera 1,200 ft on your right")
 * because they're meant to be heard and felt, not read.
 */
class AndroidAlerter(
    private val context: Context,
    /**
     * Speaks the alerts. Optional so a test — or a caller that has no business
     * making noise — can leave it out; every alert still vibrates and notifies.
     */
    private val speech: SpokenAlerts? = null,
) : Alerter {

    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ALERTS, "Drive alerts", NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Camera approach and route-push warnings while driving." }
        manager?.createNotificationChannel(channel)
    }

    override fun alert(alert: Alert) {
        vibrate(alert.severity)
        val (id, title, body) = describe(alert)
        notify(id, title, body, alert.severity)
        // Last, and never in a way that can stop the two above from happening:
        // speech is the channel that actually reaches a driver, but it is also
        // the one that can be missing, still starting up, or muted.
        speech?.say(spoken(alert), urgent = alert.severity == Alert.Severity.URGENT)
    }

    /**
     * What to say aloud, which is not the notification text.
     *
     * Written to be heard once, at speed, with eyes on the road: the thing
     * first, then where, then the number. Anything a driver cannot act on in
     * the next few seconds is left to the notification.
     */
    private fun spoken(alert: Alert): String = when (alert) {
        is Alert.CameraApproaching -> buildString {
            append(if (alert.imminent) "Camera now" else "Camera ahead")
            when (alert.side) {
                Side.LEFT -> append(", on your left")
                Side.RIGHT -> append(", on your right")
                null -> {}
            }
            append(", ").append(spokenDistance(alert.distanceMeters))
        }
        is Alert.AdvanceFailed ->
            "Route update failed. " + if (alert.retryable) "Retrying." else "The car may stop at the waypoint."
        Alert.AimRestored -> "Waypoint sent. Back in step."
        is Alert.DestinationHandedOver -> "Destination sent. Your car takes it from here."
        is Alert.OffRoute ->
            "Off the planned route. " + if (alert.replanning) "Finding a new one." else "Camera avoidance is not active."
        is Alert.Replanned ->
            if (alert.camerasOnNewRoute == 0) "New route, camera free."
            else "New route, passing ${alert.camerasOnNewRoute} camera${plural(alert.camerasOnNewRoute)}."
        is Alert.ReplanFailed -> "Off route, and no new route. Drive as if unprotected."
        Alert.BackOnRoute -> "Back on the route."
        Alert.StoodDown -> "Shunt has stopped steering. The car is yours. Camera warnings continue."
        is Alert.ReachedStop ->
            if (alert.remainingStops > 0) "Stop reached. ${alert.remainingStops} to go." else "Stop reached. Destination next."
        is Alert.ChargeStopAhead ->
            "Your car added a charging stop at ${alert.name}. " +
                if (alert.camerasOnLeg == 0) "That leg is camera free."
                else "That leg passes ${alert.camerasOnLeg} camera${plural(alert.camerasOnLeg)}."
        is Alert.ReachedChargeStop -> "Charging stop reached."
        is Alert.ResumingToDestination ->
            if (alert.camerasOnLeg == 0) "Back on the way. Camera free."
            else "Back on the way, passing ${alert.camerasOnLeg} camera${plural(alert.camerasOnLeg)}."
        is Alert.ChargeStopUnroutable ->
            "Your car is detouring to ${alert.name} and Shunt could not route it. Drive as if unprotected."
        is Alert.ChargingUpdateFailed -> "Charging route update failed. Check the route on the car."
        Alert.Arrived -> "Arrived."
        Alert.LegBoundaryReached -> "Still working out the rest of the route. Carry on when you can."
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    /** Spoken form of a distance — "1,200 feet", not "in 1,200 ft". */
    private fun spokenDistance(meters: Double): String {
        val feet = (meters * 3.28084).roundToInt()
        val rounded = ((feet + 50) / 100) * 100
        return if (rounded <= 0) "just ahead" else "%,d feet".format(rounded)
    }

    private fun describe(alert: Alert): Triple<Int, String, String> = when (alert) {
        is Alert.CameraApproaching -> Triple(
            CAMERA_NOTIF_BASE + (alert.camera.id % 1000).toInt(),
            if (alert.imminent) "Camera ahead now" else "Camera ahead",
            buildString {
                append("Camera ${formatFeet(alert.distanceMeters)}")
                when (alert.side) {
                    Side.LEFT -> append(" on your left")
                    Side.RIGHT -> append(" on your right")
                    null -> {}
                }
            },
        )
        is Alert.DestinationHandedOver -> Triple(
            CHARGING_NOTIF,
            "Destination sent",
            "Your car has ${alert.title} and routes the last stretch itself. " +
                "Camera warnings continue.",
        )
        Alert.AimRestored -> Triple(
            FAILURE_NOTIF,
            "Waypoint sent",
            "The car has the right waypoint again.",
        )
        is Alert.AdvanceFailed -> Triple(
            FAILURE_NOTIF,
            "Route update failed",
            "Couldn't advance the next waypoint (${alert.reason}). " +
                if (alert.retryable) "Retrying." else "The car may stop at the passed waypoint.",
        )
        is Alert.OffRoute -> Triple(
            OFF_ROUTE_NOTIF,
            "Off the planned route",
            "You're ${formatFeet(alert.metersOffRoute)} off the route. " +
                if (alert.replanning) {
                    "Camera avoidance doesn't apply here — finding a new route."
                } else {
                    "Camera avoidance doesn't apply here. Rejoin the route or re-plan."
                },
        )
        is Alert.Replanned -> Triple(
            OFF_ROUTE_NOTIF,
            "New route in force",
            if (alert.camerasOnNewRoute == 0) {
                "Re-planned from here and it's camera-free."
            } else {
                "Re-planned from here. It passes ${alert.camerasOnNewRoute} camera" +
                    (if (alert.camerasOnNewRoute == 1) "." else "s.")
            },
        )
        is Alert.ReplanFailed -> Triple(
            OFF_ROUTE_NOTIF,
            "No camera avoidance active",
            "Off the route and couldn't work out a new one (${alert.reason}). " +
                "Cameras ahead are unknown — drive as if unprotected.",
        )
        Alert.BackOnRoute -> Triple(
            OFF_ROUTE_NOTIF,
            "Back on the route",
            "Camera avoidance applies again.",
        )
        Alert.StoodDown -> Triple(
            OFF_ROUTE_NOTIF,
            "Shunt has stopped steering — the car is yours",
            "The route and the road kept disagreeing, so Shunt is no longer " +
                "sending anything to the car. Navigate it however you like. " +
                "Camera warnings carry on.",
        )
        is Alert.ReachedStop -> Triple(
            ARRIVED_NOTIF,
            "Stop reached",
            if (alert.remainingStops > 0) {
                "${alert.remainingStops} more stop${if (alert.remainingStops == 1) "" else "s"} " +
                    "before your destination."
            } else {
                "Next up: your destination."
            },
        )
        is Alert.ChargeStopAhead -> Triple(
            CHARGING_NOTIF,
            "Your car added a charging stop",
            "It's going to ${alert.name} first. Shunt has planned that leg too — " +
                if (alert.camerasOnLeg == 0) {
                    "it's camera-free."
                } else {
                    "it passes ${alert.camerasOnLeg} camera${if (alert.camerasOnLeg == 1) "" else "s"}."
                },
        )
        is Alert.ReachedChargeStop -> Triple(
            CHARGING_NOTIF,
            "Charging stop reached",
            "You're at ${alert.name}. Shunt keeps watching and will route the rest " +
                "of the trip once your car plans it.",
        )
        is Alert.ResumingToDestination -> Triple(
            CHARGING_NOTIF,
            "Back on the way",
            if (alert.camerasOnLeg == 0) {
                "Routed on to your destination — camera-free."
            } else {
                "Routed on to your destination. It passes ${alert.camerasOnLeg} " +
                    "camera${if (alert.camerasOnLeg == 1) "" else "s"}."
            },
        )
        is Alert.ChargeStopUnroutable -> Triple(
            CHARGING_NOTIF,
            "No camera avoidance to the charger",
            "Your car is detouring to ${alert.name} and Shunt couldn't route that " +
                "leg. It's driving there its own way — cameras on the way are " +
                "unknown. Drive as if unprotected.",
        )
        is Alert.ChargingUpdateFailed -> Triple(
            CHARGING_NOTIF,
            "Charging route update failed",
            "Shunt could not verify or restore the car's destination (${alert.reason}). " +
                "Check the route on the car before continuing.",
        )
        Alert.Arrived -> Triple(ARRIVED_NOTIF, "Arrived", "You've reached your destination.")
        Alert.LegBoundaryReached -> Triple(
            ARRIVED_NOTIF,
            "End of the planned stretch",
            "The rest of the route is still being worked out.",
        )
    }

    private fun vibrate(severity: Alert.Severity) {
        val v = vibrator ?: return
        val pattern = when (severity) {
            Alert.Severity.INFO -> longArrayOf(0, 120)
            Alert.Severity.WARNING -> longArrayOf(0, 200, 120, 200)
            Alert.Severity.URGENT -> longArrayOf(0, 400, 150, 400, 150, 400)
        }
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun notify(id: Int, title: String, body: String, severity: Alert.Severity) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_drive_monitor)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(
                if (severity == Alert.Severity.URGENT) NotificationCompat.PRIORITY_MAX
                else NotificationCompat.PRIORITY_HIGH,
            )
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun formatFeet(meters: Double): String {
        val feet = (meters * 3.28084).roundToInt()
        val rounded = ((feet + 50) / 100) * 100
        return if (rounded <= 0) "just ahead" else "in ${"%,d".format(rounded)} ft"
    }

    private companion object {
        const val CHANNEL_ALERTS = "drive_alerts"
        const val CAMERA_NOTIF_BASE = 2000
        const val FAILURE_NOTIF = 1001
        const val ARRIVED_NOTIF = 1002

        /** Route-adherence updates share an id so each replaces the last. */
        const val OFF_ROUTE_NOTIF = 1003

        /** Charging-leg updates likewise replace each other. */
        const val CHARGING_NOTIF = 1004
    }
}
