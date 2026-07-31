package app.shunt.app.drive

import app.shunt.app.plan.Destination
import app.shunt.app.plan.DrivePlan
import app.shunt.core.GeoPoint
import app.shunt.solver.geo.haversineMeters
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
    /**
     * Watches for a charging stop the *car* inserts on its own and re-plans the
     * trip as legs around it. Null leaves the behaviour exactly as before: one
     * route, one destination, no reads of the vehicle's state.
     */
    private val charging: ChargeStopCoordinator? = null,
    /**
     * Called whenever the route in force changes, so the screen can draw what
     * is actually being driven. Without it a re-plan is invisible: the monitor
     * follows the new line while the map still shows the abandoned one.
     */
    private val onPlanChanged: (DrivePlan) -> Unit = {},
) {
    /**
     * Whether the car is being steered pin by pin (see [DrivePlan.steerByWaypoints]).
     * Set from the plan this monitor was started with and carried across every
     * route that replaces it: the car's capabilities don't change mid-trip.
     */
    private var steering = false

    suspend fun run(plan: DrivePlan, locations: Flow<LocationUpdate>) {
        steering = plan.steerByWaypoints
        var current = plan
        var engine = newEngine(current)
        val finalDestination = plan.destination
        onStatus(DriveStatus.Driving(finalDestination.title))
        var arrived = false
        var previous: LocationUpdate? = null
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
                            // On a charging leg this is the charger, not the
                            // trip's end: the drive continues after charging.
                            val stop = charging?.chargeStopUnderWay()
                            if (stop == null) {
                                arrived = true
                                alerter.alert(Alert.Arrived)
                                onStatus(DriveStatus.Arrived)
                            } else {
                                charging.onReachedChargeStop()
                                alerter.alert(Alert.ReachedChargeStop(stop.name))
                                onStatus(DriveStatus.Driving(finalDestination.title, chargingVia = stop.name))
                            }
                        }
                    }
                }
                if (arrived || charging == null) {
                    previous = update
                    return@collect
                }
                val due = charging.isCheckDue(
                    moving = isMoving(previous, update),
                    metersToNextWaypoint = engine.metersToNextWaypoint(update.point),
                    metersToNearestCamera = engine.metersToNearestCamera(update.point),
                    offRoute = engine.isOffRoute,
                )
                previous = update
                if (!due) return@collect

                val change = charging.check(
                    from = update.point,
                    destination = finalDestination,
                    remainingStops = engine.remainingStops(),
                    steeringChain = engine.remainingChain(),
                )
                applyLeg(change, finalDestination)?.let { fresh ->
                    current = fresh
                    engine = newEngine(fresh)
                }
            }
        } finally {
            if (!arrived) onStatus(DriveStatus.Idle)
        }
    }

    /**
     * Put a leg change into force: alert, push it to the car, and hand back the
     * plan the monitor should follow from now on (null to keep the current one).
     */
    private suspend fun applyLeg(change: LegChange, finalDestination: Destination): DrivePlan? = when (change) {
        LegChange.None -> null
        is LegChange.ToChargeStop -> {
            val plan = inForce(change.plan)
            alerter.alert(Alert.ChargeStopAhead(change.stop.name, plan.cameras.size))
            onStatus(DriveStatus.Driving(finalDestination.title, chargingVia = change.stop.name))
            push(plan.chain)
            onPlanChanged(plan)
            plan
        }
        is LegChange.ToDestination -> {
            val plan = inForce(change.plan)
            alerter.alert(Alert.ResumingToDestination(plan.cameras.size))
            onStatus(DriveStatus.Driving(finalDestination.title))
            push(plan.chain)
            onPlanChanged(plan)
            plan
        }
        is LegChange.Unroutable -> {
            // The car is going there regardless; the only honest thing left is
            // to say that nothing is protecting this leg.
            alerter.alert(Alert.ChargeStopUnroutable(change.stop.name))
            null
        }
        is LegChange.VehicleUpdateFailed -> {
            alerter.alert(Alert.ChargingUpdateFailed(change.reason, change.retryable))
            null
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
        val planned = runCatching { doReplan(from) }.getOrNull()
        if (planned == null) {
            alerter.alert(Alert.ReplanFailed("couldn't work out a new route from here"))
            return null
        }
        val fresh = inForce(planned)
        // Only bother the car when the new route actually needs steering.
        //
        // A route with no shaping pins is one the car would drive anyway, so
        // pushing it says nothing it doesn't already know — on a single-
        // destination car it re-sends the same destination, which interrupts
        // the navigation on screen for no gain. The exception is coming *off* a
        // pinned route: the car is still aimed at a pin that no longer exists,
        // so the destination has to be restored.
        //
        // Steering pin by pin is the exception to the exception: the car is
        // aimed at a point on the route we just left, so the new head has to go
        // out even when the new route needs no shaping of its own.
        val needsSteering = steering || fresh.chain.size > 1
        val holdsStalePin = previous.chain.size > 1
        if (needsSteering || holdsStalePin) push(fresh.chain)

        alerter.alert(Alert.Replanned(fresh.cameras.size))
        onPlanChanged(fresh)
        return fresh
    }

    /**
     * Whether the car is under way. A Tesla doesn't work out its charging stops
     * until it is actually put into drive, so asking a parked car what it plans
     * to do reliably answers "nothing" — the check has to wait for movement.
     * Falls back to distance covered when a fix carries no speed.
     */
    private fun isMoving(previous: LocationUpdate?, update: LocationUpdate): Boolean {
        update.speedMetersPerSec?.let { return it >= MOVING_METERS_PER_SEC }
        val from = previous?.point ?: return false
        return haversineMeters(from, update.point) >= MOVING_METERS_BETWEEN_FIXES
    }

    /**
     * What to actually send the car for a chain that has [chain] left in it.
     *
     * When steering pin by pin, the car is only ever given the *next* point:
     * it accepts one destination, so handing it the whole tail means handing it
     * the far end and losing the shape entirely. Sending only the head is what
     * makes the avoidance reach the car — and the pin moves forward as each one
     * is approached, so the car is walked along the route.
     */
    private fun aim(chain: List<GeoPoint>): List<GeoPoint> =
        if (steering && chain.isNotEmpty()) listOf(chain.first()) else chain

    /** Stamp the steering mode on a route that replaces the current one. */
    private fun inForce(plan: DrivePlan): DrivePlan =
        if (plan.steerByWaypoints == steering) plan else plan.copy(steerByWaypoints = steering)

    /** Send a whole route to the car, alerting loudly if it doesn't land. */
    private suspend fun push(chain: List<GeoPoint>) {
        val sending = aim(chain)
        val pushed = runCatching { vehicle.pushRoute(sending) }
            .getOrElse { e -> PushResult.Failed("push threw: ${e.message}", retryable = true) }
        if (pushed is PushResult.Failed) {
            alerter.alert(Alert.AdvanceFailed(sending, pushed.reason, pushed.retryable))
        }
    }

    private suspend fun advance(remaining: List<GeoPoint>) {
        val sending = aim(remaining)
        val result = runCatching { vehicle.advanceTo(sending) }
            .getOrElse { e -> PushResult.Failed("advance threw: ${e.message}", retryable = true) }
        if (result is PushResult.Failed) {
            // Loud: the car may still stop at the waypoint we failed to drop.
            alerter.alert(Alert.AdvanceFailed(sending, result.reason, result.retryable))
        }
    }

    private companion object {
        /** At or above this the car is under way, so its trip planner has run. */
        const val MOVING_METERS_PER_SEC = 2.0

        /** No speed in the fix: this much ground covered between fixes means the same. */
        const val MOVING_METERS_BETWEEN_FIXES = 15.0
    }
}
