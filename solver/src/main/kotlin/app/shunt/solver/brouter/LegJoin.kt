package app.shunt.solver.brouter

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.haversineMeters

/**
 * Removing the pointless out-and-back where two legs meet.
 *
 * ## The problem
 *
 * A leg boundary is a **hard waypoint**: the first leg must end at it and the
 * next must start from it. [LegSplitter] chooses it on the *direct* road,
 * because that is the only geometry available before anything expensive has run
 * — and the direct road is not where a camera-avoiding route goes.
 *
 * So the two legs can disagree about the boundary. The first drives out to touch
 * it; the second, planned freely from there, immediately comes back the way it
 * came and heads off in another direction. The join is a spur: miles out and the
 * same miles back, for a point nobody asked to visit. Reported from a real plan:
 *
 * > a leg needs to go backwards after it found the way to the next spot, we
 * > should make it so if it has to double back it can just delete the useless
 * > part of the leg up to the point where it doesn't need to double back.
 *
 * Which is exactly right, and it is a *trim*, not a re-plan: both legs are
 * already correct routes, and the spur is the overlap between them.
 *
 * ## The rule
 *
 * Find the last place the two lines are still together — the point after which
 * the second leg stops retracing the first — and cut both there. Everything the
 * first leg drives beyond that point it only drives in order to come straight
 * back, and everything the second leg drives before it is that same road
 * backwards.
 *
 * ## Why this is safe
 *
 * The result is still a route the car can drive, because the trimmed ends meet
 * at a point **both legs already passed through**. Nothing is invented: the join
 * point is a vertex of the first leg and lies on the second, so the concatenated
 * line is a sub-path of what was already planned.
 *
 * What it cannot do is make the route *worse*: removing an out-and-back can only
 * shorten the drive, and neither leg's remaining geometry has changed, so the
 * camera labelling of what is left is exactly what it was.
 *
 * Cameras are the one thing to be careful about, and the direction of the error
 * is the right one — a trimmed route passes a **subset** of what the untrimmed
 * pair passed, so a count carried over from before the trim can only ever
 * overstate exposure. Overstating is safe; understating is the bug this project
 * exists to avoid.
 */
object LegJoin {

    /**
     * How close two points must be to count as the same piece of road.
     *
     * Generous relative to a road's width and tight relative to a detour. The
     * two legs were planned separately, so even where they use the same road
     * their vertices do not line up — one may have a node the other lacks.
     */
    const val OVERLAP_METERS = 60.0

    /**
     * Only trim a spur worth trimming.
     *
     * Legs meeting at a boundary always share a little road right at the join —
     * that is what a shared waypoint means — and cutting a hundred metres off
     * both would be churn for nothing. This is the length of retrace that makes
     * the spur real rather than incidental.
     */
    const val MIN_SPUR_METERS = 400.0

    /**
     * The furthest back into the previous leg a spur may reach.
     *
     * Two jobs, and both matter. It is the **safety bound**: a spur is a
     * detour to touch a boundary, so it is short by nature, and a cap means
     * this can never delete a large piece of a leg however oddly the two lines
     * happen to overlap. And it is the **cost bound**: without it, a second leg
     * that retraced a long way would be matched point-by-point against the
     * whole of the first, which on two cross-state polylines is tens of
     * millions of distance calculations.
     */
    const val MAX_SPUR_METERS = 40_000.0

    /**
     * How close a pin has to be to the trimmed line to still count as on it.
     *
     * Wider than [OVERLAP_METERS] on purpose. A pin sits *beside* the road it
     * holds — the refiner places it past a fork, the extractor at a turn — and
     * the line it is compared against has been sampled, so the nearest vertex
     * can be a couple of hundred metres away on a fast road with few nodes.
     * Keeping a pin that is no longer needed costs one rate-limited command;
     * dropping one that is loses a turn.
     */
    const val PIN_ON_ROUTE_METERS = 400.0

    /** What came back from a trim: the two legs, and how much road it saved. */
    data class Trimmed(
        val previous: List<GeoPoint>,
        val next: List<GeoPoint>,
        val savedMeters: Double,
    ) {
        val changed: Boolean get() = savedMeters > 0.0
    }

    /**
     * [previous] and [next] with the out-and-back at their join removed.
     *
     * Returns them unchanged when there is no spur, which is the ordinary case:
     * most boundaries land where both legs are going the same way anyway, which
     * is exactly what [LegSplitter] chooses them for.
     */
    fun trimDoubleBack(
        previous: List<GeoPoint>,
        next: List<GeoPoint>,
        overlapMeters: Double = OVERLAP_METERS,
        minSpurMeters: Double = MIN_SPUR_METERS,
    ): Trimmed {
        val unchanged = Trimmed(previous, next, 0.0)
        if (previous.size < 2 || next.size < 2) return unchanged

        // Only the tail of the first leg is eligible, and that bound does the
        // real safety work: whatever the two lines do elsewhere, this can only
        // ever remove road the first leg drove just before its own end, which
        // is the only road a boundary detour can consist of.
        val tailStart = indexWithinOfEnd(previous, MAX_SPUR_METERS)

        // How far into `next` the retrace goes. Walked from the start, because
        // a spur is by definition at the join — a later crossing of the same
        // road is a genuine part of the route, not something to cut out.
        var retraceEnd = 0
        var cutAt = previous.lastIndex
        for (i in next.indices) {
            val onPrevious = lastIndexNear(previous, next[i], overlapMeters, tailStart) ?: break
            retraceEnd = i
            cutAt = minOf(cutAt, onPrevious)
        }
        if (retraceEnd == 0) return unchanged

        // The spur is what the first leg drives past the join point and back.
        val saved = lengthOf(previous, cutAt, previous.lastIndex) +
            lengthOf(next, 0, retraceEnd)
        if (saved < minSpurMeters) return unchanged

        // Never trim a leg away entirely. A zero-length leg is not a shorter
        // route, it is a missing one — and the drive monitor would be handed a
        // chain with nothing in it.
        val trimmedPrevious = previous.subList(0, cutAt + 1)
        val trimmedNext = next.subList(retraceEnd, next.size)
        if (trimmedPrevious.size < 2 || trimmedNext.size < 2) return unchanged

        return Trimmed(trimmedPrevious, trimmedNext, saved)
    }

    /**
     * Where [p] sits on [line], as the **last** index within [meters], or null
     * when the line does not come near it.
     *
     * Last rather than first, because a spur is a road travelled twice: the
     * point where the second leg leaves the first is the one furthest along
     * what the first leg drove, and taking the first match would cut the
     * previous leg back to its outbound pass and delete real route with it.
     */
    private fun lastIndexNear(
        line: List<GeoPoint>,
        p: GeoPoint,
        meters: Double,
        from: Int,
    ): Int? {
        var best: Int? = null
        for (i in from..line.lastIndex) {
            if (haversineMeters(line[i], p) <= meters) best = i
        }
        return best
    }

    /** The earliest index of [line] within [meters] of its end, walking back. */
    private fun indexWithinOfEnd(line: List<GeoPoint>, meters: Double): Int {
        var walked = 0.0
        for (i in line.lastIndex downTo 1) {
            walked += haversineMeters(line[i - 1], line[i])
            if (walked > meters) return i
        }
        return 0
    }

    /**
     * [pins] with the ones that no longer sit on [line] removed.
     *
     * **The other half of a trim, and it was missing.** Cutting a leg's polyline
     * back does nothing to the pins that were placed along the part removed, so
     * they stayed — floating beside a route that no longer goes near them.
     * Reported from a real plan, with the cause identified from the map: "That
     * waypoint was placed when a leg was facing that way, and then it got
     * cropped, but the waypoint stayed."
     *
     * That is worse than untidy. A pin is a *constraint sent to the car*: the
     * drive monitor aims the vehicle at each one in turn, so a pin out on a
     * removed spur would steer the car back down the road the trim exists to
     * delete — and it would do it under FSD, at a junction, exactly the shape of
     * failure §6.1 is about.
     *
     * [nearMeters] is generous for the same reason [OVERLAP_METERS] is: the pin
     * was placed on a polyline that has since been re-sampled, so an exact match
     * is not available and erring toward keeping a pin is the safe direction —
     * a kept pin is at worst redundant, a dropped one loses a turn.
     */
    fun pinsOn(
        line: List<GeoPoint>,
        pins: List<GeoPoint>,
        nearMeters: Double = PIN_ON_ROUTE_METERS,
    ): List<GeoPoint> {
        if (line.size < 2 || pins.isEmpty()) return pins
        return pins.filter { pin -> line.any { haversineMeters(it, pin) <= nearMeters } }
    }

    private fun lengthOf(line: List<GeoPoint>, from: Int, to: Int): Double {
        var total = 0.0
        for (i in from until to) total += haversineMeters(line[i], line[i + 1])
        return total
    }
}
