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
  detection, escalating haptics, **spoken alerts** and notifications. Works
  fully offline — the speech is Android's on-device TTS, no account or key
  (`SpokenAlerts`), routed as navigation guidance so it reaches car audio and
  ducks music.
- A live "what is Shunt doing" line on the driving sheet (`DriveActivity`):
  sending waypoint *n* of *m*, asking the car about charging, re-planning,
  stood down. All of this happened before and was invisible unless it failed.
- Destination search (Photon), favourites, long-press-map-to-route.

Long-route planning was the blocker for real use and is now solved. A 489 km trip
into dense metro — the one that used to be abandoned after twenty minutes —
**planned on a real phone in 1 m 06 s and returned a genuinely camera-free
route**, verified against the map. Since that measurement the two avoidance
passes run concurrently, which takes the same 615 km benchmark trip from 57 s to
43 s; that part is from the repository's benchmark and wants confirming on a
phone. See §7.4.

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
| `:brouter` | Pure JVM | Vendored BRouter engine (MIT). Upstream except `NogoIndex.java` and one loop in `RoutingContext.calcDistance` — see §7 |
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

### How densely to pin, which is not one number

Two constants decide how firmly a route is held, and both were single values
tuned for open road. In a city they were wrong in the same direction, and the
symptom was the one reported from real driving: *in denser areas the car strays
from the pin and passes a camera before there is time to warn.*

- **`MIN_PIN_SPACING_METERS` (800 m), how far apart pins may sit.** The real
  constraint behind it is the drive monitor, which advances to the next pin once
  the car is within `max(150 m, speed × 18 s)` of the current one — so two pins
  closer than that lead distance are one constraint, not two, because the second
  is advanced past before the car aims at it. At highway speed that lead is
  500-550 m and 800 m is right. At city speed it is about 230 m, and 800 m was
  throwing away pins that would have worked. Worse, it was throwing away *the
  refiner's* pins specifically: those are placed `PAST_FORK_METERS` past a fork,
  which is inside 800 m by construction. The most carefully computed pin on the
  route was the one most likely to be discarded, exactly where the roads made it
  matter.
- **`PAST_FORK_METERS` (250 m), how far past a fork the pin goes.** It has to be
  far enough that the turn is committed and near enough that no other road
  reaches it first. Those pull apart in a grid: commitment happens almost at
  once, while 250 m can be past the next junction or two, giving the car choices
  it can make and still arrive at the pin.

**The rule that governs both is the drive monitor's lead distance**, and missing
that is how these numbers came to be wrong more than once. The monitor advances
to the next pin as soon as the car is within `max(150 m, speed × 18 s)` of the
current one. So:

- A pin **closer to a fork than the lead** is abandoned *before the car reaches
  the fork*, and the turn it exists to force is not forced.
- Two pins **closer together than the lead** are one constraint, because the
  second is advanced past before the car ever aims at it.
- A spacing floor **wider than the fork distance** throws away the refiner's own
  pins, which sit exactly that far past a fork.

Which brackets everything: `lead ≤ spacing ≤ past-fork`. Both pairs are now the
lead distance at the speed that stretch is driven, plus margin — 600 m on open
road (lead at 70 mph is 563 m) and 250 m where it is dense (lead at 30 mph is
241 m), sliding between the two on **local camera density** (`CameraIndex.countWithin`
over `DENSITY_RADIUS_METERS`, fully tight by `DENSE_CAMERA_COUNT`). Camera count
is a proxy for junction density, and an honest one: a polyline says nothing about
the side streets leading off it, while ALPRs are sited where the traffic and the
junctions are.

`PAST_FORK_METERS` was **250 m for most of this project**, against a highway lead
of 563 m — so on any fast road the pin was dropped 313 m *before* the fork and
never constrained the turn at all. That is the "long stretches where the car
doesn't follow the route" report. A test in `:app` holds the whole relationship,
because only that module can see both the pin constants and `DriveMonitorConfig`.

Tightening is safe to try because **the refiner verifies rather than assumes**: a
pin placed too far along is caught next iteration, when the leg is re-routed and
the car still strays. Being advanced past too early is *not* caught, because the
leg looks clean — so the two errors are not symmetrical, and erring long is the
recoverable direction.

### A pin holds the route, not just the cameras

For most of this project the refiner asked one question of each leg: *does the
car's own path enter a camera we avoid?* On the first real drive that turned out
to be half a question — the car took a different road, one that happened to be
camera-free, so no pin was ever placed and the car drove somewhere the phone was
not showing. Everything downstream then misreads: the monitor calls it off-route,
camera warnings are computed for a line the car is not on, and a re-plan may
fire.

`needsPin` now asks both — the car's path enters an avoided camera, **or** it
leaves our line by more than `FORK_THRESHOLD_METERS`. `pruneIdlePins` uses the
same predicate negated, so insertion and removal agree and the phase settles on
the pins that hold the route and no others, which is what "no more, no less"
has to mean to be checkable.

Two things this cost, both worth knowing:

- **It walks each leg rather than checking its vertices.** A car path's vertices
  can all sit on the planned line while the road between two of them goes
  somewhere else entirely — a straight hop between junctions is two points, and
  everything that matters happens in between. Exactly the trap `sampleSpine`
  fell into (see §7); `straysFrom` samples at 50 m.
- **The phase got slower, so `REFINE_BUDGET_MILLIS` went from 20 s to 45 s.**
  Measured on the 615 km benchmark it converges in about 34 s. At 20 s it was
  cut off part-way, and the symptom was counter-intuitive: *more* pins than the
  converged answer, because the pruning that removes the redundant ones never
  ran. A truncated phase is not a smaller version of the right answer.

Converged, that trip carries 27 pins on the balanced option and 30 on
fewest-cameras — fewer than the 40-odd it used to produce, and a strictly
stronger guarantee, because now every leg between them has been driven the way
the car would drive it and checked against both conditions.

### Pins have to earn their place

Pins arrive from two places and only one of them checked its work.
`WaypointExtractor` adds them from the route's shape and from chords that clip a
camera — both geometric guesses — and the refiner adds them where the car
provably strays. Nothing then asked whether each was still needed, and the result
was reported from real use as *"pointless waypoints one after the other on the
same straight road"*.

`pruneIdlePins` runs the refiner's own test backwards: take a pin out, route the
leg it was splitting the way the car would, and keep it unless the car does
**both** of the things it was there for — stays clear of every camera the route
avoids, *and* still drives the line we planned, within `FORK_THRESHOLD_METERS`.

That second condition is load-bearing. On cameras alone, pruning strips a route
back to the few pins cameras strictly force and lets the car pick its own way
between them: camera-free, but not the route on the screen, and every divergence
is something the drive monitor then reports as off-route. Measured on the 615 km
trip, cameras-only pruning took the balanced option from 52 pins to 25; with the
route-following condition it settles at 27, and fewest-cameras at ~40.

This makes the whole phase self-correcting — earlier stages can be generous,
because anything they over-add is removed on evidence rather than by a rule about
spacing. **What it costs is a deeper reliance on BRouter modelling the car
correctly**, since a pin is now dropped on BRouter's word that the car would
follow the road anyway. That is the assumption to suspect first if a car strays
somewhere a pin used to sit.

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

### A waypoint must not be dropped before its turn

The monitor advances to the next pin once the car is within
`max(150 m, speed × 18 s)`, and that floor exists for crawling traffic. Sitting
at a red light in a turn lane, a little short of a pin just past the junction,
the car is inside the floor *and* stationary — so the pin was advanced past, and
the next one was reachable by carrying straight on. FSD moved to leave the turn
lane. **A pin abandoned before its turn is worse than no pin: it actively steers
the car the wrong way, at a junction, under driver assistance.**

`DriveMonitorEngine` now precomputes, for each pin, the last bend sharper than
`turnCommitDegrees` within `turnCommitLookbackMeters` before it, and will not
advance until the car is past that point. It does not delay the advance
otherwise — advancing early is still right, because the car treats a waypoint as
a *stop* and will slow for it. There is one safety valve: within
`arrivalRadiusMeters` it advances regardless, so a car that never registers as
past the commit point is not left aiming at a pin it is sitting on.

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

**Go waits for that reading before deciding how to drive the trip.** Steering pin
by pin is only safe when the trip does not need charging, and `rangeCheck` was
null both when no claim could be made *and* while the vehicle read was still in
flight — so tapping Go promptly on a long trip set off steering, the car never
planned a charge for the real route, and the whole charging path was skipped.
`checkingRange` distinguishes the two, and `onGo` waits on it for at most
`RANGE_WAIT_MILLIS`; timing out lands on the documented behaviour for an
unreadable car rather than on a new risk.

Separately, `solver/charging/RangeCheck.kt` warns *before* setting off when the
camera-avoiding detour outruns the battery — the car costs charging for the
direct route it was given and never sees our detour, so nothing else is in a
position to notice. `SuperchargerSource` (Overpass) backs the one-tap "add a
charging stop on the way", which just inserts an ordinary first stop.

**Know which range figure that works from.** Shunt reads Tesla's
`est_battery_range`, which the car computes from *recent consumption* — it is
already a real-world number, not the EPA rating. For most of this project a
further 0.75 "real world" derate was applied on top, which is double-counting,
and it produced a red "not enough range" on a trip the driver knew fitted: the
car's own estimate was 258 km against a 241 km route, and Shunt presented 178 km
of usable range. The estimate is now taken at face value
(`RANGE_TRUST_FRACTION`), and **the whole margin lives in `RESERVE_METERS`**, one
number instead of two compounding invisibly. If these warnings are ever pitched
wrongly, that reserve is the dial — and a warning that fires on trips that are
plainly fine is not conservative, it is one people learn to ignore.

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
4. **Long routes are far too slow to plan.** A 5-hour route can take ~5 minutes,
   and a 615 km trip into dense metro was abandoned after twenty. That is
   unusable in the real world, and actively dangerous where mid-drive re-planning
   is involved. *Believed fixed; wants confirming on a phone.* Four separate
   causes, found in this order and each hiding the next: a widen that fired every
   trip, a camera set filtered by bounding box, a nogo scan that was linear in
   the camera count, and finally pin selection — which had no ceiling at all and
   turned out to be five times the cost of routing. Same 615 km trip: 20 min+ →
   403 s → **72.8 s**. See below.

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

  **That budget covers the whole pin phase, and did not always.** Both halves —
  `WaypointExtractor` closing shortcuts, and `WaypointRefiner` routing legs —
  now share the one deadline, and every leg is routed under whatever is left of
  it. Before that the phase overran twenty seconds by seventeen times. If you add
  anything to this phase, hand it the same deadline; a clock checked only between
  units of work bounds nothing when one unit is what runs long.

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

The corridor's half-width (`CAMERA_CORRIDOR_METERS`) was the single biggest lever
there was, and narrowing it from 60 km to 15 km is what made a 489 km trip
plannable at all: the plain fastest search took 4.5 s and the same search
carrying the camera set took 42 s, two completely different search spaces at
near-identical cost, which is what identified the camera *set* rather than the
search as the expense. **`NogoIndex` then removed that relationship entirely, and
with it the reason to be narrow — so the corridor is back at 60 km.** See below
for how that reversed.

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

### The nogo scan, which turned out to be the whole thing

Measured against real tiles and the real camera dataset (see §8), on a 490 km
trip into dense metro:

| 490 km trip | full scan | indexed |
|---|---|---|
| whole call, 608 nogos (2 km corridor) | 221.7 s | 28.1 s |
| whole call, 1181 nogos (5 km corridor) | 422.3 s | 30.2 s |
| whole call, 2349 nogos (15 km corridor — the default) | **1036.5 s** | **36.1 s** |

Note the shape of the left column: cost grows with the nogo count, which is what
identified this loop. The right column barely moves, which is what a spatial
index is supposed to look like.

`RoutingContext.calcDistance` is called for **every link the search expands**,
and scanned the entire nogo list each time — O(links × nogos). That is fine for
BRouter's normal use, where a nogo is something a user drew by hand and there
are a handful. Shunt makes every camera a nogo, so a metro trip carries
thousands, and this single loop was essentially all of planning time.
`btools/router/NogoIndex.java` is a uniform grid over the nogo list, and the loop
now visits only the nogos that could possibly match.

**This is the one place `btools.*` diverges from upstream.** Keep it in mind when
updating BRouter: the change is one new file plus about eight lines in
`calcDistance`, both marked `SHUNT CHANGE`.

It is written to be answer-preserving rather than approximately right — a nogo
excluded by the grid would have failed the loop's own radius test without side
effects, and candidates come back in ascending index order so the loop visits
them exactly as before. That was **verified rather than assumed**, twice: the same real trip planned
with and without the index produced identical geometry point for point
(533.88 km, 5463 points, same fingerprint) at 608 nogos, and at the production
15 km corridor both produced the same three options to the metre — fastest
490.7 km / 53 cameras, balanced 531.4 km / 6, fewest 545.6 km / 0.

With it, the trip that could not be planned at all now returns all three options
in about 36 s of routing, including a genuinely camera-free route.

### What the index made backwards

Two decisions were correct when the camera set *was* the cost, and wrong the
moment it stopped being. Both are worth knowing about, because the same reasoning
will go stale again the next time something here gets faster.

**The corridor is back at 60 km.** At 15 km a real 615 km trip still escaped the
corridor and forced a widen — which is not a slightly wider camera set, it is the
whole chooser run a second time out of the same plan budget, and in practice the
second round timed out and the driver was shown the fastest road alone. That is
exactly the failure the breakdown exists to make visible, and the user saw it.
Measured on the 490 km trip with the index in place:

| corridor | cameras | passes |
|---|---|---|
| 15 km | 2,349 | 36.1 s |
| 30 km | 3,580 | 37.5 s |
| 60 km | 5,395 | 40.7 s |

Four seconds for four times the cameras. Against a whole extra round, that is not
a close call. `CAMERA_CORRIDOR_METERS` now matches `ROUTE_BBOX_MARGIN_METERS`
again — cameras are considered anywhere a detour could plausibly go. Either width
is *safe*, which is what makes this a pure cost question: the fixed-point loop
verifies the corridor, so a route that leaves it is never labelled.

**Choosing the pins was then the whole of planning time**, and had been hiding
behind routing. On that 615 km trip the breakdown read: routing 52 s, *pins
349 s* — against a phase budget of 20 s. Two separate bugs, both the same shape
as passing zero to BRouter's own timeout:

- `WaypointExtractor.pinAgainstShortcuts` asked *every* avoided camera whether it
  saw a chord, and a chord early in that loop spans most of the trip while a
  camera walks a line at ten-metre samples. So one check was (trip length ÷ 10 m)
  × cameras, and there is a check per insertion. It goes through `CameraIndex`
  now, like everything else that asks that question.
- Neither extraction nor the refiner's leg routing carried a ceiling. The refiner
  checks the clock *between* legs, which bounds nothing when a single leg is what
  runs long, and each leg fell back to the router's default — a whole pass
  budget, per leg. Legs now get what is left of the refinement budget, and
  extraction shares the same deadline rather than running unbounded.

Same trip after both: **72.8 s total** (routing 52.3, pins 20.2 — the budget,
exactly), all three options, and *more* pins than before (49 and 37, against 33
and 17) because the time is no longer wasted. That is the trip the maintainer
abandoned after twenty minutes.

**On running the passes concurrently** — a lever, and no longer an unknown. Looked at properly: `btools.router.ProfileCache` is the only
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

### Why avoidance costs what it does, and what that rules out

Worth settling before anyone optimises here again, because it eliminates the
obvious idea. On a real phone `fastest` takes 3.4 s and `blocked` 22.0 s over the
same graph. Two explanations, opposite responses: if the *nogo lookup* is the
cost there is headroom left in indexing; if it is the *search space* then no
amount of indexing touches it.

Separated by displacing every camera six degrees north — identical nogo count, so
identical per-link work, but nothing near any road the trip would use:

| 615 km trip, 5,388 nogos | real positions | displaced |
|---|---|---|
| `fastest` | 3.6 s | 3.5 s |
| `blocked` | 16.0 s | 4.0 s |
| `balanced` | 14.1 s | 4.1 s |

So the lookup is about half a second and the other twelve are the search
genuinely exploring more roads and settling on a longer one. **Indexing is
finished as a lever.** `NogoIndex` did the available work and there is no second
one to find.

That leaves overlapping the passes, which is now done — `blocked` and `balanced`
are independent, and `maxConcurrentPasses` runs them at once. Measured on the
615 km trip: routing 38.5 s → 24.2 s, whole plan 57.1 s → 42.7 s, and the three
options identical to the metre. The dial exists because of **memory**: peak heap
went 230 MB → 302 MB, since each search builds its own tile cache. `AppContainer`
asks `ActivityManager.getMemoryClass()` and only takes two lanes on a device with
room (`CONCURRENT_ROUTING_HEAP_MB`); an OOM part-way through planning is a worse
failure than a slow plan. Three lanes has never been measured.

What else has *not* been tried, in descending order of expected value and risk:

- **Pin refinement done lazily** — compute the next few pins rather than all of
  them. Not a speed problem: refinement was measured against a 120 s budget and
  still settled in 19 s with identical pins, so it reaches a fixed point rather
  than being cut off. Pursue it only if a trip is found where it does not.
- **A third concurrent lane.** Only `fastest`/`spine` are left to overlap and
  they are 3-4 s each, so the ceiling is small and the memory cost is another
  full tile cache. Measure before believing it.
- **Narrowing the corridor** is now the wrong direction, and the reasoning that
  recommended it is recorded above so nobody re-derives it. Do not retighten
  `CAMERA_CORRIDOR_METERS` without first re-measuring whether the nogo count
  still costs anything.

Beyond that, the remaining cost is the shortest-path search itself, and making
*that* fundamentally faster means a different algorithm (precomputed contraction
hierarchies and the like) rather than a tuning change. That is a large piece of
work against a vendored engine, and nothing in this section should be read as
suggesting a cross-state route can be planned in seconds without it.

And one tension worth knowing about before it is discovered the hard way: an
avoidance pass on a long trip costs about 20 s, while a mid-drive re-plan is
allowed 12 s (`REPLAN_PASS_BUDGET_MILLIS`). So a re-plan early in a cross-state
drive comes back as the plain fastest road — the one thing this app exists not
to hand anyone. It shrinks as the drive goes on, since what gets re-planned is
the *remaining* trip, and it wants confirming on a real drive rather than tuning
at a desk. The ways out are a longer mid-drive ceiling (the driver is still on a
route while it thinks, so this is less dangerous than it sounds) or concurrency.
Not a smaller one: an unbounded re-plan is what §6.1 is about.

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

### Measuring planning against real data

`solver/.../RealWorldPlanningBenchmark.kt` plans a real trip over real `.rd5`
tiles and the real DeFlock dataset, and prints the same breakdown the app shows.
It is **off unless `SHUNT_BENCH_DIR` is set**, and takes its coordinates from the
environment rather than the repository — tiles are hundreds of megabytes, and a
committed benchmark is exactly where someone's real travel would end up.

```
mkdir -p bench/segments
curl -o bench/segments/W90_N40.rd5 https://brouter.de/brouter/segments4/W90_N40.rd5
curl -o bench/cams.json 'https://cdn.deflock.me/regions/40/-100.json'
cp app/src/main/assets/brouter/* bench/
SHUNT_BENCH_DIR=$PWD/bench SHUNT_BENCH_FROM=lat,lon SHUNT_BENCH_TO=lat,lon \
  ./gradlew :solver:test --tests '*RealWorldPlanningBenchmark*' -i
```

Use it before optimising anything here. Every performance decision in this
project up to August 2026 was made from screenshots of the app's own breakdown,
because the sandbox could not reach the tile CDN — and at least one of them was
wrong (grouping cameras by site was expected to help and moved the number by
0.7 s).

**Watch the heap.** The Gradle test JVM defaults to `-Xmx512m`, and BRouter
builds a `NodesCache` over tiles that are tens of megabytes each, so the
benchmark is memory-bound in a way a phone may not be. Raise it before drawing
conclusions, and remember the same constraint is what makes concurrent routing
a memory question rather than a correctness one (§7).

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

- **[high] Confirm the concurrent passes and the denser pins on a phone** —
  §7.4 and §6. Long-route planning is confirmed working on a real device
  (489 km in 1 m 06 s, camera-free); what has not been seen on one is the
  concurrency (43 s vs 57 s on the benchmark) or the density-aware pins. The
  concurrency in particular wants watching for memory pressure on a real
  device rather than a container with 16 GB.
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
- **Charging fine-tuning:** the reserve (`RESERVE_METERS`, now the only margin
  on the car's range estimate), the charger corridor, and the probe cadences are
  first-pass numbers — worth revisiting against real drives.
- On-route arrow, and gray out the traveled portion of the route.
- Simplify the current-location dot to a solid pulsing dot (no accuracy halo).
- Fix one-way arrows pointing the wrong direction on the basemap.
- **Standby mode — follow the car's own navigation.** Shunt sits idle; the
  driver sets a destination in the *car*, and Shunt notices, plans a
  camera-avoiding route to it and starts steering. Anything that appears
  unexpectedly — a destination the driver typed, or a charging stop the car
  inserted — goes to the front of the queue. Most of the machinery exists:
  `TessieAccountClient.activeRoute` already reads what the car is aiming at, and
  `ChargeStopCoordinator` already polls it on a cadence that respects §6.1. What
  is new is the idle watch and the trigger, and the thing to be careful about is
  §6.1 again: a mode whose whole job is to notice a destination and push a route
  is one bad edge away from fighting the driver, so the stand-down rules have to
  cover it from the start.
- **[far future]** Direct Tesla Fleet API integration (connect a Tesla account,
  no Tessie) — only after everything else is fleshed out and tested.
