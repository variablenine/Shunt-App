<p align="center">
  <img src="docs/brand/shunt-banner.png" alt="Shunt — route around the cameras" width="720">
</p>

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
camera-free route exists. Rather than force one answer, Shunt **routes on-device
and offers a few options** — from *Fastest* (may pass cameras) through
*Balanced* to *Fewest cameras* — each labelled with its time, distance, and the
cameras it passes, so the driver chooses their own trade-off.

Under the hood each camera is a weighted "nogo" shaped by its **field of view**:
a camera with a known facing watches a 180° wedge in front of it (so routes may
pass behind it), while an unknown-facing camera is treated as watching every
direction, with more distance. BRouter minimises time spent within any camera's
view in a single shortest-path pass — no greedy backtracking, no route
"technically" avoiding a camera while driving through its cone. Nothing is
silent: every option states the cameras it passes,
the map marks each one, and the drive monitor warns on approach while driving.
See [docs/brouter-spike.md](docs/brouter-spike.md) for the engine's evaluation.

## Architecture

Everything ships in a single APK and runs on the phone, **including routing
itself**. There is no server component. Outbound traffic is only to third-party
HTTP APIs — [Photon](https://photon.komoot.io) (a keyless, OpenStreetMap-based
geocoder) for destination search, the DeFlock CDN for camera data, the BRouter
CDN for offline map tiles (fetched once per region), and the vehicle service.
Once a region's tile is cached, routing needs no network at all.

| Module     | Platform | Purpose |
|------------|----------|---------|
| `:core`    | Pure JVM | Shared value types (`GeoPoint`, …) with zero dependencies |
| `:brouter` | Pure JVM | Vendored [BRouter](https://github.com/abrensch/brouter) routing engine (MIT), untouched |
| `:solver`  | Pure JVM | Camera data source, the native BRouter router/planner, keyless Photon search, and a CLI harness |
| `:tesla`   | Pure JVM | The vehicle seam: `VehicleNavClient` interface, fake, and the production Tessie client |
| `:app`     | Android  | Jetpack Compose UI, drive monitor foreground service |

`:core`, `:solver`, and `:tesla` are Android-free so they can be unit-tested
and driven from a CLI on a laptop without an emulator. Part A built `:tesla`
as an interface plus a fake only; Part B added the production
`TessieVehicleNavClient` behind the same interface, and the app resolves one
or the other from a single DI seam (`AppContainer`) — the fake unless a Tessie
token + VIN are configured. Everything downstream depends only on the
interface.

### Vehicle client (Tessie)

`TessieVehicleNavClient` talks to [Tessie](https://tessie.com), a paid drop-in
proxy for Tesla's Fleet API that performs Vehicle Command Protocol signing on
the caller's behalf (a 2021 Model 3 requires it) — so there is **no signing
logic in this repo**, only authenticated HTTP to the user's own vehicle with
their own bearer token.

- Waypoints push as a chain via `navigation_gps_request` (`{lat, lon, order}`,
  order 1 = replace trip for the first point, 3 = append stop for the rest).
  `pushRoute` sends the full chain; `advanceTo` re-sends the remaining chain.
- The single-call `navigation_waypoints_request` fast path is **feature-
  detected, not assumed**: Tesla's own proxy has no handler for it, so it's
  tried once, the outcome cached, and on a "not supported" response the client
  falls back to the per-point chain for good.
- The Fleet API's 30-commands-per-minute-per-vehicle cap is enforced by a
  sliding-window rate limiter.
- Every failure maps to `PushResult.Failed` with an accurate `retryable` flag
  (auth/bad-request permanent; timeouts/rate-limits/5xx/offline transient),
  and the client never throws or returns a false success — the drive monitor's
  alerting depends on that. It passes the same `VehicleNavClientContract` the
  fake does. Not exercised against a live vehicle (that would command a real
  car); verified against MockWebServer and the contract suite.

### Native routing (BRouter)

The app routes entirely on-device with the vendored BRouter engine (`:brouter`,
MIT). Each ALPR becomes a weighted "nogo" shaped by its field of view — a 180°
sector polygon in the direction a camera faces (routes may pass behind it), or a
larger full circle when the facing is unknown — and BRouter finds the
minimum-**exposure** route in a single shortest-path pass over the whole road
graph. A weight sweep produces the *Fastest → Balanced → Fewest cameras*
options; a hard nogo backs the fewest-cameras choice where a clear path exists.
Because it's one shortest-path pass, it **never reports infeasibility when a
route exists** — the greedy backtracking failure below simply can't happen.
Shunt's wrapper is `:solver`'s `BrouterRouter` / `BrouterPlanner`; waypoints for
the vehicle are extracted where the chosen route diverges from the fastest.

Offline **map tiles** — BRouter's 5°×5° `.rd5` cells, ~11 MB each — download
lazily per region from the BRouter CDN, cache on disk, and can be pre-pinned for
the home region. A trip whose tile isn't present yet prompts a one-time download
rather than silently going online (`BrouterTileSource`). See
[docs/brouter-spike.md](docs/brouter-spike.md) for the engine evaluation.

**Legacy HERE greedy solver (CLI only).** The original `RouteSolver` — greedy
`avoid[areas]` exclusion against HERE Routing v8 — remains as the laptop CLI
analysis harness (below), not in the app. Its known limitation is exactly what
BRouter avoids: greedy exclusion can report infeasibility when a clean route
exists, because excluding camera A pushes the route onto camera B and it never
backtracks. It also supports a `--strict-direction` mode using the crowdsourced
OSM facing tags (plain `direction` on ~97% of records, `camera:direction` on
~2%; both honored). The app instead uses omnidirectional nogo circles — more
conservative, which is the point: keep real distance from every camera.

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
HERE Routing v8, Geocoding v1, and Autosuggest v1 responses. HTTP behavior
(caching, fallbacks, concurrency caps, request formatting) runs against
MockWebServer. The safety-critical drive logic and the whole planning flow —
including offline and push-failure paths — run against `FakeVehicleNavClient`.

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
3. Full build including the app: `./gradlew build` (needs the Android SDK).
4. Run the solver CLI: `./gradlew :solver:installDist` then
   `./solver/build/install/solver/bin/solver solve --from … --to …`.

### Required API keys

**The app needs no API keys at all.** Destination search uses keyless Photon and
routing is native/offline, so a plain checkout builds and runs a fully functional
app with no account or card anywhere.

| Key | Used by | Where it comes from | Notes |
|-----|---------|---------------------|-------|
| `HERE_API_KEY` | The **legacy solver CLI** only (`:solver` harness) — never the app | HERE Access Manager → your app → **API Keys** → Create API key | Optional. Only needed to run the old HERE-based CLI; put it in `local.properties` (`HERE_API_KEY=…`) or the environment. A **refresh token** (a `reftkn:…` value) is *not* an API key and returns 401. |
| Tessie token | The production vehicle client | The user's own Tessie account | **Part B only** — not used anywhere in this repo. |

No key is committed, and a missing `HERE_API_KEY` doesn't fail the build: only
the legacy CLI needs it, and it prints a clear error when absent.

The **basemap** needs no key: the app ships with a dark
[OpenFreeMap](https://openfreemap.org) street style. Override `map_style_url`
(`app/src/main/res/values/strings.xml`) with any MapLibre style JSON (e.g. a
Protomaps or self-hosted PMTiles style) to change it; a blank value draws a
plain dark background.

### Releases

`.github/workflows/release.yml` builds the APK and attaches it to a published
GitHub Release (it also runs on demand from the Actions tab). Releases are
signed with a stable certificate decoded from the `ALPHA_KEYSTORE_BASE64` repo
secret, so each build carries the **same signature** and updates install in
place. Without the secret the build falls back to a debug key, so fresh
checkouts and CI still work — but consecutive debug-signed builds won't update
over each other. The APK contains no API keys and needs none — search and
routing are both keyless.

## Offline behavior

Shunt is built to keep working when the signal drops on a rural drive:

- **Camera data** degrades gracefully: fresh network → disk cache → stale
  cache → a bundled full-dataset snapshot shipped in the APK. Every result
  carries its `Freshness`, and the plan screen shows a banner when it's
  serving the offline snapshot.
- **Routing** runs fully on-device once the region's tile is cached — no
  connection needed to plan a camera-aware route. The one online moment is the
  first-time tile download for a new region, which the app prompts for
  explicitly. Destination **search** (keyless Photon) does need a connection;
  when it fails the app shows "Couldn't reach search" rather than an empty list.
- **The drive monitor** is the part that matters most offline, and it needs
  no connectivity at all: waypoint-approach timing, camera-approach warnings
  (from the cached camera set), escalating haptics, and local notifications
  are all computed on-device. A mid-drive `advanceTo` that fails because the
  car is unreachable raises a loud local alert instead of failing silently.

## Battery & privacy

- **No background work whatsoever.** There is no `WorkManager`, `JobScheduler`,
  or `AlarmManager`, and no periodic sync. Camera data refreshes once on app
  open. The only long-running component is the drive-monitor foreground
  service, which is started from the Go tap, runs `START_NOT_STICKY`, and
  stops itself on arrival or cancel — so nothing runs while you aren't driving.
- **Location is while-in-use only.** The app requests `ACCESS_FINE_LOCATION`
  and **never** `ACCESS_BACKGROUND_LOCATION`. GPS is sampled (~1 s, configurable)
  only for the duration of a monitored drive.
- **Network is frugal.** Camera tiles are fetched lazily for just the area a
  route touches, capped at 5 concurrent, and disk-cached by version so they
  aren't refetched; a single `OkHttpClient` pools connections.
- **No analytics, no telemetry, no account.** The only outbound traffic is to
  Photon (keyless search), the DeFlock CDN (cameras), the BRouter CDN (one-time
  offline tile downloads), and (via the Part B client) the user's own vehicle
  service. Routing and monitoring run entirely on the phone.

> **APK size note:** the debug APK is large (~100 MB) because it bundles the
> MapLibre native libraries for every ABI plus the offline camera snapshot.
> Release builds enable R8 minification and resource shrinking, and
> `-PslimAbi` restricts to arm64-v8a for a much smaller alpha APK. Shipping an
> Android App Bundle (or per-ABI splits) would go further, letting each device
> download only its own native library.

## Licenses

- **Code:** [AGPL-3.0](LICENSE).
- **Camera data:** ODbL 1.0 — derived from OpenStreetMap via DeFlock. See
  [LICENSE-DATA.md](LICENSE-DATA.md) for attribution and share-alike terms.

## Status

**Complete — Part A (M0–M5) and Part B.** The app plans camera-aware routes,
pushes them to the vehicle, and monitors the drive, with the production Tessie
client wired behind the vehicle seam.

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
- **M5** — hardening: offline behavior end to end, clearer error surfaces
  (offline routing/search messages, logged fallbacks), a battery review, and
  this README.
- **Part B** — the production `TessieVehicleNavClient`, dropped into the
  existing seam and satisfying the same contract tests as the fake.
- **Native routing (BRouter)** — on-device, offline camera-aware routing that
  replaces the HERE greedy path in the app: weighted-nogo minimum-exposure with
  a realistic standoff, a *Fastest → Balanced → Fewest cameras* chooser, and
  per-region offline tiles with a download prompt. Engine spike in
  [docs/brouter-spike.md](docs/brouter-spike.md).
- **Alpha polish** — a live dark basemap, a DeFlock-style camera display
  (facing cones + tap-for-info), a pulsing current-location dot, startup
  permission prompts, an on-screen crash reporter, a launcher icon, and CI
  (emulator smoke test + a stable-signed release build that updates in place).

The suite is **115 tests** across the four modules — no live network calls in
any of them — plus two on-device smoke tests that launch the app on an
emulator in CI (the check JVM tests can't do) to catch launch crashes and UI
regressions.

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
→ Go. Destination search uses keyless [Photon](https://photon.komoot.io)
(OpenStreetMap-based, no account or card); Home and Work are one-tap favorites
persisted across launches. The
map is MapLibre (never the Google Maps SDK). It draws the chosen route, a
pulsing blue dot at the current location, and — DeFlock-style — every known
ALPR in view as a dot with a cone showing which way it faces; tapping a camera
opens its make, operator, and facing. On a minimum-exposure route the
unavoidable cameras the route passes are highlighted in alarm red on top.
Viewport cameras load from the same cached DeFlock source the router uses,
fetched per-pan (debounced, and skipped when zoomed too far out).

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

**Map basemap:** ships with a dark street basemap from
[OpenFreeMap](https://openfreemap.org), which needs no key, configured via
`map_style_url` (`app/src/main/res/values/strings.xml`). Point it at any
MapLibre style JSON — a Protomaps style or a self-hosted PMTiles style — to
change it; a blank value falls back to a plain dark background.

**Route origin:** the trip origin (and autosuggest bias) is the device's
last-known location when `ACCESS_FINE_LOCATION` is granted, otherwise the
saved Home favorite. The app asks for location (and notifications on
Android 13+) on open — so the current-location dot and drive alerts work from
the first launch — prompting only for what isn't already granted and falling
back to Home if declined.
