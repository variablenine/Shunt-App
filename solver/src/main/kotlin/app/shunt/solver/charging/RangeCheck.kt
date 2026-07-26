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
    /** Range we're willing to count on — derated and with a reserve held back. */
    val usableMeters: Double,
    /** The shortest option offered for the same trip, for the detour comparison. */
    val shortestOptionMeters: Int,
    val batteryPercent: Int?,
) {
    val level: Level get() = when {
        routeMeters > usableMeters -> Level.SHORT
        routeMeters > usableMeters * RangeEstimate.TIGHT_FRACTION -> Level.TIGHT
        else -> Level.FINE
    }

    /** How far past the usable range this route runs (0 when it fits). */
    val shortfallMeters: Double get() = (routeMeters - usableMeters).coerceAtLeast(0.0)

    /**
     * True when the *detour* is what breaks it: the shortest option offered
     * would make it and this one won't. Worth saying separately — the answer
     * isn't "charge", it's "this specific route costs you the trip".
     */
    val detourIsTheProblem: Boolean
        get() = routeMeters > usableMeters && shortestOptionMeters <= usableMeters

    enum class Level {
        /** Comfortably within range. */
        FINE,

        /** Makes it, but without much left — worth mentioning, not worth alarm. */
        TIGHT,

        /** Won't make it on the charge in the battery. */
        SHORT,
    }
}

/** Turns the car's reported range into a figure it's safe to plan against. */
object RangeEstimate {

    const val METERS_PER_MILE = 1_609.344

    /**
     * Tesla reports the EPA-rated remaining range, which real driving does not
     * achieve: highway speed, cold, heat, hills and headwind all take a bite.
     * Three quarters is the conventional rule of thumb for planning against a
     * rated figure, and erring low is the safe direction for this particular
     * warning — the cost of being wrong is being stranded.
     */
    const val REAL_WORLD_FRACTION = 0.75

    /** Never plan to arrive on empty; hold back roughly ten miles. */
    const val RESERVE_METERS = 16_000.0

    /** Above this fraction of usable range, a route is "tight" rather than fine. */
    const val TIGHT_FRACTION = 0.85

    /**
     * How much of the usable range to spend getting to a charging stop, so the
     * car still arrives there with something in hand.
     */
    const val REACHABLE_FRACTION = 0.8

    fun usableMeters(estimatedRangeMiles: Double): Double =
        (estimatedRangeMiles * METERS_PER_MILE * REAL_WORLD_FRACTION - RESERVE_METERS)
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
    ): RangeCheck? {
        val miles = estimatedRangeMiles?.takeIf { it > 0 } ?: return null
        return RangeCheck(
            routeMeters = routeMeters,
            usableMeters = usableMeters(miles),
            shortestOptionMeters = shortestOptionMeters,
            batteryPercent = batteryPercent,
        )
    }
}
