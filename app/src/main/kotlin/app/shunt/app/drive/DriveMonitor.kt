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
    /**
     * Works out a fresh camera-aware plan from the vehicle's current position
     * when it has left the planned route, or null if it can't. Absent, leaving
     * the route is still detected and alerted — just not recovered from.
     */
    private val replan: (suspend (from: GeoPoint) -> DrivePlan?)? = null,
) {
    suspend fun run(plan: DrivePlan, locations: Flow<LocationUpdate>) {
        var current = plan
        var engine = newEngine(current)
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
                        is DriveSignal.OffRoute -> {
                            // Say it first and unconditionally: from here the
                            // camera avoidance is void until a new route is in
                            // force, and the driver must know that immediately
                            // rather than after a re-plan that may fail.
                            alerter.alert(Alert.OffRoute(signal.metersOffRoute, replanning = replan != null))
                            replanFrom(signal.at, current)?.let { fresh ->
                                current = fresh
                                engine = newEngine(fresh)
                            }
                        }
                        DriveSignal.BackOnRoute -> alerter.alert(Alert.BackOnRoute)
                        is DriveSignal.ReachedStop -> {
                            // Re-push what's left so the car continues from here
                            // once the driver sets off again.
                            advance(signal.remaining)
                            alerter.alert(Alert.ReachedStop(remainingStops = signal.remaining.size - 1))
                        }
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

    private fun newEngine(plan: DrivePlan) =
        DriveMonitorEngine(plan.chain, plan.cameras, config, plan.polyline, plan.stopPoints)

    /**
     * Re-plan from [from] and put the new route in force, pushing it to the
     * vehicle. Returns the new plan, or null when we could not produce one —
     * in which case the driver has already been told they're off route and is
     * additionally told that no avoidance is active.
     */
    private suspend fun replanFrom(from: GeoPoint, previous: DrivePlan): DrivePlan? {
        val doReplan = replan ?: return null
        val fresh = runCatching { doReplan(from) }.getOrNull()
        if (fresh == null) {
            alerter.alert(Alert.ReplanFailed("couldn't work out a new route from here"))
            return null
        }
        // Hand the car the new chain. A push failure is loud but doesn't discard
        // the plan: our own camera warnings still follow the new route, which is
        // the part that matters when the vehicle isn't cooperating.
        val pushed = runCatching { vehicle.pushRoute(fresh.chain) }
            .getOrElse { e -> PushResult.Failed("replan push threw: ${e.message}", retryable = true) }
        if (pushed is PushResult.Failed) {
            alerter.alert(Alert.AdvanceFailed(fresh.chain, pushed.reason, pushed.retryable))
        }
        alerter.alert(Alert.Replanned(fresh.cameras.size))
        return fresh
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
