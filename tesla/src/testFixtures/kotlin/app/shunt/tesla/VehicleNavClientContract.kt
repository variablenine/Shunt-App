package app.shunt.tesla

import app.shunt.core.GeoPoint
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The behavioral contract every [VehicleNavClient] must satisfy — the fake
 * here, and the production client when it lands (extend this class from its
 * test suite; it is published via :tesla's test fixtures).
 *
 * Implementations provide clients in specific states. A client backed by a
 * real protocol should point at a scripted transport (e.g. MockWebServer),
 * never at a live vehicle.
 */
abstract class VehicleNavClientContract {

    /** A client whose next calls will succeed. */
    abstract fun healthyClient(): VehicleNavClient

    /** A client whose next call fails in a way that is worth retrying (e.g. transient network). */
    abstract fun retryablyFailingClient(): VehicleNavClient

    /** A client whose next call fails permanently (e.g. auth rejected). */
    abstract fun permanentlyFailingClient(): VehicleNavClient

    protected val chain = listOf(
        GeoPoint(44.5133, -88.0133),
        GeoPoint(44.7659, -88.0040),
        GeoPoint(45.0906, -87.6431),
    )

    @Test
    fun `pushRoute succeeds on a healthy client`() = runTest {
        assertEquals(PushResult.Success, healthyClient().pushRoute(chain))
    }

    @Test
    fun `advanceTo succeeds with the remaining tail`() = runTest {
        val client = healthyClient()
        client.pushRoute(chain)
        assertEquals(PushResult.Success, client.advanceTo(chain.drop(1)))
    }

    @Test
    fun `empty pushRoute fails without throwing and is not retryable`() = runTest {
        val result = healthyClient().pushRoute(emptyList())
        val failure = assertIs<PushResult.Failed>(result)
        assertEquals(false, failure.retryable)
    }

    @Test
    fun `empty advanceTo fails without throwing and is not retryable`() = runTest {
        val result = healthyClient().advanceTo(emptyList())
        val failure = assertIs<PushResult.Failed>(result)
        assertEquals(false, failure.retryable)
    }

    @Test
    fun `retryable failures surface as Failed with retryable true, never exceptions`() = runTest {
        val result = retryablyFailingClient().pushRoute(chain)
        val failure = assertIs<PushResult.Failed>(result)
        assertTrue(failure.retryable, "transient failures must be marked retryable")
        assertTrue(failure.reason.isNotBlank(), "failures must carry a reason")
    }

    @Test
    fun `permanent failures surface as Failed with retryable false, never exceptions`() = runTest {
        val result = permanentlyFailingClient().pushRoute(chain)
        val failure = assertIs<PushResult.Failed>(result)
        assertEquals(false, failure.retryable, "permanent failures must not be marked retryable")
        assertTrue(failure.reason.isNotBlank(), "failures must carry a reason")
    }
}
