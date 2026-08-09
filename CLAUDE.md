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

Long-route planning was the blocker for real use and is now usable: measured on
a real phone, a 470 km trip went from 12 m 46 s to 41 s. See §7.4.

Working but **not proven on real vehicles**:

- Everything that talks to the car. See §6 and §7 — there are known, reproduced
  problems here from real driving. Several were diagnosed and fixed in August
  2026 but have not yet been confirmed on a drive.

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
  `RouteRequest.kt` is the seam between them — one parameter object, so adding
  something the router needs does not change the planner's signature and every
  fake in the suite with it.
- *Where do the car's waypoints come from?* `solver/waypoints/WaypointExtractor.kt`
  (candidates from route shape) and `WaypointRefiner.kt` (which ones the car
  will actually honour).
- *What does a camera "see"?* `solver/brouter/CameraVision.kt`,
  indexed by `solver/brouter/CameraIndex.kt` over `solver/geo/SpatialIndex.kt`.
- *What happens while driving?* `app/drive/DriveMonitorEngine.kt` (pure decision
  logic), `app/drive/DriveMonitor.kt` (coordination), `DriveMonitorService.kt`
  (the Android shell). `DriveMonitorBounds` holds the numbers the monitor is
  held to, public so tests assert the same values rather than a copy.
- *Why won't it route me back onto that road?* `RouteRequest.blocked`, filled by
  `DriveMonitor` from `solver/geo/stretchAhead`.
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

### 6.1 The driver always wins

The hardest lesson from real driving, and the one most likely to be undone by a
well-meaning change: **Shunt must never fight the driver for control of the
car.**

It did. The route wanted a road that was closed; the driver refused it; Shunt
re-planned, pushed the new route, and the car turned back toward the closure.
Every push overwrote what the driver had just set on the car's own screen, and
the loop only ended when they cancelled navigation in the app.

What makes this class of bug dangerous is that every individual step is
reasonable. Detecting off-route is right. Re-planning is right. Pushing the new
route is right. The failure is only visible from outside the loop — and from the
driver's seat it reads as the app refusing to let go.

So `DriveMonitor` **stands down**: more than `MAX_REPLANS_IN_WINDOW` re-plans
inside `REPLAN_WINDOW_MILLIS` and it stops commanding the car altogether — no
re-plans, no pushes, no waypoint advancement — and says so once. Camera warnings
continue, because they cost the car nothing.

Two properties worth preserving if you touch this:

- **Standing down is one-way for the drive.** Shunt cannot observe the road
  becoming passable, so nothing should silently re-earn control.
- **It covers every path that talks to the car**, not just re-planning. A
  monitor that stopped re-planning but kept advancing waypoints would still be
  fighting.

Anything added later that can push to the vehicle on a timer or a signal —
charging probes included — has to respect the same flag.

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
2. **A waypoint on the phone map can send the car somewhere else nearby.**
   *Two real contributing causes fixed; not yet confirmed which one the driver
   was seeing.* Worse when the next waypoint is *behind* where the car has
   already driven. The maintainer notes this resembles the old
   Google-Maps-share-to-Tesla failure, where sharing too quickly navigated to
   the centre of a city, state, or the whole country — i.e. a *coarse or
   re-geocoded* location rather than the exact point.

   Fixed so far: `ChargeStopCoordinator` restored steering by sending the whole
   remaining chain, which a single-destination car collapses to its **last**
   point, so the car was re-aimed at the trip's destination while the phone
   showed the next pin; and share coordinates could reach the car in scientific
   notation, which a consumer may fall back to parsing as a *place name*.

   Still possible, and the reason to keep this open: the `share` fallback in §6
   hands the car a string it resolves itself. `docs/field-notes.md` describes
   the read-back experiment that would settle it.
3. **A closed road was routed onto, and Shunt fought the driver over it.** The
   route wanted a closed road, so leaving it re-planned, pushed, and turned the
   car back — overriding what the driver had just set on the car's own screen,
   over and over, until they cancelled navigation in the app.

   **This is the most important thing on this list**, and not really a routing
   bug. See §6.1. All four parts are now addressed and none is confirmed on a
   drive: direction of travel on a re-plan, standing down, not routing back onto
   the abandoned stretch (`RouteRequest.blocked`), and the alert repetition —
   which turned out not to live in the alerting at all. A re-plan builds a fresh
   `DriveMonitorEngine`, and a fresh engine had no memory of which cameras it had
   already announced, so every camera still in range was warned about again.
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

**Temporary instrumentation is in the app right now.** The result sheet shows a
"Planned in …" block breaking the time down by stage, and splitting the routing
stage by what each search over the road graph was for (`fastest`, `balanced`,
`blocked`, `fewest (fallback)`, and `… (widen N)` for a re-search after the
camera area was widened). It exists because this project is developed in a
sandbox that cannot reach the BRouter tile CDN, so the only machine that can say
which pass is slow is a real phone. `PlanTimings`, its plumbing, and
`PlanningTimeBreakdown` in `ResultSheet.kt` all come out together once long-route
planning is fast enough that nobody is asking.

### What the numbers from a real phone showed

Measured on a real device, three trips (241 km / 394 km / 473 km), planning in
59 s / 1 m 49 s / 12 m 46 s. Two things came straight out of the breakdown:

- **The widen fired every single time.** The camera area was sized from the
  straight origin→destination box, and a real route always bulges outside it —
  so re-searching the whole graph was not a rare correction, it was guaranteed,
  and every trip paid for the entire routing phase twice.
- **Nogos are the cost, not distance.** On the 473 km trip the `fastest` pass —
  same graph, no cameras — took 5.1 s, while `balanced` took 2 m 25 s and
  `blocked` 2 m 44 s. About thirty times, for carrying the camera set. And the
  bounding box grows with the *square* of trip length, which is why the cost
  looked exponential in distance.

Both are addressed by planning the direct road first, with no cameras at all,
and using its shape:

1. That pass is the cheapest search there is, and its geometry is what the
   camera area is now drawn around — so the guaranteed widen is gone.
2. Cameras are filtered to a **corridor** around that spine rather than its
   bounding box. A long diagonal trip's box is mostly country no route would
   touch, and every camera in it was being checked against every link.

**The corridor is only safe because the fixed-point loop verifies it.** A route
that leaves the corridor has been planned against an incomplete camera set, so
it is not labelled — the spine grows to include what the routes actually did and
everything is planned again. `withinCorridor` derives its threshold from the
filter in `camerasAlong`; the two must move together, and the safety margin
exists so "the route stayed this far inside" implies "every camera that could
see it was in the set".

One trap worth knowing, because it was live for an hour: the spine is *sampled*
along the route, and sampling that keeps only existing vertices leaves gaps as
wide as the vertices are apart. A sparse line — a re-planned leg, a straight hop
— then drops every camera in the gap. `sampleSpine` walks segments rather than
keeping their ends, and a test holds it there.

**Measured again after those changes**, same phone: 241 km fell from 59 s to
10 s, ~400 km from 1 m 49 s to 25 s, and ~470 km from 12 m 46 s to 41 s. No
widen on any of them. That is the difference between unusable and usable.

**Still not usable at the very top end.** A trip of roughly 700 km through dense
metro country still ran long enough to be abandoned before it finished. Two
guards now bound it rather than fix it:

- **Every search has a real ceiling.** BRouter takes a `maxRunningTime` and
  Shunt passed **zero** for the life of the project, which BRouter reads as *no
  limit*. Checking the clock between passes — the obvious thing, and the first
  thing tried — bounds nothing at all when a single pass is what runs long,
  because a search is a tight CPU loop with no suspension point. Each pass now
  gets whatever is left of the budget, and the value handed over is never
  allowed to be zero.
- **Running out removes an option, never corrupts one.** A skipped pass is named
  in the breakdown, because a chooser that quietly comes back short reads as
  Shunt deciding there was no camera-free route.

**On running the passes concurrently** — the biggest remaining lever, and no
longer an unknown. Looked at properly: `btools.router.ProfileCache` is the only
mutable static state on the routing path, its entry points are `synchronized`,
and it carries a `profilesBusy` flag whose *only* purpose is to stop two threads
sharing one profile context. BRouter's own design anticipates concurrent
routing. The cache defaults to one slot, so a second concurrent pass re-parses
the profile rather than corrupting anything; `ProfileCache.setSize(n)` removes
even that cost.

So the blocker is not correctness, it is **memory**: each engine builds its own
`NodesCache` over the tiles, and on a cross-state trip that is large. Two
concurrent passes on a phone is plausible; three is not obviously so. Anyone
picking this up should cap the concurrency at two, and measure resident memory
on a real device before going further — an OOM mid-plan is a worse failure than
a slow plan.

What else has *not* been tried, in descending order of expected value and risk:
narrowing the corridor further (it is 60 km; tightening it risks re-introducing
widens on exactly the long detours that made the fewest-cameras option good).
Pin refinement is no longer the bottleneck, so doing it lazily — the
maintainer's suggestion, and the right shape for pins specifically — is worth
doing for *quality* on long routes rather than for speed. The candidates, in
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

Three workflows run on a `main` push, and all three have to be green before a
build is worth sending:

| Workflow | What it proves |
|---|---|
| `tests.yml` | The unit suites pass (`:core`, `:solver`, `:tesla`, `:app`). |
| `instrumented.yml` | The app launches on a real emulator — the only check that catches launch crashes and Compose regressions. |
| `release.yml` | It builds under R8, and produces the installable APK. |

`release.yml` writes a **run summary containing a direct download link** for the
APK, so the link to send is the run's summary page. Downloading a workflow
artifact requires being signed in to GitHub; that is GitHub's rule, and it is
the reason a rolling pre-release would be more convenient — deliberately not
done, because no release goes out until the app works in a car.

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
- **[high] Confirm the August 2026 vehicle fixes on a real drive.** Charging
  re-route, waypoint fidelity, standing down, and the abandoned-road block were
  all diagnosed from field reports and none has been seen working in a car.
- **Pin refinement, done lazily** — compute the next few pins rather than all of
  them. Not a speed problem any more; a quality one, since a long route's pins
  currently share one budget between them.
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
