package app.shunt.tesla

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CommandRateLimiterTest {

    /** Drives the limiter with a manual clock; sleeping advances that clock. */
    private class FakeClock(var nowMillis: Long = 0)

    private fun limiter(clock: FakeClock, max: Int = 3, window: Long = 1000) =
        CommandRateLimiter(
            maxCommands = max,
            windowMillis = window,
            now = { clock.nowMillis },
            sleep = { millis -> clock.nowMillis += millis },
        )

    @Test
    fun `allows up to the cap without waiting`() = runTest {
        val clock = FakeClock()
        val limiter = limiter(clock)
        repeat(3) { limiter.acquire() }
        assertEquals(0, clock.nowMillis, "first 3 within the window should not sleep")
    }

    @Test
    fun `throttles the command over the cap until the oldest ages out`() = runTest {
        val clock = FakeClock()
        val limiter = limiter(clock, max = 3, window = 1000)
        repeat(3) { limiter.acquire() } // at t=0
        limiter.acquire() // 4th must wait a full window (oldest was at 0)
        assertEquals(1000, clock.nowMillis)
    }

    @Test
    fun `commands spread across time never block`() = runTest {
        val clock = FakeClock()
        val limiter = limiter(clock, max = 3, window = 1000)
        repeat(10) {
            limiter.acquire()
            clock.nowMillis += 400 // slower than 3-per-1000ms
        }
        // Never forced to sleep beyond our own spacing; total time is the spacing sum.
        assertTrue(clock.nowMillis <= 10 * 400, "limiter should not have added waits, was ${clock.nowMillis}")
    }
}
