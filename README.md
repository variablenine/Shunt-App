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

### Solver internals and a known limitation

The camera-free search is **greedy exclusion**: request a route, buffer every
camera by a configurable radius (default 40 m), test the polyline for
intersection, add the offenders as HERE `avoid[areas]` boxes (max 20 per
request — only cameras on the current candidate line are excluded, nearest
first; never the whole dataset), and re-request until clean or out of rounds.

**Known limitation:** greedy exclusion can report infeasibility when a clean
route actually exists, because excluding camera A may push the route onto
camera B, and the search never backtracks. The minimum-exposure fallback
mitigates this by scoring *every* candidate seen — the initial alternatives
plus each exclusion round's routes — by distinct-camera count with ties
broken by travel time, but it is still a heuristic. The exact alternative —
delete camera-adjacent edges from a road graph and run shortest path — is
future work.

**Directionality** (`--strict-direction`, default **off**): some records
carry a facing direction, and with the flag on a camera only blocks when the
route's heading at the nearest point falls within a configurable arc of that
facing. It is off by default because the tags are crowdsourced, often absent,
and many units cover multiple directions. (Live-data note: the plain OSM
`direction` tag appears on ~97% of records, `camera:direction` on ~2%; both
are honored, preferring `camera:direction`.)

### Camera data

DeFlock CDN tiles (20°×20°, listed by `regions/index.json`) are fetched
lazily for the area a route needs, at most 5 in flight, and cached on disk
honoring the index's `expiration_utc` and cache-busting `?v=` version. If the
network fails, the cache serves stale; if there is no cache at all, a bundled
snapshot of the full dataset (~2.5 MB gzipped, refresh with
`tools/update-snapshot.sh`) answers offline. Every result reports its
freshness (`NETWORK` / `CACHE` / `STALE_CACHE` / `BUNDLED`).

### CLI harness

The solver runs from a laptop without a car or an emulator:

```
./gradlew :solver:installDist
./solver/build/install/solver/bin/solver solve \
    --from "45.8,-88.1" --to "some address" [--json] [--radius 40] [--strict-direction]
```

### Testing policy

No live network calls in unit tests. All parsers are tested against fixtures
recorded from the live services (2026-07-19): DeFlock index + tile slice, and
HERE Routing v8 + Geocoding v1 responses. HTTP behavior (caching, fallbacks,
concurrency caps, request formatting) runs against MockWebServer.

Two live-API findings worth knowing (both encoded in the client): HERE's
`avoid[areas]` separates areas with `|`, not `!` — `!` introduces per-area
*exceptions* and 400s when used as a separator; and the documented 20-box cap
is not currently enforced server-side, but the client enforces it locally
anyway.

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

Milestones M0–M4 complete:

- **M0** — Gradle multi-module scaffolding, license separation, key hygiene.
- **M1** (`:solver`) — camera source, route solver, waypoint extraction, and
  CLI, unit-tested and verified against the live DeFlock and HERE endpoints.
- **M2** (`:tesla`) — the `VehicleNavClient` interface, the scriptable
  `FakeVehicleNavClient`, and an abstract contract suite (shipped as test
  fixtures) that the production client must also pass. DI is wired so `:app`
  resolves the client from a single place (`AppContainer`); the production
  swap is one line.
- **M3** (`:app`) — the planning UI (below).
- **M4** (`:app`) — the drive monitor (below).

Hardening (M5) is next.

### M4 — drive monitor

Tapping Go starts a foreground service (`foregroundServiceType="location"`,
`FOREGROUND_SERVICE_LOCATION` + `ACCESS_FINE_LOCATION`) from the visible
activity — a while-in-use location service cannot be started from the
background, so this start-from-foreground path is deliberate. Only
`ACCESS_FINE_LOCATION` is requested; **never** `ACCESS_BACKGROUND_LOCATION`.
The service starts on Go and stops on arrival or cancel — nothing runs when
the user isn't driving, and no background work is scheduled.

**Waypoint advancement is the safety-critical part.** The vehicle treats
waypoints as stops, not pass-through points — it won't consider one visited
until parked there, and under driver assistance will actually stop. So as the
car approaches each waypoint the monitor calls `advanceTo` with the remaining
chain to drop the one being passed, fired **early** (a configurable ~18 s time
lead, with a distance floor for crawling traffic), not at the pin.

**Every failure is loud, and works offline.** Camera-approach warnings, an
`advanceTo` failure, and arrival all raise escalating haptics plus a local
notification — none need connectivity, which is the whole point of the
fallback. Camera warnings come from the cached camera set and degrade to
"Camera in 1,200 ft on your right" with nothing upstream available. Alerts are
meant to be felt and heard on a 2am rural drive, not read.

The decision logic (`DriveMonitorEngine`) is pure and exhaustively unit-
tested; the coordinator (`DriveMonitor`) is tested against
`FakeVehicleNavClient` and a fake alerter over scripted GPS, **including the
failure paths that can't be driven around** — an `advanceTo` failing while
approaching an unavoidable camera. The Android foreground service is a thin
shell over both.

### M3 — planning UI

Jetpack Compose. The flow is: enter destination → solver runs → result card
→ Go. Destination search uses HERE autosuggest (same vendor and key as
routing); Home and Work are one-tap favorites persisted across launches. The
map is MapLibre (never the Google Maps SDK), drawing the chosen route and, on
a minimum-exposure route, red markers for every unavoidable camera.

The **result card is the most important screen**: it states which outcome
occurred, the added time versus fastest, and the waypoint count — and for a
minimum-exposure fallback it makes unmissable that no camera-free route
exists, listing the count and coordinates of every camera the route passes.
Go stays tappable there: the user accepts the exposure knowingly.

Camera data is refreshed once on app open; **no background work is
scheduled**. When only the offline snapshot is available, the screen says so.

The presentation logic lives in `PlanViewModel`, decoupled from Compose,
HTTP, and the car behind small ports so the whole flow — including the Go
push and its retryable/permanent failure paths — is unit-tested against
`FakeVehicleNavClient`.

**Map basemap:** MapLibre renders the route and camera markers on a plain
background out of the box. To show a street basemap, set `map_style_url`
(`app/src/main/res/values/strings.xml`) to a Protomaps style JSON or a
self-hosted PMTiles style.

**Route origin:** the trip origin (and autosuggest bias) is the device's
last-known location when `ACCESS_FINE_LOCATION` is already granted, otherwise
the saved Home favorite. M3 requests no permissions; live location and the
permission prompt arrive with the M4 drive monitor.
