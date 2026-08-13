package app.shunt.solver.brouter

import btools.expressions.BExpressionContextWay
import btools.expressions.BExpressionMetaData
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the shipped routing profile will and will not drive on.
 *
 * These evaluate `car-vario.brf` directly against tag combinations, with no
 * tiles and no search — the same expression engine BRouter uses per link,
 * handed the tags by hand. That makes the profile's access rules testable in
 * CI, which they were not before: everything else here needs an `.rd5` tile
 * that is too large to commit.
 *
 * The subject is Shunt's one profile divergence: an emergency-only crossing of
 * a divided highway must never be offered as a way to turn round, while an
 * ordinary median U-turn — the Michigan left — must keep working. Those two
 * are the same manoeuvre to a driver and completely different things in law,
 * so the test states both halves.
 */
class CarProfileAccessTest {

    private val dir: File = Files.createTempDirectory("brouter-profile").toFile()

    private val way: BExpressionContextWay = run {
        BrouterAssets.install(dir) { name ->
            requireNotNull(javaClass.getResourceAsStream("/brouter-data/$name")) { "missing resource $name" }
        }
        val meta = BExpressionMetaData()
        val ctx = BExpressionContextWay(meta)
        meta.readMetaData(File(dir, "lookups.dat"))
        ctx.parseFile(File(dir, "car-vario.brf"), "global")
        ctx
    }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /**
     * The profile's own verdict on a way carrying [tags]: can a car be routed
     * along it at all?
     *
     * `caraccess` being false pins `maxspeed` to zero, which the profile turns
     * into a cost factor of 10000 — BRouter's "forbidden", the same value it
     * uses for a road the search may not enter.
     */
    private fun routable(vararg tags: Pair<String, String>): Boolean {
        val data = requireNotNull(way.createNewLookupData()) { "lookup data not frozen" }
        for ((key, value) in tags) way.addLookupValue(key, value, data)
        // The same two calls a search makes per link: pack the tags the way a
        // tile stores them, then evaluate in the forward direction.
        way.evaluate(false, way.encode(data))
        return way.costfactor < 9999f
    }

    @Test
    fun `an emergency-only median crossover is not routable`() {
        assertFalse(
            routable("highway" to "service", "service" to "emergency_access"),
            "the router may turn across a divided highway through an emergency-vehicle gap",
        )
        assertFalse(
            routable("highway" to "service", "service" to "crossover"),
            "a crossover service way is still routable",
        )
    }

    @Test
    fun `an explicit permission still wins`() {
        // If a mapper has said cars may use it, that is better evidence than
        // our inference from the service tag, and it is read first.
        assertTrue(
            routable("highway" to "service", "service" to "emergency_access", "motorcar" to "yes"),
            "an explicitly car-accessible service way was refused",
        )
        assertTrue(
            routable("highway" to "service", "service" to "emergency_access", "access" to "yes"),
            "an explicitly accessible service way was refused",
        )
    }

    @Test
    fun `a Michigan left still routes`() {
        // A public median U-turn is an ordinary road or a link, not an
        // emergency service way, and nothing here may touch it.
        assertTrue(routable("highway" to "primary_link"), "primary_link refused")
        assertTrue(routable("highway" to "secondary_link"), "secondary_link refused")
        assertTrue(routable("highway" to "trunk_link"), "trunk_link refused")
        assertTrue(routable("highway" to "unclassified"), "unclassified refused")
    }

    @Test
    fun `ordinary service roads are untouched`() {
        // Driveways, parking aisles and plain service roads are how a route
        // reaches a destination that is not on a public road, so excluding
        // service roads wholesale would have been the wrong fix.
        assertTrue(routable("highway" to "service"), "a bare service road was refused")
        assertTrue(routable("highway" to "service", "service" to "driveway"), "a driveway was refused")
        assertTrue(routable("highway" to "service", "service" to "parking_aisle"), "a parking aisle was refused")
    }

    @Test
    fun `access tags the profile already honoured still work`() {
        // Not a new rule — asserted so that a later edit to `caraccess` cannot
        // quietly drop them while the emergency case keeps passing.
        assertFalse(routable("highway" to "service", "access" to "no"), "access=no was routable")
        assertFalse(routable("highway" to "service", "access" to "private"), "access=private was routable")
        assertFalse(
            routable("highway" to "service", "motor_vehicle" to "emergency"),
            "motor_vehicle=emergency was routable",
        )
        assertTrue(routable("highway" to "residential"), "a residential street was refused")
    }
}
