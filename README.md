# Shunt

Shunt is an open-source privacy-advocacy tool: an Android navigation app for
Tesla owners that plans driving routes minimizing exposure to automated
license plate readers (ALPRs), then pushes the resulting route to the
vehicle's built-in navigation.

ALPR locations are public infrastructure catalogued in OpenStreetMap by the
[DeFlock](https://deflock.me) community project. Choosing which public roads
to drive on is entirely legal; Shunt just makes the tradeoff visible.

## Routing policy

Camera-free is strongly preferred and worth almost any amount of extra time,
but ALPRs are deliberately sited at chokepoints, so for some trips no
camera-free route exists. The policy:

1. If a camera-free route exists, return the **fastest camera-free route**.
2. If none exists, **say so explicitly**, then return the route passing the
   **fewest distinct cameras**, ties broken by travel time.

The fallback is never silent: the result card states that no camera-free
route was found and how many cameras the chosen route passes, the map marks
each unavoidable camera, and the drive monitor warns on approach to each one.
This is modeled in the type system (`Clean` / `MinimumExposure` / `Failed`)
so it cannot be fumbled — `MinimumExposure` is an expected, navigable
outcome, not an error.

## Architecture

Everything ships in a single APK and runs on the phone. There is no server
component. The only outbound traffic is to third-party HTTP APIs (HERE, the
DeFlock CDN, and the vehicle service).

| Module    | Platform | Purpose |
|-----------|----------|---------|
| `:core`   | Pure JVM | Shared value types (`GeoPoint`, …) with zero dependencies |
| `:solver` | Pure JVM | Camera data source, HERE routing, the route solver, and a CLI harness |
| `:tesla`  | Pure JVM | The vehicle seam: `VehicleNavClient` interface + fake implementation |
| `:app`    | Android  | Jetpack Compose UI, drive monitor foreground service |

`:core`, `:solver`, and `:tesla` are Android-free so they can be unit-tested
and driven from a CLI on a laptop without an emulator. `:tesla` contains an
interface and a fake only — the production vehicle client is developed
separately and dropped in via a one-line DI change. No vehicle networking,
authentication, or request-signing code lives here.

## Setup

Requirements: JDK 17+, Android SDK (platform 35) for `:app`.

1. Copy `.env.example` to `.env` (or use `local.properties`) and set
   `HERE_API_KEY`. Real keys are gitignored — never commit them.
2. Build the JVM modules without any Android tooling:
   `./gradlew :core:build :solver:build :tesla:build`
3. Full build including the app: `./gradlew build`
4. Run the solver CLI: `./gradlew :solver:run --args="…"` (the `solve`
   command lands in M1).

## Licenses

- **Code:** [AGPL-3.0](LICENSE).
- **Camera data:** ODbL 1.0 — derived from OpenStreetMap via DeFlock. See
  [LICENSE-DATA.md](LICENSE-DATA.md) for attribution and share-alike terms.

## Status

Milestone M0 (repo scaffolding) — solver, vehicle seam, UI, and drive
monitor land in M1–M4. Known-limitation documentation for the solver's
greedy-exclusion search is added alongside M1.
