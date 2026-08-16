package app.shunt.solver.brouter

import app.shunt.core.GeoPoint

/**
 * One ask of the routing engine.
 *
 * A parameter object rather than a long argument list because the planner takes
 * its router as a *function*, so every new thing the router needs to know used
 * to change that signature and every fake in the test suite with it. The seam
 * exists so the hard parts of planning can be tested without a map tile on
 * disk; it should not cost a refactor each time.
 */
data class RouteRequest(
    /** Origin, any intermediate stops in order, then the destination. */
    val points: List<GeoPoint>,
    /** Camera zones to avoid, shaped by field of view. Empty means the plain fastest road. */
    val cameras: List<CameraVision> = emptyList(),
    /**
     * The compass bearing the vehicle is actually travelling on, when it is
     * moving. Given it, the route has to set off the way the car is already
     * pointing instead of doubling back — a re-plan that answers with a U-turn
     * is worse than useless at 60 mph. Null when parked or unknown: a stationary
     * fix's bearing is noise, and pinning the route to it would rule out the
     * road behind for no reason.
     */
    val headingDegrees: Double? = null,
    /**
     * Points on roads the driver cannot or will not use, treated as impassable.
     *
     * This is how a closed road stops being offered. Shunt cannot see a closure;
     * it can only see that the driver left the route and did not come back, and
     * that re-planning onto the same stretch produced the loop that made the app
     * fight them for control of the car (see CLAUDE.md §6.1). So the stretch
     * just abandoned is blocked for the rest of *this* plan and no longer.
     *
     * Deliberately not persisted. A road closed this afternoon is open tomorrow,
     * and Shunt has nowhere to keep that belief and no business trying.
     */
    val blocked: List<GeoPoint> = emptyList(),
    /**
     * Ceiling for all the searches in this request together, or null for the
     * router's default. A plan computed while moving is worth less the longer it
     * takes, so a mid-drive caller asks for far less than a parked one.
     */
    val budgetMillis: Long? = null,
    /**
     * An earlier round of this same plan already *proved* no hard-blocked route
     * exists, so don't spend the search proving it again.
     *
     * Sound rather than merely convenient, and the argument is monotonicity: a
     * corridor widen only ever **adds** cameras, so the zones the block is given
     * on the later round are a superset of the ones it already failed against.
     * A route that did not exist among fewer obstacles cannot appear among more.
     *
     * "Proved" is doing real work in that sentence — this is set only when the
     * pass reported *no route*, never when it ran out of time. A timed-out
     * search has proved nothing at all, and skipping it on a later round would
     * be discarding an option on no evidence.
     *
     * Worth the flag because it is expensive: on a real leg into a dense metro
     * the block spent 12-15 s reaching that conclusion, and then spent it again
     * on the widen round — out of the same budget that then had nothing left for
     * the pass that actually produced a route.
     */
    val hardBlockProvenImpossible: Boolean = false,
)
