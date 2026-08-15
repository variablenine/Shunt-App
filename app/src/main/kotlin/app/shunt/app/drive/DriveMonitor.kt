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
/**
 * Numbers the monitor is held to, kept public so tests can assert against the
 * same values rather than a copy that can drift out of step with them.
 */
object DriveMonitorBounds {
    /**
     * How much of the refused road to block, and how finely.
     *
     * Long enough to push the re-plan past whatever the driver would not drive,
     * short enough that it is a road being blocked and not the trip. The spacing
     * sits under twice BrouterRouter's blocking radius so the circles overlap —
     * a gap is a thread the router will use, which lands the car back on the
     * road this exists to stop offering.
     */
    const val ABANDONED_STRETCH_METERS = 4_000.0
    const val ABANDONED_SPACING_METERS = 100.0
}

class DriveMonitor(
    private val vehicle: VehicleNavClient,
    private val alerter: Alerter,
    private val config: DriveMonitorConfig = DriveMonitorConfig(),
    private val onStatus: (DriveStatus) -> Unit = {},
    /**
     * What Shunt is doing with the car at this instant, for the driver to see.
     * Every one of these things already happened invisibly; see [DriveActivity].
     */
    private val onActivity: (DriveActivity) -> Unit = {},
    /**
     * Works out a fresh camera-aware plan from the vehicle's current position
     * when it has left the planned route, or null if it can't. Absent, leaving
     * the route is still detected and alerted — just not recovered from.
     */
    private val replan: (
        suspend (from: GeoPoint, headingDegrees: Double?, blocked: List<GeoPoint>) -> DrivePlan?
    )? = null,
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
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * Whether the car is being steered pin by pin (see [DrivePlan.steerByWaypoints]).
     * Set from the plan this monitor was started with and carried across every
     * route that replaces it: the car's capabilities don't change mid-trip.
     */
    private var steering = false

    /** Shaping pins in the plan in force, for "waypoint 3 of 12". */
    private var totalPins = 0

    suspend fun run(plan: DrivePlan, locations: Flow<LocationUpdate>) {
        steering = plan.steerByWaypoints
        totalPins = (plan.chain.size - 1).coerceAtLeast(0)
        onActivity(DriveActivity.Watching)
        var current = plan
        var engine = newEngine(current)
        val finalDestination = plan.destination
        onStatus(DriveStatus.Driving(finalDestination.title))
        var arrived = false
        var previous: LocationUpdate? = null
        try {
            locations.takeWhile { !arrived }.collect { update ->
                // Worked out before `previous` moves on, so it compares this fix
                // with the one before it rather than with itself.
                val heading = headingOf(previous, update)
                for (signal in engine.onLocation(update)) {
                    when (signal) {
                        is DriveSignal.ApproachingWaypoint -> advance(signal.remaining)
                        is DriveSignal.ApproachingCamera -> alerter.alert(
                            Alert.CameraApproaching(
                                signal.camera, signal.distanceMeters, signal.side, signal.imminent,
                            ),
                        )
                        is DriveSignal.OffRoute -> {
                            // Whether this is the one where Shunt gives up has
                            // to be decided before the alert goes out, not
                            // after: telling the driver "re-planning" and then
                            // immediately handing them the car is the sort of
                            // contradiction that gets read as a malfunction.
                            val givingUp = !stoodDown && replan != null && tooManyReplans()
                            // Say it first and unconditionally: from here the
                            // camera avoidance is void until a new route is in
                            // force, and the driver must know that immediately
                            // rather than after a re-plan that may fail.
                            alerter.alert(
                                Alert.OffRoute(
                                    signal.metersOffRoute,
                                    replanning = replan != null && !stoodDown && !givingUp,
                                ),
                            )
                            if (givingUp) standDown()
                            // Re-plan from the direction of travel, not just the
                            // position. Without it the answer can be "turn round"
                            // — which on a road you've just committed to is not
                            // an answer at all.
                            replanFrom(signal.at, current, heading)?.let { fresh ->
                                current = fresh
                                engine = newEngine(fresh, engine)
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
                // Standing down has to cover the charging probe too. It re-asserts
                // the destination to ask its question, which is exactly the kind
                // of push the driver was fighting.
                if (arrived || charging == null || stoodDown) {
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

                onActivity(DriveActivity.CheckingCharging)
                val change = charging.check(
                    from = update.point,
                    destination = finalDestination,
                    remainingStops = engine.remainingStops(),
                    steeringChain = engine.remainingChain(),
                    headingDegrees = heading,
                )
                onActivity(if (stoodDown) DriveActivity.StoodDown else DriveActivity.Watching)
                val afterCharging = applyLeg(change, finalDestination)
                if (afterCharging != null) {
                    current = afterCharging
                    // Deliberately NOT inheriting what has already been
                    // announced. A charging leg is a different stretch of road,
                    // often an hour later, and a camera met again there is a new
                    // encounter — under-warning is the worse mistake here.
                    engine = newEngine(afterCharging)
                } else {
                    reaim(engine.remainingChain())
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

    /**
     * The engine for a route that replaces the one in force.
     *
     * Pass [previous] where the replacement is the *same journey re-planned* —
     * off-route recovery — and what has already been announced carries over.
     * Without that hand-off every camera still in range is warned about again on
     * each re-plan, which during the closed-road loop became alerts that would
     * not stop.
     *
     * Leave it out where the replacement is a genuinely new stretch of road, so
     * a camera met again there is announced again.
     */
    private fun newEngine(plan: DrivePlan, previous: DriveMonitorEngine? = null) =
        DriveMonitorEngine(
            plan.chain,
            plan.cameras,
            config,
            plan.polyline,
            plan.stopPoints,
            alreadyWarned = previous?.warnedTiers.orEmpty(),
        )

    /**
     * Re-plan from [from] and put the new route in force, pushing it to the
     * vehicle. Returns the new plan, or null when we could not produce one —
     * in which case the driver has already been told they're off route and is
     * additionally told that no avoidance is active.
     */
    private suspend fun replanFrom(
        from: GeoPoint,
        previous: DrivePlan,
        headingDegrees: Double?,
    ): DrivePlan? {
        if (stoodDown) return null
        val doReplan = replan ?: return null
        onActivity(DriveActivity.Replanning)
        // Keep the new route off the stretch just abandoned. Shunt cannot see a
        // closure — only that the driver left here — so what gets blocked is the
        // road immediately ahead of that point, for this plan and no longer.
        // Blocking the whole remaining route would block the trip instead.
        val refused = app.shunt.solver.geo.stretchAhead(
            polyline = previous.polyline,
            from = from,
            lengthMeters = ABANDONED_STRETCH_METERS,
            spacingMeters = ABANDONED_SPACING_METERS,
        )
        val planned = runCatching { doReplan(from, headingDegrees, refused) }.getOrNull()
        if (planned == null) {
            alerter.alert(Alert.ReplanFailed("couldn't work out a new route from here"))
            onActivity(if (stoodDown) DriveActivity.StoodDown else DriveActivity.Watching)
            return null
        }
        val fresh = inForce(planned)
        // The new plan replaces the old one, pins and all.
        totalPins = (fresh.chain.size - 1).coerceAtLeast(0)
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
        onActivity(if (stoodDown) DriveActivity.StoodDown else DriveActivity.Watching)
        return fresh
    }

    /**
     * True once Shunt has given the car back to the driver. One-way: nothing in
     * a drive re-earns the right to start commanding the car again, because the
     * condition that triggered it — the road and the route disagreeing — is not
     * something Shunt can observe getting better.
     */
    private var stoodDown = false

    /** When each re-plan happened, newest last, trimmed to the window. */
    private val replanTimes = ArrayDeque<Long>()

    /**
     * Whether re-planning has become a fight.
     *
     * Off-route, re-plan, push, the car turns back towards a road the driver is
     * refusing, off-route again — each turn of that loop overrides whatever the
     * driver just did on the car's own screen. Counting the turns is enough to
     * recognise it, and does not require guessing why the road was refused.
     */
    private fun tooManyReplans(): Boolean {
        val now = nowMillis()
        replanTimes.addLast(now)
        while (replanTimes.isNotEmpty() && now - replanTimes.first() > REPLAN_WINDOW_MILLIS) {
            replanTimes.removeFirst()
        }
        return replanTimes.size > MAX_REPLANS_IN_WINDOW
    }

    private suspend fun standDown() {
        stoodDown = true
        onActivity(DriveActivity.StoodDown)
        alerter.alert(Alert.StoodDown)
    }

    /**
     * The bearing to route from: the fix's own heading while under way, null
     * when stopped. A parked car's last heading is just the way it happened to
     * come to rest, and holding a new route to it would rule out the road
     * behind for no reason.
     */
    private fun headingOf(previous: LocationUpdate?, update: LocationUpdate): Double? =
        if (isMoving(previous, update)) update.bearingDegrees else null

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
        if (stoodDown) return
        val sending = aim(chain)
        val pushed = runCatching { vehicle.pushRoute(sending) }
            .getOrElse { e -> PushResult.Failed("push threw: ${e.message}", retryable = true) }
        if (pushed is PushResult.Failed) {
            alerter.alert(Alert.AdvanceFailed(sending, pushed.reason, pushed.retryable))
        }
    }

    /**
     * Put the car back on the pin we are steering to, after a charging check.
     *
     * **Observed on a real drive**, and the reason this is unconditional. A
     * charging probe has to redirect the car at the final destination to ask its
     * question — that is the only way to read what the car intends — and the
     * coordinator was left to put the aim back itself. It does, on the paths it
     * knows about. It cannot on the others: a re-assert that reports failure
     * after the car has already taken it, a resume whose re-plan finds nothing,
     * an exception on the way out. On every one of those the car is left holding
     * the trip's destination, and a car holding the destination drives to it —
     * off our route, which is then reported as off-route, re-planned, and the
     * driver ends up on a road with cameras on it that they had a clean route
     * around.
     *
     * So the monitor asserts it instead of trusting it. Every probe that changes
     * nothing ends with the car aimed where the monitor believes it is aimed,
     * which is the only claim worth making after touching the car's destination.
     * A wasted command is a rate-limited call every 45 s at worst; the failure it
     * replaces is silent camera exposure.
     *
     * Unconditional here means *whatever the probe concluded*, not *whatever the
     * driver wants*: [stoodDown] still stops it, because a monitor that kept
     * pushing after standing down would be back to fighting the driver (§6.1).
     *
     * Limited to steering because that is exactly when a probe has to move the
     * car. A car that holds the final destination can be read for free — no
     * push, no redirect, nothing to put back — and re-sending its own
     * destination to it would be pure traffic. A steered car never holds the
     * destination, so every probe redirects it and every probe owes it an aim.
     */
    private suspend fun reaim(remaining: List<GeoPoint>) {
        if (!steering || stoodDown || remaining.isEmpty()) return
        val sending = aim(remaining)
        val result = runCatching { vehicle.advanceTo(sending) }
            .getOrElse { e -> PushResult.Failed("re-aim threw: ${e.message}", retryable = true) }
        if (result is PushResult.Failed) {
            alerter.alert(Alert.AdvanceFailed(sending, result.reason, result.retryable))
        }
    }

    private suspend fun advance(remaining: List<GeoPoint>) {
        if (stoodDown) return
        val sending = aim(remaining)
        // Numbered from the driver's point of view: how many of this plan's pins
        // are behind them, out of how many there were.
        onActivity(DriveActivity.SendingWaypoint(totalPins - remaining.size + 1, totalPins))
        val result = runCatching { vehicle.advanceTo(sending) }
            .getOrElse { e -> PushResult.Failed("advance threw: ${e.message}", retryable = true) }
        onActivity(if (stoodDown) DriveActivity.StoodDown else DriveActivity.Watching)
        if (result is PushResult.Failed) {
            // Loud: the car may still stop at the waypoint we failed to drop.
            alerter.alert(Alert.AdvanceFailed(sending, result.reason, result.retryable))
        }
    }

    private companion object {
        /**
         * How many re-plans in [REPLAN_WINDOW_MILLIS] before Shunt gives the car
         * back. Three is comfortably more than an ordinary drive produces — a
         * missed turn re-plans once — and well short of the number it takes for
         * a driver to realise they are being overruled.
         */
        /** See [DriveMonitorBounds]. */
        const val ABANDONED_STRETCH_METERS = DriveMonitorBounds.ABANDONED_STRETCH_METERS
        const val ABANDONED_SPACING_METERS = DriveMonitorBounds.ABANDONED_SPACING_METERS

        const val MAX_REPLANS_IN_WINDOW = 3
        const val REPLAN_WINDOW_MILLIS = 5 * 60_000L

        /** At or above this the car is under way, so its trip planner has run. */
        const val MOVING_METERS_PER_SEC = 2.0

        /** No speed in the fix: this much ground covered between fixes means the same. */
        const val MOVING_METERS_BETWEEN_FIXES = 15.0
    }
}
