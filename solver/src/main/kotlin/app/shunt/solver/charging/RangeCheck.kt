package app.shunt.solver.charging

/**
 * How a planned route's length compares with what the car can actually cover.
 *
 * This exists because of a failure mode particular to Shunt: the whole point of
 * the app is to take a *longer* road than the obvious one. A trip the car would
 * comfortably make direct can stop being comfortable once the camera-avoiding
 * detour is added, and the car's own trip planner never sees that route — it
 * only knows the destination we shared, so it plans charging for the direct
 * line. In a dense city on a low battery that gap is exactly where someone ends
 * up stranded, which is why this is computed on the phone from the route we
 * actually intend to drive.
 */
data class RangeCheck(
    val routeMeters: Int,
    /** Range we're willing to count on leaving now — derated, reserve held back. */
    val usableMeters: Double,
    /** The shortest option offered for the same trip, for the detour comparison. */
    val shortestOptionMeters: Int,
    val batteryPercent: Int?,
    /**
     * The trip split at its charging stops, in order.
     *
     * A trip with a charging stop is not one long run, and treating it as one
     * is why adding a stop never cleared the warning: a 400 km trip stayed
     * "400 km against 260 km of range" even when it had become two comfortable
     * legs. What matters is whether each leg fits, not the total.
     */
    val legMeters: List<Int> = listOf(routeMeters),
    /** Range available setting off again from a charging stop. */
    val chargedUsableMeters: Double = usableMeters,
) {
    /** What each leg has to work with: the battery now, then a charge each time. */
    fun allowanceFor(leg: Int): Double = if (leg == 0) usableMeters else chargedUsableMeters

    /** Metres each leg runs past what it has, negative when it fits. */
    val legShortfalls: List<Double>
        get() = legMeters.mapIndexed { i, meters -> meters - allowanceFor(i) }

    val level: Level get() = when {
        legShortfalls.any { it > 0 } -> Level.SHORT
        legMeters.indices.any { legMeters[it] > allowanceFor(it) * RangeEstimate.TIGHT_FRACTION } ->
            Level.TIGHT
        else -> Level.FINE
    }

    /** How far the worst leg runs past its allowance (0 when they all fit). */
    val shortfallMeters: Double get() = (legShortfalls.maxOrNull() ?: 0.0).coerceAtLeast(0.0)

    /** True once the trip has been split by at least one charging stop. */
    val hasChargingStops: Boolean get() = legMeters.size > 1

    /**
     * True when the *detour* is what breaks it: the shortest option offered
     * would make it and this one won't. Worth saying separately — the answer
     * isn't "charge", it's "this specific route costs you the trip". Only
     * meaningful before any charging stop is added, since after that the
     * comparison is against a different trip.
     */
    val detourIsTheProblem: Boolean
        get() = !hasChargingStops && routeMeters > usableMeters && shortestOptionMeters <= usableMeters

    enum class Level {
        /** Comfortably within range. */
        FINE,

        /** Makes it, but without much left — worth mentioning, not worth alarm. */
        TIGHT,

        /** Won't make it: some leg runs past what the battery can cover. */
        SHORT,
    }
}

/** Turns the car's reported range into a figure it's safe to plan against. */
object RangeEstimate {

    const val METERS_PER_MILE = 1_609.344

    /**
     * How far to trust the car's own range estimate.
     *
     * **This was 0.75 and named `REAL_WORLD_FRACTION`, and both were wrong.**
     * The reasoning was sound for the *rated* range — EPA figures are not what
     * real driving achieves, and three quarters is the usual rule of thumb — but
     * the field Shunt reads is `est_battery_range`, which Tesla computes from
     * recent consumption and which therefore already has real driving in it.
     * Derating an already-derated number by a further quarter is why a trip the
     * driver knew perfectly well they could make came up in red.
     *
     * From the real reading that exposed it: the car reported the equivalent of
     * 160 miles and the route was 241 km. The car's own estimate was 258 km — it
     * fits, with about 17 km in hand. Shunt presented "about 178 km of usable
     * range" and a 63 km shortfall.
     *
     * So the estimate is now taken at face value, and **the entire margin lives
     * in [RESERVE_METERS]** where it can be reasoned about as one number instead
     * of compounding invisibly with another. That reading now comes out as
     * [RangeCheck.Level.TIGHT] — makes it, not by much — which is the honest
     * answer and the one the driver gave.
     *
     * Lower this if field reports show the car's estimate running optimistic
     * (deep cold is the likely case, though the car re-estimates for that too).
     * Erring low is still the safe direction, because stranded beats nagged —
     * but a warning that fires on trips that are plainly fine is not a
     * conservative warning, it is one people learn to dismiss, and that costs
     * the real ones too.
     */
    const val RANGE_TRUST_FRACTION = 1.0

    /**
     * Never plan to arrive on empty; hold back roughly ten miles.
     *
     * Now the *only* margin between the car's estimate and what Shunt will plan
     * against, which makes it the dial to turn if these warnings turn out to be
     * pitched wrongly in either direction. Ten miles is enough to reach a
     * charger from the point the car says it is empty, which is what a reserve
     * is for.
     */
    const val RESERVE_METERS = 16_000.0

    /** Above this fraction of usable range, a route is "tight" rather than fine. */
    const val TIGHT_FRACTION = 0.85

    /**
     * How much of the usable range to spend getting to a charging stop, so the
     * car still arrives there with something in hand.
     */
    const val REACHABLE_FRACTION = 0.8

    /**
     * What a road-trip charging stop actually leaves in the battery. Charging
     * past ~80% is slow enough that nobody does it mid-trip, so planning the
     * next leg against a full battery would promise range that won't be there.
     */
    const val CHARGE_TO_FRACTION = 0.8

    fun usableMeters(estimatedRangeMiles: Double): Double =
        (estimatedRangeMiles * METERS_PER_MILE * RANGE_TRUST_FRACTION - RESERVE_METERS)
            .coerceAtLeast(0.0)

    /**
     * A check for the route the user is about to drive, or null when the car's
     * range isn't known — an unknown range must produce no claim at all, in
     * either direction.
     */
    fun of(
        routeMeters: Int,
        shortestOptionMeters: Int,
        estimatedRangeMiles: Double?,
        batteryPercent: Int?,
        /** Leg lengths between charging stops; empty means one unbroken run. */
        legMeters: List<Int> = emptyList(),
    ): RangeCheck? {
        val miles = estimatedRangeMiles?.takeIf { it > 0 } ?: return null
        return RangeCheck(
            routeMeters = routeMeters,
            usableMeters = usableMeters(miles),
            shortestOptionMeters = shortestOptionMeters,
            batteryPercent = batteryPercent,
            legMeters = legMeters.ifEmpty { listOf(routeMeters) },
            chargedUsableMeters = chargedUsableMeters(miles, batteryPercent),
        )
    }

    /**
     * Range to plan the leg after a charging stop against. Scales the reported
     * remaining range back up to a full battery, then takes the fraction a
     * road-trip charge actually reaches. Without a battery reading there is
     * nothing to scale from, so it falls back to what's in the car now — the
     * conservative direction.
     */
    fun chargedUsableMeters(estimatedRangeMiles: Double, batteryPercent: Int?): Double {
        val percent = batteryPercent?.takeIf { it in 1..100 } ?: return usableMeters(estimatedRangeMiles)
        val fullRated = estimatedRangeMiles / (percent / 100.0)
        return usableMeters(fullRated * CHARGE_TO_FRACTION)
    }
}
