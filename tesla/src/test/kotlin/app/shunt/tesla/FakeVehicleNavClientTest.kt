package app.shunt.tesla

import app.shunt.core.GeoPoint
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FakeVehicleNavClientTest {

    private val a = GeoPoint(44.5, -88.0)
    private val b = GeoPoint(44.6, -88.1)
    private val c = GeoPoint(44.7, -88.2)

    @Test
    fun `records every call in order with arguments`() = runTest {
        val fake = FakeVehicleNavClient()
        fake.pushRoute(listOf(a, b, c))
        fake.advanceTo(listOf(b, c))
        fake.advanceTo(listOf(c))
        assertEquals(
            listOf<FakeVehicleNavClient.Call>(
                FakeVehicleNavClient.Call.PushRoute(listOf(a, b, c)),
                FakeVehicleNavClient.Call.AdvanceTo(listOf(b, c)),
                FakeVehicleNavClient.Call.AdvanceTo(listOf(c)),
            ),
            fake.calls(),
        )
    }

    @Test
    fun `scripted results are consumed in order then behavior resumes`() = runTest {
        val fake = FakeVehicleNavClient()
        fake.enqueueResult(PushResult.Failed("vehicle asleep", retryable = true))
        fake.enqueueResult(PushResult.Success)
        assertEquals(PushResult.Failed("vehicle asleep", true), fake.pushRoute(listOf(a)))
        assertEquals(PushResult.Success, fake.pushRoute(listOf(a)))
        assertEquals(PushResult.Success, fake.pushRoute(listOf(a)))
    }

    @Test
    fun `fails on exactly the configured call numbers across both methods`() = runTest {
        val fake = FakeVehicleNavClient(FakeVehicleNavClient.Behavior(failOnCalls = setOf(2, 4)))
        assertEquals(PushResult.Success, fake.pushRoute(listOf(a, b)))     // 1
        assertIs<PushResult.Failed>(fake.advanceTo(listOf(b)))             // 2
        assertEquals(PushResult.Success, fake.advanceTo(listOf(b)))        // 3
        assertIs<PushResult.Failed>(fake.pushRoute(listOf(a)))             // 4
        assertEquals(PushResult.Success, fake.pushRoute(listOf(a)))        // 5
    }

    @Test
    fun `intermittent failure rate is deterministic under a fixed seed`() = runTest {
        suspend fun outcomes(): List<Boolean> {
            val fake = FakeVehicleNavClient(
                FakeVehicleNavClient.Behavior(failureRate = 0.5, random = Random(42)),
            )
            return (1..20).map { fake.pushRoute(listOf(a)) is PushResult.Failed }
        }
        val first = outcomes()
        assertEquals(first, outcomes(), "same seed must reproduce the same failures")
        assertTrue(first.any { it } && first.any { !it }, "rate 0.5 should mix outcomes")
    }

    @Test
    fun `full failure rate always fails and zero never does`() = runTest {
        val always = FakeVehicleNavClient(FakeVehicleNavClient.Behavior(failureRate = 1.0))
        val never = FakeVehicleNavClient(FakeVehicleNavClient.Behavior(failureRate = 0.0))
        repeat(5) {
            assertIs<PushResult.Failed>(always.pushRoute(listOf(a)))
            assertEquals(PushResult.Success, never.pushRoute(listOf(a)))
        }
    }

    @Test
    fun `latency is applied per call`() = runTest {
        val fake = FakeVehicleNavClient(FakeVehicleNavClient.Behavior(latencyMillis = 750))
        val start = testScheduler.currentTime
        fake.pushRoute(listOf(a))
        fake.advanceTo(listOf(a))
        assertEquals(1500, testScheduler.currentTime - start)
    }

    @Test
    fun `concurrent calls are all recorded`() = runTest {
        val fake = FakeVehicleNavClient(FakeVehicleNavClient.Behavior(latencyMillis = 10))
        (1..50).map { n ->
            async { fake.advanceTo(listOf(GeoPoint(44.0 + n * 0.001, -88.0))) }
        }.awaitAll()
        assertEquals(50, fake.calls().size)
    }

    @Test
    fun `reset clears calls scripts and counters`() = runTest {
        val fake = FakeVehicleNavClient(FakeVehicleNavClient.Behavior(failOnCalls = setOf(1)))
        fake.enqueueResult(PushResult.Success)
        fake.pushRoute(listOf(a))
        fake.reset()
        assertTrue(fake.calls().isEmpty())
        // Call counter restarted: the configured first-call failure applies again.
        assertIs<PushResult.Failed>(fake.pushRoute(listOf(a)))
    }
}

/** The fake must satisfy the same contract the production client will. */
class FakeVehicleNavClientContractTest : VehicleNavClientContract() {
    override fun healthyClient(): VehicleNavClient = FakeVehicleNavClient()

    override fun retryablyFailingClient(): VehicleNavClient =
        FakeVehicleNavClient(
            FakeVehicleNavClient.Behavior(
                failOnCalls = setOf(1),
                failure = PushResult.Failed("transient: vehicle unreachable", retryable = true),
            ),
        )

    override fun permanentlyFailingClient(): VehicleNavClient =
        FakeVehicleNavClient(
            FakeVehicleNavClient.Behavior(
                failOnCalls = setOf(1),
                failure = PushResult.Failed("auth rejected", retryable = false),
            ),
        )
}
