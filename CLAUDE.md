# Shunt — working notes for Claude

Shunt is an open-source (AGPL-3.0) Android app (Jetpack Compose) that plans
driving routes minimizing exposure to ALPR / Flock Safety cameras, then pushes
the route to a Tesla. Everything runs on the phone — there is **no backend**.
It's useful to non-Tesla drivers too (plan + on-map camera display + drive
alerts work without a car).

## Core philosophy (hard constraints)

- **Keyless and account-free.** No third-party API that requires an account,
  API key, or payment card. This is a firm product decision — a key/card
  requirement is a hard stop for the target user. Concretely:
  - **Destination search → Photon** (`photon.komoot.io`), keyless OpenStreetMap
    geocoder. (Replaced HERE, which needed a card.)
  - **Routing → BRouter**, vendored, fully offline/on-device.
  - **Basemap → OpenFreeMap** dark style, keyless.
  - **Camera data → DeFlock CDN** (OSM/ODbL).
  - The only credentialed integration is the *optional* Tessie vehicle client
    (the user's own account), and a far-future direct Tesla Fleet API.
- **On-device / offline-first.** Routing and the drive monitor need no network
  once a region's tile is cached. No background work, no analytics, no telemetry.
- **Search coverage is OSM-limited by design.** Because we're keyless, some
  businesses HERE/Google had (e.g. a specific supper club) may be missing from
  OpenStreetMap. The chosen direction is **"stay keyless, maximize OSM"**: rank
  nearby OSM results first (done — `PhotonSearch.rankByProximity`) and add
  genuinely-missing places to OpenStreetMap rather than reintroducing a paid
  geocoder.
- **Safety.** Tesla/FSD compatibility is a **work in progress**. Before any
  real-world FSD testing, the app must show prominent warnings that navigation
  is functional but the Tesla-push/FSD portion is unproven — use extra caution.

## Modules

| Module | Platform | Purpose |
|--------|----------|---------|
| `:core` | Pure JVM | Shared value types (`GeoPoint`), zero deps |
| `:brouter` | Pure JVM | Vendored BRouter engine (MIT), untouched `btools.*` |
| `:solver` | Pure JVM | Camera source (DeFlock), BRouter router/planner, Photon search, geo helpers, CLI |
| `:tesla` | Pure JVM | Vehicle seam: `VehicleNavClient`, fake, Tessie client |
| `:app` | Android | Compose UI, drive-monitor foreground service, DI (`AppContainer`) |

Non-UI stack is Android-free so it's unit-testable without an emulator.

## Conventions

- **Dev branch:** `claude/new-session-aqxbbn`. After a PR merges, restart it
  from the latest `main` (`git checkout -B <branch> origin/main`); never stack
  new work on already-merged history.
- **Open PRs via the GitHub API/tools, not the web button.** The branch name is
  reused across many PRs, which confuses GitHub's "create PR" shortcut into
  linking an old PR. Creating via API sidesteps it.
- **Changelog policy:** *pre-releases* list **each individual change**; *full
  (non-pre) releases* summarize the changes **since the last full release**.
- **Commit identity:** `Claude <noreply@anthropic.com>`. (GitHub's own merge
  commits show as `noreply@github.com` / "Unverified" — that's expected and not
  something to amend.)
- Don't commit personal location data (home coords, town names) — scrub tests
  and docs to neutral coordinates.

## Release / R8 gotchas (release builds only — debug skips minification)

- **Keep rules are load-bearing** (`app/proguard-rules.pro`):
  - `-keep class btools.** { *; }` — BRouter loads its physics model by
    reflection (`Class.forName("btools.router.KinematicModel")`); R8 stripping
    it makes every route fail with "No route found" on installed builds only.
  - kotlinx.serialization keep rules — every `@Serializable` model relies on them.
- **Load bundled assets via `AssetManager`** (`context.assets.open(...)`), not
  `getResourceAsStream` (unreliable on Android). BRouter profile + `lookups.dat`
  live in `app/src/main/assets/brouter/`.
- Release signing uses the `ALPHA_KEYSTORE_BASE64` repo secret so updates
  install in place.

## Build & test

```
./gradlew :core:build :solver:build :tesla:build   # non-Android, fast
./gradlew :solver:test :app:testDebugUnitTest       # JVM unit tests
./gradlew :app:compileDebugKotlin                   # app compiles
```
Unit tests make **no live network calls** (fixtures + MockWebServer). CI also
runs an emulator smoke test (`LaunchSmokeTest`).

## Roadmap (open items)

- **[high] FSD/Tesla "work in progress" safety warnings** everywhere — required
  before real-world Tesla/FSD testing.
- **OSM coverage:** keep improving nearby-first ranking; add missing local
  places to OpenStreetMap so they become searchable (permanent, community win).
- On-route arrow + gray out the traveled portion of the route.
- Simplify the current-location dot to a solid pulsing dot (no accuracy halo).
- Tap-and-hold on the map to navigate (Google-Maps style).
- Fix one-way arrows pointing the wrong direction on the basemap.
- **[far future]** Direct Tesla Fleet API integration (connect a Tesla account,
  no Tessie) — only after everything else is fleshed out and tested.
