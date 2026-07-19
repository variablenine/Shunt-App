package app.shunt.tesla

import app.shunt.core.GeoPoint
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Scriptable in-memory [VehicleNavClient]. This is a real deliverable, not a
 * stub: it is the only way to exercise the drive monitor's failure paths
 * ("the push failed while approaching an unavoidable camera" cannot be
 * tested by driving around).
 *
 * Behaviors, in order of precedence per call:
 *  1. Explicitly enqueued results ([enqueueResult]) are returned first.
 *  2. Call numbers in [Behavior.failOnCalls] fail (1-based, counted across
 *     both methods).
 *  3. [Behavior.failureRate] fails that fraction of calls, deterministically
 *     via the seeded [Behavior.random].
 *  4. Otherwise Success.
 * Every call first waits [Behavior.latencyMillis] (virtual time under
 * kotlinx-coroutines runTest).
 */
class FakeVehicleNavClient(
    private val behavior: Behavior = Behavior(),
) : VehicleNavClient {

    data class Behavior(
        val latencyMillis: Long = 0,
        /** 1-based call numbers that fail, e.g. setOf(2) fails only the 2nd call. */
        val failOnCalls: Set<Int> = emptySet(),
        /** Fraction of calls [0.0, 1.0] that fail, drawn from [random]. */
        val failureRate: Double = 0.0,
        /** Seeded for reproducible intermittent failures. */
        val random: Random = Random(0),
        /** The failure returned by failOnCalls/failureRate. */
        val failure: PushResult.Failed = PushResult.Failed("scripted failure", retryable = true),
    )

    sealed interface Call {
        val waypoints: List<GeoPoint>

        data class PushRoute(override val waypoints: List<GeoPoint>) : Call
        data class AdvanceTo(override val waypoints: List<GeoPoint>) : Call
    }

    private val mutex = Mutex()
    private val recordedCalls = mutableListOf<Call>()
    private val scriptedResults = ArrayDeque<PushResult>()
    private var callCount = 0

    /** Every call made, in order. */
    suspend fun calls(): List<Call> = mutex.withLock { recordedCalls.toList() }

    /** Queue an explicit result for an upcoming call (FIFO, consumed once). */
    suspend fun enqueueResult(result: PushResult): Unit =
        mutex.withLock { scriptedResults.addLast(result) }

    suspend fun reset(): Unit = mutex.withLock {
        recordedCalls.clear()
        scriptedResults.clear()
        callCount = 0
    }

    override suspend fun pushRoute(waypoints: List<GeoPoint>): PushResult =
        handle(Call.PushRoute(waypoints))

    override suspend fun advanceTo(remaining: List<GeoPoint>): PushResult =
        handle(Call.AdvanceTo(remaining))

    private suspend fun handle(call: Call): PushResult {
        if (behavior.latencyMillis > 0) delay(behavior.latencyMillis)
        return mutex.withLock {
            recordedCalls += call
            callCount++
            when {
                call.waypoints.isEmpty() ->
                    PushResult.Failed("empty waypoint chain", retryable = false)
                scriptedResults.isNotEmpty() -> scriptedResults.removeFirst()
                callCount in behavior.failOnCalls -> behavior.failure
                behavior.failureRate > 0.0 &&
                    behavior.random.nextDouble() < behavior.failureRate -> behavior.failure
                else -> PushResult.Success
            }
        }
    }
}
