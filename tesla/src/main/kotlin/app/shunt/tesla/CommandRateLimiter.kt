package app.shunt.tesla

import kotlinx.coroutines.delay

/**
 * Sliding-window rate limiter for the Fleet API's 30-commands-per-minute-per-
 * vehicle cap. Each vehicle command (one navigation point, or one waypoints
 * request) takes a slot. When the window is full, [acquire] suspends until the
 * oldest command ages out — commands are throttled, never dropped.
 *
 * Clock and sleep are injected so the throttling can be tested in virtual time.
 */
class CommandRateLimiter(
    private val maxCommands: Int = 30,
    private val windowMillis: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val timestamps = ArrayDeque<Long>()

    /** Block until a command slot is free, then consume it. */
    suspend fun acquire() {
        purge(now())
        if (timestamps.size >= maxCommands) {
            val waitMillis = windowMillis - (now() - timestamps.first())
            if (waitMillis > 0) sleep(waitMillis)
            purge(now())
        }
        timestamps.addLast(now())
    }

    private fun purge(current: Long) {
        while (timestamps.isNotEmpty() && current - timestamps.first() >= windowMillis) {
            timestamps.removeFirst()
        }
    }
}
