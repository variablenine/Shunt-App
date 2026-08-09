package app.shunt.solver.brouter

/**
 * Where planning time went.
 *
 * **Temporary diagnostic.** It exists because a five-hour route took about five
 * minutes to plan on a real phone and the sandbox this is developed in cannot
 * run BRouter against real map tiles — so the only way to find out which part
 * is slow is to measure it on the device that is actually slow. Remove it, and
 * the block it draws on the result sheet, once long-route planning is fast
 * enough that nobody is asking.
 *
 * The breakdown is deliberately two levels. [stages] says which phase of
 * planning cost the time; [routingPasses] splits the routing phase by what each
 * search over the road graph was actually for, because "routing is slow" and
 * "the hard-block pass fails and we pay for a fourth search every time" call
 * for completely different fixes.
 */
data class PlanTimings(
    val stages: List<Timed>,
    val routingPasses: List<Timed>,
) {
    val totalMillis: Long get() = stages.sumOf { it.millis }

    /** One measured piece of work. */
    data class Timed(val label: String, val millis: Long) {
        /** Rounded to a tenth of a second — nothing here is precise enough to want more. */
        val seconds: Double get() = millis / 1000.0
    }

    companion object {
        const val STAGE_CAMERAS = "Finding cameras"
        const val STAGE_ROUTING = "Planning routes"
        const val STAGE_PINS = "Placing pins"
    }
}
