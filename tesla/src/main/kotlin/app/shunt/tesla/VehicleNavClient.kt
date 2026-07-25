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
    /** The whole waypoint chain reached the vehicle. */
    data object Success : PushResult

    /**
     * The vehicle accepted only the final destination, not the shaped chain.
     *
     * Newer cars require Tesla's signed Vehicle Command Protocol, whose command
     * proxy implements `navigation_request` (a single shared destination) but
     * not the chain commands. So the car navigates there its own way — which may
     * run straight past cameras this route was built to avoid. Shunt's own
     * on-phone camera warnings still follow the planned route, but the car is
     * not being steered along it, and the user must be told.
     */
    data class DestinationOnly(val reason: String) : PushResult

    /**
     * [retryable] must be accurate: the drive monitor retries retryable
     * failures and alerts immediately on non-retryable ones.
     */
    data class Failed(val reason: String, val retryable: Boolean) : PushResult
}
