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
)
