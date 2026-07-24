package app.shunt.app.drive

import app.shunt.app.plan.DrivePlan
import app.shunt.core.GeoPoint
import app.shunt.tesla.PushResult
import app.shunt.tesla.VehicleNavClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile

/**
 * Drives a single trip: feeds GPS fixes through [DriveMonitorEngine], executes
 * its decisions against the vehicle, and routes every failure to a loud local
 * [Alerter]. The initial route push already happened at Go (in the plan
 * screen); this owns the in-drive advanceTo calls and the alerting.
 *
 * Fully exercisable with FakeVehicleNavClient and a fake Alerter over a
 * scripted location [Flow] — including the failure paths that cannot be driven
 * around (advanceTo failing while approaching an unavoidable camera).
 */
class DriveMonitor(
    private val vehicle: VehicleNavClient,
    private val alerter: Alerter,
    private val config: DriveMonitorConfig = DriveMonitorConfig(),
    private val onStatus: (DriveStatus) -> Unit = {},
) {
    suspend fun run(plan: DrivePlan, locations: Flow<LocationUpdate>) {
        val engine = DriveMonitorEngine(plan.chain, plan.cameras, config)
        onStatus(DriveStatus.Driving(plan.destination.title))
        var arrived = false
        try {
            locations.takeWhile { !arrived }.collect { update ->
                for (signal in engine.onLocation(update)) {
                    when (signal) {
                        is DriveSignal.ApproachingWaypoint -> advance(signal.remaining)
                        is DriveSignal.ApproachingCamera -> alerter.alert(
                            Alert.CameraApproaching(
                                signal.camera, signal.distanceMeters, signal.side, signal.imminent,
                            ),
                        )
                        DriveSignal.Arrived -> {
                            arrived = true
                            alerter.alert(Alert.Arrived)
                            onStatus(DriveStatus.Arrived)
                        }
                    }
                }
            }
        } finally {
            if (!arrived) onStatus(DriveStatus.Idle)
        }
    }

    private suspend fun advance(remaining: List<GeoPoint>) {
        val result = runCatching { vehicle.advanceTo(remaining) }
            .getOrElse { e -> PushResult.Failed("advance threw: ${e.message}", retryable = true) }
        if (result is PushResult.Failed) {
            // Loud: the car may still stop at the waypoint we failed to drop.
            alerter.alert(Alert.AdvanceFailed(remaining, result.reason, result.retryable))
        }
    }
}
