# Shunt — working handbook

This file is the project's memory. It is written so that **a person or an AI
agent with no prior context can pick Shunt up cold and continue the work**,
using nothing but this repository. If you are that person: read this file top to
bottom before touching code. Nothing here is optional background — the
constraints section in particular encodes decisions that have already been made,
tested, and paid for, and re-litigating them wastes weeks.

Keeping this file current is part of the job. See [Working
agreements](#working-agreements).

---

## 1. What Shunt is, and why it exists

Shunt is an open-source (AGPL-3.0) Android app that plans driving routes which
**minimize exposure to automated licence plate readers (ALPRs)** — the Flock
Safety and similar camera networks now blanketing public roads — and then
optionally pushes that route to a Tesla's built-in navigation.

Everything runs on the phone. **There is no backend, no account, and no API
key.** The app is useful without a car, too: planning, the on-map camera
display, and the drive-time alerts all work standalone.

### The point

ALPR networks record where ordinary people drive, continuously, without consent
and usually without their knowledge. The locations of those cameras are public
infrastructure, catalogued openly in OpenStreetMap by the
[DeFlock](https://deflock.me) community. Choosing which public road to drive on
is entirely legal. Shunt exists to make that choice *visible and actionable* —
to give a driver back the ability to not be tracked, which is a right people
should not have to be technical to exercise.

That is why it is free, open source, and AGPL: so anyone can use it, audit it,
fork it, and keep it alive independently of any one maintainer, company, or
account. **Assume this project may face pressure from surveillance vendors.**
Continuity is a design requirement, not a nicety. Concretely:

- The repository must stay self-sufficient — no private runbooks, no knowledge
  that exists only in one person's head or one AI session's context.
- Documentation is updated in the same commit as the code it describes.
- No dependency may require an account that can be revoked. (See §3.)

### Legal and ethical posture

Shunt routes around **publicly documented, publicly sited** cameras on public
roads. It does not defeat, disable, obscure, or interfere with any camera, and
it must never grow features that do. It does not help evade a specific stop,
pursuit, or investigation. The line is: *choosing where to drive is the
driver's business; tampering with infrastructure is not this project.* Keep
contributions on the right side of it.

---

## 2. Current status (keep this section honest)

Working and reasonably trusted:

- On-device camera-aware route planning (BRouter), with a Fastest → Balanced →
  Fewest-cameras chooser.
- The map: dark basemap, every known camera with its facing cone, tap for
  details, the route, live location.
- The drive monitor: camera-approach warnings, waypoint advancement, off-route
  detection, escalating haptics + notifications. Works fully offline.
- Destination search (Photon), favourites, long-press-map-to-route.

Working but **not proven on real vehicles**:

- Everything that talks to the car. See §6 and §7 — there are known, reproduced
  problems here from real driving.

Not done:

- No release has ever been published, deliberately. `versionCode` is still 1.
  Builds go out as CI artifacts until the app is genuinely functional in a car.

---

## 3. Hard constraints (do not violate without an explicit decision)

**Keyless and account-free.** No third-party service that needs an account, an
API key, or a payment card. This is a firm product decision: a key or card
requirement is a hard stop for the target user, and any service that can issue
credentials can revoke them. Current integrations:

| Need | Service | Why |
|---|---|---|
| Destination search | [Photon](https://photon.komoot.io) | Keyless OSM geocoder. Replaced HERE, which required a card. |
| Fallback search | Nominatim | Keyless OSM, used when Photon fails. |
| Routing | BRouter, vendored in `:brouter` | Fully offline, on-device, MIT. |
| Basemap | [OpenFreeMap](https://openfreemap.org) dark style | Keyless. |
| Camera data | DeFlock CDN | OSM-derived, ODbL. |
| Tesla charging sites | Overpass | Keyless OSM query. |

The **only** credentialed integration is the *optional* Tessie vehicle client,
using the user's own account, plus a far-future direct Tesla Fleet API.

**On-device and offline-first.** Once a region's map tile is cached, routing and
the drive monitor need no network at all. No background work, no analytics, no
telemetry, no account. Location permission is while-in-use only —
`ACCESS_BACKGROUND_LOCATION` is never requested.

**Search coverage is OSM-limited by design.** Being keyless means some
businesses that Google or HERE would find are simply missing from OpenStreetMap.
The chosen direction is *stay keyless, maximise OSM*: rank nearby results first
(`PhotonSearch.rankByProximity`) and add genuinely missing places to
OpenStreetMap so they become searchable for everyone, permanently. Do not
reintroduce a paid geocoder to paper over this.

**Never commit personal location data.** No real home or work coordinates, no
local town or business names, no recorded API responses from routes actually
driven (encoded polylines hide coordinates from a text search, so they are
especially dangerous). Every fixture, test, and doc uses **neutral placeholder
coordinates in the central US** (~39,-98 or ~33,-97) and generic names. Treat a
real-world location in a test as a bug. Before pushing, `git grep` for
coordinates in the region the maintainer actually lives.

**Safety.** Tesla/FSD support is a work in progress and must be labelled as
such wherever a user could rely on it (`TeslaWipWarning` — currently on the
result sheet, the driving sheet, and vehicle settings). Pushing a route to a car
that then drives itself is a safety-critical path; when in doubt, alert loudly
rather than fail silently.

---

## 4. Repository map

| Module | Platform | Purpose |
|---|---|---|
| `:core` | Pure JVM | Shared value types (`GeoPoint`), zero dependencies |
| `:brouter` | Pure JVM | Vendored BRouter engine (MIT), untouched `btools.*` |
| `:solver` | Pure JVM | Camera source, BRouter router/planner, waypoints, search, charging range |
| `:tesla` | Pure JVM | Vehicle seam: `VehicleNavClient`, fake, Tessie client, capability probe |
| `:app` | Android | Compose UI, drive-monitor foreground service, DI (`AppContainer`) |

Everything except `:app` is Android-free so it can be unit-tested on a laptop
with no emulator. **Keep it that way** — it is the reason the hard parts are
testable at all.

Key files, by the question they answer:

- *How is a route planned?* `solver/brouter/BrouterPlanner.kt` (orchestration),
  `solver/brouter/BrouterRouter.kt` (the engine calls and nogo shapes).
- *Where do the car's waypoints come from?* `solver/waypoints/WaypointExtractor.kt`
  (candidates from route shape) and `WaypointRefiner.kt` (which ones the car
  will actually honour).
- *What does a camera "see"?* `solver/brouter/CameraVision.kt`,
  indexed by `solver/brouter/CameraIndex.kt` over `solver/geo/SpatialIndex.kt`.
- *What happens while driving?* `app/drive/DriveMonitorEngine.kt` (pure decision
  logic), `app/drive/DriveMonitor.kt` (coordination), `DriveMonitorService.kt`
  (the Android shell).
- *How does charging work?* `app/drive/ChargeStopCoordinator.kt` +
  `ChargeStops.kt`, and `solver/charging/RangeCheck.kt`.
- *What reaches the car?* `tesla/TessieVehicleNavClient.kt`.
- *How is it all wired?* `app/di/AppContainer.kt` — the single DI seam.

---

## 5. How the whole thing fits together

1. **Search.** The user types a destination; `PhotonSearch` geocodes it,
   ranking results near the device first. Nominatim is the fallback.
2. **Plan.** `BrouterPlanner.plan()` takes origin → (stops) → destination:
   - Checks the offline BRouter tiles for the area are present, else prompts to
     download.
   - Fetches every camera in a generous bounding box around the trip.
   - Runs BRouter several times at different avoidance strengths, producing
     Fastest / Balanced / Fewest-cameras options.
   - Iterates to a fixed point: if a route detours outside the box cameras were
     fetched for, widen and re-plan. **A route must never be labelled against
     cameras the router was not given a chance to avoid** — that is the "it
     drove past an avoidable camera" bug, and it is why this loop exists.
   - Extracts and refines shaping pins (see §6).
3. **Choose.** The result sheet states, for each option, its time, distance, and
   every camera it passes — including coordinates when no camera-free route
   exists. The user accepts the trade-off knowingly.
4. **Go.** The chosen route is pushed to the car (§6), and a foreground
   drive-monitor service starts.
5. **Drive.** `DriveMonitorEngine` decides, from each GPS fix: warn about a
   camera ahead, advance to the next waypoint (early — the car treats waypoints
   as stops), announce arrival, or flag off-route. `DriveMonitor` acts on those
   decisions and handles charging checks.

---

## 6. What a Tesla actually does (hard-won, do not re-derive)

This section is the most expensive knowledge in the project. It came from real
cars, and most of it is counter-intuitive.

### The car only accepts one destination

`TessieVehicleNavClient.sendChain()` tries three things in order:

1. `navigation_waypoints_request` — the whole chain in one call. **Tesla's own
   vehicle-command proxy has no handler for it.** Feature-detected, cached.
2. `navigation_gps_request` — one call per point, exact `lat`/`lon`. The good
   path when it works.
3. `share` (Tessie's native command) — **the last resort, and what a 2021 Model
   3 requiring signed commands actually falls through to.** It takes one
   destination and no waypoints, so the route shape is lost. Returns
   `PushResult.DestinationOnly`.

Consequences that shape the whole design:

- On such a car, **whatever was last pushed is exactly what it is aiming at.**
  So an active route naming somewhere else, miles away, is the car's own
  inserted Supercharger — that is how charging detection works at all.
- To make a single-destination car follow a shaped route, Shunt **steers it pin
  by pin**: aim at the next pin, then move the aim along as the drive
  progresses. `PlanViewModel.pushForDriving()` decides this;
  `DrivePlan.steerByWaypoints` records it.

### A pin is a constraint, not a description

The car is never given our line — only one point at a time — and it then routes
*itself* there by its own fastest path. So a pin only constrains anything if the
quickest way to reach it already happens to be the road we want. Two ways that
fails, both putting the car past a camera the route exists to avoid:

- **A pin too far along a detour** — the car drives the fast road and joins the
  detour at its tail.
- **A pin past the fork** — the car does not turn back once committed.

`WaypointRefiner` therefore does not guess from geometry. For each leg the car
will drive it *routes the leg the way the car will* (no avoidance) and checks
whether that path enters a camera our route avoids; where it does, it puts a pin
just past the point the two paths diverge. This is empirical on purpose.

### It doesn't plan charging until it's put into drive

Reading at the moment Go is tapped always answers "no charging stop". The check
has to repeat once the car is actually moving. Hence two kinds of check in
`ChargeStopCoordinator`:

- **Free reads** — the car already holds the final destination, so asking costs
  nothing (no push, no redirect). These run every ~45 s under way and are what
  catch the charger appearing.
- **Re-asserts** — the car is aimed at a charger, and finding out whether it
  still intends to means pushing the destination again, which briefly redirects
  it. `ProbeWindow` rations these to moments well clear of the next waypoint and
  any camera, and the coordinator asserts at construction that the gate
  distances exceed what a car can cover while it settles.

A charging stop found this way becomes a normal camera-avoided leg; arriving at
it is a leg end, not the trip's end.

Separately, `solver/charging/RangeCheck.kt` warns *before* setting off when the
camera-avoiding detour outruns the battery — the car costs charging for the
direct route it was given and never sees our detour, so nothing else is in a
position to notice. `SuperchargerSource` (Overpass) backs the one-tap "add a
charging stop on the way", which just inserts an ordinary first stop.

---

## 7. Known problems from real-world driving

Reported by the maintainer after real drives. These are **observed behaviour**,
not theory. Keep this list current — mark items fixed with the commit that fixed
them, and add new observations as they come in. Detail lives in
[docs/field-notes.md](docs/field-notes.md).

1. **Charging re-route does not fire on long trips.** *Cause found, fix landed,
   unconfirmed on a real drive.* Sending a long-distance destination showed the
   route, but the car simply navigated to the final destination and Shunt never
   noticed the inserted Supercharger. The watch was switched off entirely
   whenever the trip was being steered pin by pin — which is most long trips.
   See `AppContainer.chargeStopCoordinator()` and the `steering` flag on
   `ChargeStopCoordinator`; a steered car is now probed the rationed way rather
   than not at all.
2. **A waypoint on the phone map can send the car somewhere else nearby.** Worse
   when the next waypoint is *behind* where the car has already driven. The
   maintainer notes this resembles the old Google-Maps-share-to-Tesla failure,
   where sharing too quickly navigated to the centre of a city, state, or the
   whole country — i.e. it smells like a *coarse or re-geocoded* location rather
   than the exact point. Suspicion: something is navigating to the centre of a
   road or area. **This is consistent with the `share` fallback in §6**, which
   hands the car a coordinate *string* that the car itself resolves.
3. **A closed road was routed onto, and leaving it was handled badly.**
   Re-planning must also respect the direction of travel while moving — an
   answer that requires driving backwards is not an answer.
4. **Long routes are far too slow to plan.** A 5-hour route can take ~5 minutes.
   That is unusable in the real world, and actively dangerous where mid-drive
   re-planning is involved. *Partly addressed* — see below; needs re-measuring
   on a real phone.

### Where planning time actually goes

Worth knowing before optimising anything here. Planning cost is dominated by one
thing: **how many times the whole road graph gets searched.** Each search is a
BRouter run over a `.rd5` tile set, and on a cross-state trip that is seconds
each. The passes are:

- *Deciding the route* — bounded and small. Up to four avoidance strengths
  (fastest, balanced, blocked, and a weighted fallback when blocked finds
  nothing), inside a loop that widens the camera area if a route detours outside
  it. Usually one iteration.
- *Refining the pins* — **was unbounded.** One routing pass per candidate pin
  per option, and a long camera-dense trip wants dozens. This is what made a
  five-hour route take minutes.

What has been done about it:

- The **fastest option is never refined.** It is by definition the road the car
  picks when left alone, so there is nothing to hold it onto.
- **Legs are memoised across options.** The options share an origin, a
  destination, and usually their early pins; the same leg used to be searched
  once per option.
- **Refinement has a time budget** (`REFINE_BUDGET_MILLIS`, 20 s; and
  `REPLAN_REFINE_BUDGET_MILLIS`, 4 s for anything planned while moving). When it
  runs out, planning settles for the pins it has. This is safe in a way that
  giving up on the route would not be: pins only *steer* a car that routes
  itself, so a route with fewer of them is still the route we planned, still
  labelled with the cameras it passes, and still warned about while driving.

Still open, and untested because it needs a real device: whether the
route-deciding passes alone are fast enough on a long trip. The candidates, in
descending order of expected value and risk, are running the independent
avoidance passes concurrently (BRouter thread-safety and phone memory are the
unknowns), and narrowing the nogo set from the trip's bounding box to a corridor
around the routes actually under consideration (which risks re-introducing the
"drove past an avoidable camera" bug — read §5 before trying it).

---

## 8. Build, test, and CI

```
./gradlew :core:build :solver:build :tesla:build   # non-Android, fast
./gradlew :solver:test :app:testDebugUnitTest       # JVM unit tests
./gradlew :app:compileDebugKotlin                   # app compiles
./gradlew :app:assembleRelease                      # R8 — catches keep-rule bugs
```

Unit tests make **no live network calls** — fixtures plus MockWebServer. CI also
runs an emulator smoke test (`LaunchSmokeTest`), which is the only thing that
catches launch crashes and Compose regressions.

### Testing when the Android SDK is unavailable

Some sandboxes (including Claude Code web sessions) block `dl.google.com` by
egress policy, so the Android Gradle Plugin cannot be resolved and *the root
build file fails to configure* — which breaks even the pure-JVM modules.

Work around it locally by temporarily reducing the root build to the Kotlin
plugins and dropping `include(":app")` from `settings.gradle.kts`, then running
`:core:test :solver:test :tesla:test`. **Restore both files before committing.**
`:solver` and `:tesla` hold essentially all the routing, camera, waypoint, and
vehicle logic, so this still covers the hard parts. `:app` is verified by CI.

Do not disable TLS verification or unset the proxy to get around this.

### CI workflows

- `.github/workflows/instrumented.yml` — emulator smoke test on every push and
  PR.
- `.github/workflows/release.yml` — builds a release APK on every `main` push
  (uploaded as the `shunt-apk` build artifact) and attaches it to a published
  GitHub Release when one is created. Signing uses the `ALPHA_KEYSTORE_BASE64`
  repo secret so updates install in place; without it the build falls back to a
  debug key and consecutive builds will not update over each other.

### Release / R8 gotchas (release builds only — debug skips minification)

- **Keep rules are load-bearing** (`app/proguard-rules.pro`):
  - `-keep class btools.** { *; }` — BRouter loads its physics model by
    reflection (`Class.forName("btools.router.KinematicModel")`); R8 stripping it
    makes every route fail with "No route found" **on installed builds only**.
  - kotlinx.serialization keep rules — every `@Serializable` model needs them.
- **Load bundled assets via `AssetManager`** (`context.assets.open(...)`), not
  `getResourceAsStream`, which is unreliable on Android. The BRouter profile and
  `lookups.dat` live in `app/src/main/assets/brouter/`.

---

## 9. Working agreements

**Branching: work goes straight to `main`.** No feature branches, no PRs for
routine work. Push to `main`, which triggers the release workflow and produces
an installable APK artifact. (Historically this project used a long-lived
`claude/*` branch and PRs; that is retired. If a change is genuinely risky or
wants review, a branch and PR is still fine — but it is the exception.)

**Every push to `main` should end with the maintainer getting a link to the
resulting build**, once CI is green. That loop is meant to be hands-off: make
the change, push, watch the run, send the link. If a run fails, fix it and push
again — do not leave a red `main`.

**No releases until the app is genuinely functional in a car.** Builds ship as
CI artifacts. When releases do start: *pre-releases* list each individual
change; *full releases* summarize changes since the last full release.

**Documentation is updated in the same commit as the code.** This is a hard
rule, not a preference — it is what makes the project survivable if the
maintainer loses access to a given AI account or tool. Specifically:

- This file (`CLAUDE.md`) — architecture, constraints, hard-won knowledge,
  status. If a change alters how something works, the description here changes
  with it.
- `README.md` — anything user- or contributor-facing.
- `docs/field-notes.md` — real-world observations and what they turned out to
  mean.
- §7 above — mark problems fixed, add new ones.

If you finish a change and have not touched documentation, check again whether
that is really true.

**Commit identity:** `Claude <noreply@anthropic.com>`. (GitHub's own merge
commits show as `noreply@github.com` / "Unverified" — expected, don't amend.)

**Never commit personal location data.** See §3. This is the one mistake that
cannot be undone by a follow-up commit.

---

## 10. Roadmap

Ordered roughly by what unblocks real use.

- **[high] Long-route planning performance** — see §7.4. The blocker for real
  driving.
- **[high] Charging re-route on long trips** — §7.1.
- **[high] Waypoint fidelity on the car** — §7.2. Getting a coarse location is
  worse than getting none, because it looks like it worked.
- **[high] Closed roads and direction-aware re-planning** — §7.3.
- **OSM coverage:** keep improving nearby-first ranking; add missing local
  places to OpenStreetMap so they become searchable for everyone.
- **Charging fine-tuning:** the range derate (`REAL_WORLD_FRACTION`), the
  charger corridor, and the probe cadences are first-pass numbers — worth
  revisiting against real drives.
- On-route arrow, and gray out the traveled portion of the route.
- Simplify the current-location dot to a solid pulsing dot (no accuracy halo).
- Fix one-way arrows pointing the wrong direction on the basemap.
- **[far future]** Direct Tesla Fleet API integration (connect a Tesla account,
  no Tessie) — only after everything else is fleshed out and tested.
