package app.shunt.tesla

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the car is asked to navigate to, when it only accepts one destination and
 * resolves a string. Reported: "make sure that the final destination is actually
 * picked up by the car correctly. The correct address, correct business name,
 * not just a nearby point."
 */
class ShareAddressTest {

    private val client = TessieVehicleNavClient(
        http = okhttp3.OkHttpClient(),
        bearerToken = "t",
        vin = "V",
        baseUrl = "https://example.invalid",
    )

    @Test
    fun `a street address is sent as an address`() {
        assertEquals(
            "132 Birch Street, Kingsford, Michigan",
            client.postalAddress("132 Birch Street, Kingsford, Michigan"),
        )
    }

    @Test
    fun `a business with a town is sent as an address`() {
        // The whole point of preferring text: a coordinate cannot tell the car
        // which business it names, so the car shows a point instead.
        assertEquals(
            "Central Library, Springfield, IL",
            client.postalAddress("Central Library, Springfield, IL"),
        )
    }

    @Test
    fun `a bare name is not an address and falls back to the coordinate`() {
        // "Home" would be a worse search than a coordinate, not a better one.
        assertNull(client.postalAddress("Home"))
        assertNull(client.postalAddress("Work"))
        assertNull(client.postalAddress("Springfield"))
    }

    @Test
    fun `nothing to say means nothing is sent`() {
        assertNull(client.postalAddress(null))
        assertNull(client.postalAddress("   "))
        assertNull(client.postalAddress(","))
    }
}
