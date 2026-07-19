package app.shunt.app.di

import app.shunt.tesla.FakeVehicleNavClient
import app.shunt.tesla.VehicleNavClient

/**
 * The single place the app resolves its dependencies. Everything downstream
 * (drive monitor, view models) takes [VehicleNavClient] from here and never
 * constructs a concrete client itself.
 *
 * Swapping the fake for the production client is the ONE line marked below —
 * this is the seam Part B drops into. Nothing else in the app changes.
 */
class AppContainer {

    val vehicleNavClient: VehicleNavClient by lazy {
        // --- one-line vehicle-client swap ---
        FakeVehicleNavClient()
        // e.g. TessieVehicleNavClient(http, tokenProvider, vin)  // Part B
        // -------------------------------------
    }
}
