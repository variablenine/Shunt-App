# :tesla

The vehicle seam. This module holds the `VehicleNavClient` interface, its
result types, and `FakeVehicleNavClient` — all defined in M2.

Boundary rule: no networking, no credentials, no request-signing logic lives
in this repo's Part A. Everything downstream depends on the interface, never
on a concrete client. The production client is written separately and dropped
in via a one-line DI change.
