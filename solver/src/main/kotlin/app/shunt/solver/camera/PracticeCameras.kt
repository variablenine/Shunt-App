package app.shunt.solver.camera

import app.shunt.core.GeoPoint
import app.shunt.solver.geo.BoundingBox
import kotlin.math.abs
import kotlin.math.floor

/**
 * Made-up cameras, placed the same way every time, for testing avoidance where
 * there are no real ones.
 *
 * ## Why this is needed
 *
 * A county that removes its ALPRs is the outcome this project wants, and it also
 * makes the project untestable locally — nothing to avoid means nothing to
 * verify. The maintainer's own county has discontinued Flock, and DeFlock will
 * eventually stop listing what is no longer there, so the routes, the pins, the
 * approach warnings, and the spoken alerts all become unexercisable at home.
 *
 * These fill that gap without touching the real dataset.
 *
 * ## Determinism is the whole requirement
 *
 * A test camera that moves between runs is worse than useless: the route changes
 * underneath you, a fix cannot be told from a coincidence, and a bug report about
 * one cannot be reproduced by anyone else. So positions come from a hash of the
 * cell they sit in, not from a random number generator with a shared seed —
 * which means any two devices, at any time, in any order of queries, produce the
 * *identical* set for the same area. There is no state to get out of step.
 *
 * ## They must never be mistaken for real ones
 *
 * Every camera carries [PRACTICE_TAG], and everything downstream that shows a
 * camera to a user is expected to say so. A driver who cannot tell a practice
 * camera from a Flock unit has been given false information about where they are
 * being watched, which is the one mistake this app cannot afford to make.
 */
object PracticeCameras {

    /**
     * On every generated camera. Present, it means "Shunt invented this".
     *
     * A tag rather than an id range or a separate list, so it survives being
     * mixed into the real set, passed through the planner, drawn on the map, and
     * written to a log — all of which happen — without anywhere having to
     * remember which list a camera came from.
     */
    const val PRACTICE_TAG = "shunt:practice"

    /**
     * Candidate grid spacing, in degrees — roughly 1.1 km north-south.
     *
     * Much finer than the spacing that ends up in the field, because most
     * candidates are thrown away: only the ones that land near a road survive
     * ([snapped]). A coarse grid produced cameras a kilometre out in a field,
     * where they watch nothing and no route ever has to avoid them — the
     * reported complaint that *"the practice cams aren't really showing up on
     * actual roads so it doesn't really affect drives much"*.
     */
    const val CELL_DEGREES = 0.01

    /**
     * Roughly how many candidate cells carry one, before snapping.
     *
     * Not every cell: a landscape with a camera at every junction is one where
     * avoidance is hopeless, and the interesting behaviour — the detour, the
     * guard pins, the warning — needs gaps to route through.
     */
    const val OCCUPANCY = 0.35

    /**
     * How far a candidate may be from a road and still be used.
     *
     * This one number does both jobs. It puts every surviving camera *on* a
     * road, which is what makes routes actually have to avoid them; and because
     * candidates with no road nearby are dropped, a uniform grid comes out dense
     * in towns and sparse in open country without anything having to know where
     * towns are. **The road network is the population map** — which is the same
     * observation the real dataset embodies, since ALPRs are sited where the
     * junctions and the traffic are.
     */
    const val SNAP_RADIUS_METERS = 120.0

    /**
     * Every practice camera inside [bbox], placed on real roads.
     *
     * [snapToRoads] moves each candidate onto the nearest way and drops the ones
     * with none within [SNAP_RADIUS_METERS]; without it (or where it fails) the
     * candidates are used as they fall, which is the old behaviour and is still
     * better than nothing on a device with no tiles for the area.
     *
     * Deterministic either way: candidates come from the grid cell, and snapping
     * is a function of the map data, so two devices with the same tiles produce
     * the same field.
     */
    fun inBox(
        bbox: BoundingBox,
        snapToRoads: ((List<GeoPoint>, Double) -> List<GeoPoint?>)? = null,
    ): List<Camera> {
        val candidates = candidatesIn(bbox)
        if (snapToRoads == null) return candidates
        val snapped = runCatching { snapToRoads(candidates.map { it.location }, SNAP_RADIUS_METERS) }
            .getOrNull()
            ?: return candidates
        return candidates.mapIndexedNotNull { index, camera ->
            snapped.getOrNull(index)?.let { camera.copy(location = it) }
        }
    }

    /** The unsnapped grid: one candidate per occupied cell, before roads. */
    private fun candidatesIn(bbox: BoundingBox): List<Camera> {
        val out = mutableListOf<Camera>()
        var latCell = floor(bbox.minLat / CELL_DEGREES).toLong()
        val lastLat = floor(bbox.maxLat / CELL_DEGREES).toLong()
        val firstLon = floor(bbox.minLon / CELL_DEGREES).toLong()
        val lastLon = floor(bbox.maxLon / CELL_DEGREES).toLong()
        // A whole-world box would be millions of cells; a trip's box is
        // thousands. Guard anyway, because the caller is a bounding box that has
        // been expanded by a corridor margin and nobody checks it twice.
        var budget = MAX_GENERATED
        while (latCell <= lastLat && budget > 0) {
            var lonCell = firstLon
            while (lonCell <= lastLon && budget > 0) {
                cameraIn(latCell, lonCell)?.let {
                    if (bbox.contains(it.location)) {
                        out += it
                        budget--
                    }
                }
                lonCell++
            }
            latCell++
        }
        return out
    }

    /**
     * The camera in this grid cell, or null where the cell is empty.
     *
     * Everything — whether there is one, where in the cell it sits, and which
     * way it faces — comes from the same hash of the cell's coordinates, so the
     * answer depends on nothing but the cell.
     */
    private fun cameraIn(latCell: Long, lonCell: Long): Camera? {
        val h = hash(latCell, lonCell)
        if (fraction(h) > OCCUPANCY) return null
        // Three independent-enough draws from one hash, by taking different
        // parts of it. Good enough for scattering test fixtures; this is not a
        // place that needs a real PRNG.
        val offsetLat = fraction(hash(h, 1)) * CELL_DEGREES
        val offsetLon = fraction(hash(h, 2)) * CELL_DEGREES
        val bearing = fraction(hash(h, 3)) * 360.0
        val location = GeoPoint(
            lat = latCell * CELL_DEGREES + offsetLat,
            lon = lonCell * CELL_DEGREES + offsetLon,
        )
        return Camera(
            // Negative, so a practice camera can never collide with a real OSM
            // id — those are positive — in any map or set keyed by id.
            id = -(abs(h) or 1L),
            location = location,
            tags = mapOf(
                PRACTICE_TAG to "yes",
                "direction" to "%.0f".format(java.util.Locale.US, bearing),
                "manufacturer" to "Practice (not real)",
            ),
        )
    }

    /** A stable 64-bit mix of two longs. Splitmix64's finaliser, twice. */
    private fun hash(a: Long, b: Long): Long {
        var x = a * -0x61c8864680b583ebL xor (b + -0x7ee3623a03d3c83fL)
        x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
        x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
        return x xor (x ushr 31)
    }

    /** The hash as a fraction in [0, 1). */
    private fun fraction(h: Long): Double = (h ushr 11).toDouble() / (1L shl 53).toDouble()

    /** Whether [camera] was invented by this rather than observed by anyone. */
    fun isPractice(camera: Camera): Boolean = camera.tags.containsKey(PRACTICE_TAG)

    /** Ceiling on one query, so an absurd box cannot hang the planner. */
    private const val MAX_GENERATED = 20_000
}
