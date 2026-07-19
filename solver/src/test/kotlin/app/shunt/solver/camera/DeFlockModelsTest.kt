package app.shunt.solver.camera

import app.shunt.solver.geo.BoundingBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parsers exercised against fixtures recorded from the live CDN (2026-07-19). */
class DeFlockModelsTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/deflock/$name")!!.readBytes().decodeToString()

    @Test
    fun `parses live index fixture`() {
        val index = parseDeFlockIndex(fixture("index.json"))
        assertEquals(20, index.tileSizeDegrees)
        assertTrue(index.regions.contains("40/-100"))
        assertTrue(index.tileUrl.contains("{lat}") && index.tileUrl.contains("{lon}"))
        assertEquals("1784406947", index.version)
        assertEquals(
            "https://cdn.deflock.me/regions/40/-100.json?v=1784406947",
            index.urlForTile(TileKey(40, -100)),
        )
    }

    @Test
    fun `parses live tile fixture`() {
        val records = parseDeFlockTile(fixture("tile-40_-100-slice.json"))
        assertEquals(127, records.size)
        val cameras = records.map { it.toCamera() }
        assertTrue(cameras.all { it.id > 0 })
        // Live data carries `direction` on nearly every record.
        assertTrue(cameras.count { it.directionDegrees != null } > 100)
    }

    @Test
    fun `tile keys covering a bbox`() {
        // Straddles the -100/-80 column boundary
        val keys = TileKey.covering(BoundingBox(44.0, -101.0, 46.0, -79.0), 20)
        assertEquals(listOf(TileKey(40, -120), TileKey(40, -100), TileKey(40, -80)), keys)
    }

    @Test
    fun `tile key for negative coordinates floors south-west`() {
        val keys = TileKey.covering(BoundingBox(-1.0, -1.0, 1.0, 1.0), 20)
        assertEquals(
            setOf(TileKey(-20, -20), TileKey(-20, 0), TileKey(0, -20), TileKey(0, 0)),
            keys.toSet(),
        )
    }

    @Test
    fun `direction parsing handles numeric cardinal and junk`() {
        assertEquals(278.0, Camera.parseDirection("278"))
        assertEquals(45.0, Camera.parseDirection("NE"))
        assertEquals(0.0, Camera.parseDirection("360"))
        assertNull(Camera.parseDirection("140;320"))
        assertNull(Camera.parseDirection(""))
    }

    @Test
    fun `camera prefers camera-direction over direction`() {
        val camera = Camera(1, app.shunt.core.GeoPoint(45.0, -88.0),
            mapOf("direction" to "90", "camera:direction" to "180"))
        assertEquals(180.0, camera.directionDegrees)
    }
}
