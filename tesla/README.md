# :tesla

The vehicle seam. Pure JVM, no Android dependencies, no networking, no
credentials — the boundary is deliberate: everything downstream depends on
the `VehicleNavClient` interface, never on a concrete client.

## Contents

- **`VehicleNavClient`** — the interface. `pushRoute(waypoints)` sends a full
  route as an ordered waypoint chain; `advanceTo(remaining)` re-sends the
  not-yet-passed tail (the vehicle treats waypoints as stops, so the drive
  monitor drops each one before arrival).
- **`PushResult`** — `Success` or `Failed(reason, retryable)`. The
  `retryable` flag is load-bearing: the drive monitor retries retryable
  failures and alerts immediately on permanent ones, and must never be handed
  a false `Success`.
- **`FakeVehicleNavClient`** — a real deliverable, not a stub. Records every
  call, returns scripted results (FIFO), and can be configured to fail on the
  Nth call, fail intermittently at a seeded rate, or add per-call latency.
  This is what makes the drive monitor's failure paths testable without a car.
- **`VehicleNavClientContract`** (in `testFixtures`) — the behavioral contract
  every implementation must pass. The production client (built separately in
  Part B) extends this from its own test suite; publishing it as a test
  fixture is what lets the real client be held to the same bar as the fake.

## The boundary

No vehicle networking, authentication, or request-signing code lives in this
module in Part A. The production `VehicleNavClient` is dropped in later via a
one-line change in `:app`'s `AppContainer` — the single place the app
resolves the client. If implementing something here requires a credential or
an HTTP call, the boundary has been crossed.
