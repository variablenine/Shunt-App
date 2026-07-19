package app.shunt.tesla

import app.shunt.core.GeoPoint

/**
 * The vehicle seam. Everything downstream (drive monitor, UI) depends on
 * this interface and never on a concrete client; the production
 * implementation is developed separately and swapped in via a one-line DI
 * change (see AppContainer in :app).
 *
 * Contract (enforced by VehicleNavClientContract in testFixtures — every
 * implementation, fake or real, must pass it):
 *  - An empty waypoint list returns [PushResult.Failed] (retryable=false);
 *    it must not throw.
 *  - Failures are reported as [PushResult.Failed] with an accurate
 *    [PushResult.Failed.retryable] flag — never as exceptions, and never as
 *    a false Success. The drive monitor's alerting depends on this.
 */
interface VehicleNavClient {
    /** Push a full route as an ordered waypoint chain to the vehicle's navigation. */
    suspend fun pushRoute(waypoints: List<GeoPoint>): PushResult

    /**
     * Re-push the not-yet-passed tail of the chain, dropping waypoints the
     * vehicle is approaching. Called by the drive monitor ahead of each
     * waypoint (the vehicle treats waypoints as stops, so they must be
     * dropped before arrival).
     */
    suspend fun advanceTo(remaining: List<GeoPoint>): PushResult
}

sealed interface PushResult {
    data object Success : PushResult

    /**
     * [retryable] must be accurate: the drive monitor retries retryable
     * failures and alerts immediately on non-retryable ones.
     */
    data class Failed(val reason: String, val retryable: Boolean) : PushResult
}
