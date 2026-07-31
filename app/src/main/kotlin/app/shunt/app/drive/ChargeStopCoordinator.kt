package app.shunt.app.drive

import app.shunt.app.plan.Destination
import app.shunt.app.plan.DrivePlan
import app.shunt.core.GeoPoint
import app.shunt.solver.geo.haversineMeters
import app.shunt.tesla.ActiveRoute
import app.shunt.tesla.PushResult
import app.shunt.tesla.VehicleNavClient
import kotlinx.coroutines.delay

/** Which leg of the trip is in force. */
sealed interface Leg {
    /** Heading for the destination the driver asked for. */
    data object ToDestination : Leg

    /** Heading for a charger the car inserted, on a route Shunt planned. */
    data class ToChargeStop(val stop: ChargeStop) : Leg

    /** Stopped at that charger, waiting for the car to decide what's next. */
    data class ParkedAt(val stop: ChargeStop) : Leg
}

/** What a check concluded. The monitor pushes new plans; see [ChargeStopCoordinator]. */
sealed interface LegChange {
    /** Nothing changed — carry on with the plan already in force. */
    data object None : LegChange

    /** Drive this camera-aware route to the charger the car picked. */
    data class ToChargeStop(val stop: ChargeStop, val plan: DrivePlan) : LegChange

    /** The car is done charging (or never needed to): here's the route onward. */
    data class ToDestination(val plan: DrivePlan) : LegChange

    /**
     * A charging stop was detected but no camera-aware route to it could be
     * produced. The car is left navigating there its own way, with no avoidance
     * in force — which the driver has to be told, loudly.
     */
    data class Unroutable(val stop: ChargeStop) : LegChange

    /** A probe or steering restore failed; the car's actual target is uncertain. */
    data class VehicleUpdateFailed(val reason: String, val retryable: Boolean) : LegChange
}

/**
 * Keeps Shunt's camera avoidance in force across the charging stops **the car
 * inserts by itself**.
 *
 * Tesla's trip planner adds a Supercharger to any trip the battery won't cover
 * and navigates there first, silently. Shunt can't suppress that and shouldn't
 * try: the car knows its state of charge and Shunt doesn't. So instead of
 * fighting it, this asks the car where it's actually going and then plans *that*
 * leg with camera avoidance, so the roads really driven are the ones we vetted.
 *
 * Two facts about the car shape the whole design:
 *
 *  - **It only accepts one destination.** On a car that requires Tesla's signed
 *    command protocol, every push collapses to a single shared destination (see
 *    [PushResult.DestinationOnly]). So whatever we last pushed is exactly what
 *    the car is aiming at, and a read that names somewhere else miles away is
 *    the car's own charging stop.
 *  - **It doesn't plan charging until it's in drive.** Asking at the moment Go
 *    is tapped always answers "no charging stop", so the check has to repeat
 *    once the car is actually moving.
 *
 * Which gives two kinds of check, and the difference matters:
 *
 *  - While the car already holds the final destination, asking costs **nothing**
 *    — no push, no redirect — so it happens every [ProbeWindow.readIntervalMillis]
 *    once under way, which is what catches the charging stop appearing.
 *  - While the car is aimed at a charger, finding out whether it *still* intends
 *    to means handing it the final destination again and re-reading. That
 *    briefly points it somewhere else, so it is rationed by [ProbeWindow] to
 *    moments where a momentarily different instruction cannot matter.
 *
 * Vehicle interaction is split deliberately: this class owns the re-assert push
 * and putting steering back afterwards, while pushing a *new leg* is left to
 * [DriveMonitor], so a push failure alerts through the same path as every other.
 */
class ChargeStopCoordinator(
    private val vehicle: VehicleNavClient,
    /** Reads what the car says it's navigating to. Read-only; never wakes it. */
    private val readActiveRoute: suspend () -> ActiveRoute?,
    /** Plans a camera-aware leg, or null when none could be produced. */
    private val planLeg: suspend (
        from: GeoPoint,
        via: List<GeoPoint>,
        to: Destination,
        headingDegrees: Double?,
    ) -> DrivePlan?,
    private val window: ProbeWindow = ProbeWindow(),
    /** How long to let the car settle on a route after re-asserting the destination. */
    private val settleMillis: Long = 8_000,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        // A re-assert blocks the location stream while the car settles, so the
        // gate distances must exceed what a car can cover in that time —
        // otherwise a camera or a waypoint could pass unwarned inside it.
        // Assert it here so future tuning can't quietly break the guarantee.
        val reach = MAX_PLAUSIBLE_SPEED_MPS * settleMillis / 1000.0
        require(window.clearOfCameraMeters > reach && window.clearOfWaypointMeters > reach) {
            "probe gate must be wider than the ${reach.toInt()} m a car can cover while probing"
        }
    }

    var leg: Leg = Leg.ToDestination
        private set

    private var lastProbeAt: Long = nowMillis()

    /**
     * True while the car is aimed at the trip's real destination, so a check
     * needs no push at all — every push collapses to a single destination, and
     * that destination is already the right one.
     */
    private val carHoldsFinalDestination: Boolean get() = leg is Leg.ToDestination

    /** The charger being driven to right now, or null when heading for the destination. */
    fun chargeStopUnderWay(): ChargeStop? = (leg as? Leg.ToChargeStop)?.stop

    /**
     * Whether to check now. [moving] gates the free reads: a parked car hasn't
     * planned its charging yet, so asking tells us nothing.
     */
    fun isCheckDue(
        moving: Boolean,
        metersToNextWaypoint: Double?,
        metersToNearestCamera: Double?,
        offRoute: Boolean,
    ): Boolean {
        val since = nowMillis() - lastProbeAt
        return when {
            leg is Leg.ParkedAt -> window.isSafeParked(since)
            carHoldsFinalDestination ->
                moving && window.isSafeToRead(since, metersToNearestCamera, offRoute)
            else -> window.isSafeUnderWay(since, metersToNextWaypoint, metersToNearestCamera, offRoute)
        }
    }

    /**
     * Ask the car what it intends and bring the trip into line with the answer.
     * [steeringChain] is the not-yet-passed part of the route in force, so an
     * unchanged verdict puts the car back exactly where it was.
     *
     * Only call this when [isCheckDue] said so.
     */
    suspend fun check(
        from: GeoPoint,
        destination: Destination,
        remainingStops: List<GeoPoint>,
        steeringChain: List<GeoPoint>,
        /** The bearing we're travelling on, so a new leg can't start with a U-turn. */
        headingDegrees: Double? = null,
    ): LegChange {
        lastProbeAt = nowMillis()
        val reasserted = !carHoldsFinalDestination
        if (reasserted) {
            // If the destination doesn't even reach the car, the read that
            // follows would describe the old trip — so don't read, and don't
            // touch the leg.
            val sent = runCatching { vehicle.pushRoute(listOf(destination.location)) }
                .getOrElse { PushResult.Failed("re-assert threw", retryable = true) }
            if (sent is PushResult.Failed) {
                return LegChange.VehicleUpdateFailed(sent.reason, sent.retryable)
            }
            pause(settleMillis)
        }

        val probe = classify(destination.location, steeringChain)

        return when (probe) {
            // Unreadable says nothing at all — never read it as "no charging".
            ChargeProbe.Unknown -> unchanged(reasserted, steeringChain)

            ChargeProbe.DirectToDestination -> when (leg) {
                is Leg.ToDestination -> LegChange.None
                // The car dropped the charging stop (or has finished charging):
                // the rest of the trip is a straight run to the destination.
                is Leg.ToChargeStop, is Leg.ParkedAt ->
                    resumeToDestination(from, remainingStops, destination, headingDegrees)
            }

            is ChargeProbe.StopInserted -> {
                val known = (leg as? Leg.ToChargeStop)?.stop ?: (leg as? Leg.ParkedAt)?.stop
                if (known != null && ChargeStopReading.isSameStop(known, probe.stop)) {
                    // Same charger, same leg — confirmed, put the car back on it.
                    unchanged(reasserted, steeringChain)
                } else {
                    startChargeLeg(from, probe.stop, headingDegrees)
                }
            }
        }
    }

    /** The charge leg ended: we're at the charger. The car decides what's next. */
    fun onReachedChargeStop() {
        val stop = chargeStopUnderWay() ?: return
        leg = Leg.ParkedAt(stop)
        // Ask promptly rather than waiting out the driving interval — the car
        // may re-plan the moment it's plugged in.
        lastProbeAt = nowMillis() - window.parkedIntervalMillis
    }

    private suspend fun startChargeLeg(
        from: GeoPoint,
        stop: ChargeStop,
        headingDegrees: Double?,
    ): LegChange {
        val destination = Destination(stop.name, stop.at)
        val plan = runCatching { planLeg(from, emptyList(), destination, headingDegrees) }.getOrNull()
            ?: return LegChange.Unroutable(stop)
        leg = Leg.ToChargeStop(stop)
        return LegChange.ToChargeStop(stop, plan)
    }

    private suspend fun resumeToDestination(
        from: GeoPoint,
        remainingStops: List<GeoPoint>,
        destination: Destination,
        headingDegrees: Double?,
    ): LegChange {
        val plan = runCatching { planLeg(from, remainingStops, destination, headingDegrees) }.getOrNull()
            // Keep the leg as it was: claiming a route we haven't got would be
            // worse than leaving the car pointed at the destination it has.
            ?: return LegChange.None
        leg = Leg.ToDestination
        return LegChange.ToDestination(plan)
    }

    /**
     * Nothing changed. If we redirected the car to ask, put it back on the leg
     * in force — steering must never be left pointing at the final destination
     * while Shunt believes it is driving to a charger.
     */
    private suspend fun unchanged(reasserted: Boolean, steeringChain: List<GeoPoint>): LegChange {
        if (reasserted && steeringChain.isNotEmpty()) {
            val restored = runCatching { vehicle.advanceTo(steeringChain) }
                .getOrElse { PushResult.Failed("steering restore threw", retryable = true) }
            if (restored is PushResult.Failed) {
                return LegChange.VehicleUpdateFailed(restored.reason, restored.retryable)
            }
        }
        return LegChange.None
    }

    private suspend fun classify(finalDestination: GeoPoint, steeringChain: List<GeoPoint>): ChargeProbe {
        val route = runCatching { readActiveRoute() }.getOrNull()
        val probe = ChargeStopReading.classify(route, finalDestination)
        if (probe is ChargeProbe.StopInserted && namesOurOwnWaypoint(probe, steeringChain)) {
            // The car is still naming a pin we pushed, so our re-assert hasn't
            // taken effect: this read describes the old trip and says nothing.
            return ChargeProbe.Unknown
        }
        return probe
    }

    /**
     * A single-destination car reports the last point of whatever chain we
     * pushed; one that took the whole chain reports the first still ahead.
     * Either being echoed back is our own instruction, not a charging stop.
     */
    private fun namesOurOwnWaypoint(probe: ChargeProbe.StopInserted, steeringChain: List<GeoPoint>): Boolean =
        listOfNotNull(steeringChain.firstOrNull(), steeringChain.lastOrNull()).any {
            haversineMeters(probe.stop.at, it) <= ChargeStopReading.SAME_PLACE_METERS
        }

    private companion object {
        /** Upper bound on road speed, for the probe-gate sanity check (~100 mph). */
        const val MAX_PLAUSIBLE_SPEED_MPS = 45.0
    }
}
