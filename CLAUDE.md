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
  Fewest-cameras chooser that **opens on fewest-cameras**. Not cosmetic: the
  selection is the trade-off every *later* leg is planned to, so opening on
  Fastest planned the rest of a long trip as the plain fastest road. See §6,
  "The chooser's selection is the whole trip's".
- The map: dark basemap, every known camera with its facing cone, tap for
  details, the route, live location, and **place names** — shops, parks,
  amenities. The basemap style draws none of those (it descends from Dark
  Matter, a deliberate backdrop style), but the tiles it fetches carry them, so
  `RouteMap.addPlaceLabels` renders `poi` and `park` from the source that is
  already loaded. No extra request, no new host. See F-23.
- The drive monitor: camera-approach warnings, waypoint advancement, off-route
  detection, escalating haptics, **spoken alerts** and notifications. Works
  fully offline — the speech is Android's on-device TTS, no account or key
  (`SpokenAlerts`), routed as navigation guidance so it reaches car audio and
  ducks music.
- A live "what is Shunt doing" line on the driving sheet (`DriveActivity`):
  sending waypoint *n* of *m*, asking the car about charging, re-planning,
  stood down. All of this happened before and was invisible unless it failed.
- Destination search (Photon), favourites, long-press-map-to-route, and recents
  matched as you type — which doubles as the answer to a place OSM cannot name:
  pin it once and it is findable by name from then on.
- A rolling week of diagnostics (`DiagnosticLog`), exportable to a file the user
  saves where they choose. Never uploaded, never scheduled; coordinates are stripped
  unless the person exporting turns them on, and the log expires by itself.
- An adjustable camera reach (`CameraVision.rangeScale`, a slider in settings).
  Nobody publishes an ALPR's read range and it varies with the lens, the height
  and the traffic, so the built-in figure is a *policy* about standoff rather
  than a measurement. The scale flows through routing, the camera counts, the
  drive warnings and the cones drawn on the map together, so they cannot
  disagree about what a camera can see. **That last clause was aspirational
  until August 2026** — the nogo shapes read the base constants directly, so
  turning the setting up made the app report more cameras on a route it had not
  moved an inch. `NogoCoverageTest` now holds the agreement at several scales,
  which is the version of that test that could have caught it. See F-22.
- Practice cameras (`PracticeCameras`): a deterministic field of invented ALPRs,
  **snapped onto real roads** with BRouter's own waypoint matching, switched on in
  settings, so avoidance can be exercised where the real ones have been removed.
  Snapping doubles as the density rule — candidates with no road within
  `SNAP_RADIUS_METERS` are dropped, so a uniform grid comes out dense in town and
  sparse in the country without anything having to know where towns are. Tagged
  as not real everywhere they appear, with a banner on the map while it is on.

Long-route planning was the blocker for real use and is now solved. A 489 km trip
into dense metro — the one that used to be abandoned after twenty minutes —
**planned on a real phone in 1 m 06 s and returned a genuinely camera-free
route**, verified against the map. Since that measurement the two avoidance
passes run concurrently, which takes the same 615 km benchmark trip from 57 s to
43 s; that part is from the repository's benchmark and wants confirming on a
phone. See §7.4.

**Longer than that, the answer is to stop planning it in one go.** A trip whose
routes escape the camera corridor pays for the whole chooser twice, and at
~580 km the second round ran out of budget and handed the driver the fastest road
and nothing else (§7.10, F-16). `LegSplitter` cuts such a trip at a camera-free
point on the direct road and plans the first leg alone: the same 583 km trip goes
from 75 s and 43 cameras to **9.4 s and a camera-free first leg**. The solver
side is done and measured; it is *off by default* until the app can extend a
drive in progress. See §6, "Cutting a long trip into legs".

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

**A commercial geocoder was tried and taken back out.** After enough of the
missing-places problem the maintainer asked for Google, then reconsidered a few
minutes later — *"let's not do the Google API thing I just want something that
actually works"* — and the reconsideration was right, because measuring properly
found the fault was ours. See below.

**A hole in the camera data is not a camera set.** `CameraResult.missingTiles`
counts the tiles nothing could supply — no network, no cache, not in the bundled
snapshot — and planning refuses rather than treating them as empty. They *were*
treated as empty: `loadTile` ended `snapshot.tile(key) ?: emptyList()`, so an
unloadable tile and a tile with genuinely no cameras returned the same value, and
a route through the hole came back labelled camera-free having never been asked
to avoid anything. That is the one failure §5 names outright. See F-49.

**On-device and offline-first.** Once a region's map tile is cached, routing and
the drive monitor need no network at all. No background work, no analytics, no
telemetry, no account. The diagnostic log is not an exception to this and must
never become one: it is written locally, expires after a week, and leaves the
phone only when a person exports it and sends it themselves. An app whose purpose
is to stop a driver being tracked cannot quietly report where they drove. Location permission is while-in-use only —
`ACCESS_BACKGROUND_LOCATION` is never requested.

**Search coverage is OSM-limited by design, but far less than it looked.**
Being keyless means some businesses Google would find are missing from
OpenStreetMap. That is true and it is *not* what most "missing location"
complaints turned out to be.

**Both geocoders must be asked about the driver's own area before the world.**
Photon's `location_bias_scale` is a preference, and measured against real
queries it loses badly to raw OSM "importance":

| typed, from a small town | biased only | bounded to ±1.5° |
|---|---|---|
| "Concordia Public Library" | a library in Hong Kong | the actual local library |
| "brown grand theatre" | a theatre in Warsaw | the Brown Grand Opera House |
| "Main Street Concordia" | a school in Tomball, Texas | streets in the right town |

The failure is not ranking and cannot be fixed by ranking: `rankByProximity` can
only sort what it was given, and what it was given was Hong Kong. A hard `bbox`
is what puts the local answer in the response at all. `PhotonSearch.suggest` now
searches bounded first and widens only when that genuinely finds nothing —
*genuinely*, because a throttled request is not an empty result and answering a
429 with a second request is the opposite of what it asked for.
`NominatimSearch` has done the same thing for the same reason since F-11.

This is why the Google experiment was abandoned rather than finished: the
coverage gap that justified it was mostly our own query. What remains genuinely
missing is genuinely missing from OSM, and the answer to that is unchanged — add
the place, or press and hold it on the map, which files it in Recents and makes
it findable by name from then on.

**Searching for a *kind* of place is a different query, and must not go through
the name geocoders.** Asked for "coffee" they answer with Coffee County,
Alabama; for "grocery", with shops called "Grocery" in Dubai. That is not a
ranking fault to be tuned — a text geocoder is doing its job, and the job is the
wrong one. `PlaceCategories` recognises the words a driver actually types and
`PhotonSearch.nearby` answers them by OSM tag through Photon's *reverse*
endpoint, which takes `osm_tag` filters and a radius and needs no query text at
all. Same host, same keyless terms, about a second, and it returns real
supermarkets a couple of miles away instead of Dubai. Overpass was measured for
this and rejected: 30-40 s and frequent timeouts, which is fine for the one-shot
Supercharger lookup and hopeless for a typeahead.

**Overpass is also not the answer to "the place exists and Shunt can't find
it", and this was measured rather than assumed.** The idea is tempting and
keeps coming back: both geocoders answer from a pre-built index tuned for
worldwide name lookup, so a small local business loses to its namesakes and a
recently mapped one is missing entirely, while Overpass queries OSM itself
minutes behind live. Built and measured as a last-resort tier — used only once
both geocoders had failed, where the alternative was telling the driver no such
place exists — a name query came back in **72-79 s** with a `nwr[name~…,i]`
regex, and *narrowing the radius from 50 km to 5 km did not help*, because a
case-insensitive regex on `name` cannot use any index and scans the whole area
either way. One call in three was rate-limited outright. It was written, tested,
measured, and deleted; do not rebuild it.

What *does* work for a place the map data doesn't name is already in the app:
press and hold it on the map. It is reverse-geocoded, routed to, and kept in
Recents — and Recents are now matched as the driver types, so a place saved that
way is findable by name from then on. That is keyless, instant, works offline,
and cannot be missing. The other half of the answer is unchanged: add the place
to OpenStreetMap so it is searchable for everyone.

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
| `:brouter` | Pure JVM | Vendored BRouter engine (MIT). Upstream except `NogoIndex.java` and one loop in `RoutingContext.calcDistance` (§7), and the nearby-way collection in `WaypointMatcherImpl`/`MatchedWaypoint` (§6); also republishes the shipped routing profile onto the JVM classpath |
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
- *What roads may the router use at all?* `app/src/main/assets/brouter/car-vario.brf`
  — the shipped routing profile, and its **only** copy. `:brouter` republishes
  those bytes onto the JVM classpath at build time (`bundledBrouterData`), so
  tests and the benchmark route against exactly what a phone routes against; it
  used to be a second checked-in file that could drift. The one Shunt rule in
  it is marked `SHUNT CHANGE` — see §6, emergency-only crossovers —
  and `CarProfileAccessTest` evaluates the profile against tag combinations
  directly, which is the only routing check in CI that needs no `.rd5` tile.
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
- *Why does the route double back at a leg boundary?* It did, and no longer
  should — `solver/brouter/LegJoin.kt` trims the out-and-back where two legs
  meet. See §6, "Cutting a long trip into legs".
- *How do I test avoidance where there are no cameras?*
  `solver/camera/PracticeCameras.kt` — deterministic by construction (positions
  hash from the grid cell, so there is no seed to share and no state to get out
  of step), and every camera carries `shunt:practice` so nothing downstream can
  present one as real.
- *How does a user report a bug?* `app/diag/DiagnosticLog.kt` (pure, tested) and
  `app/diag/DiagnosticExport.kt` (the Android half: the document picker and the
  output stream). The split is deliberate — the privacy rules are in the part
  that can be unit-tested. It **saves** rather than shares: a share sheet asks
  who to send a week of somebody's driving to before they have read a word of
  it, which is the wrong order of operations.

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

Which brackets everything: `lead ≤ spacing ≤ past-fork`. **That was held by
coincidence and is now held by construction**: the two ends were derived from
different things — spacing from camera density, the lead from speed — and
nothing makes a camera-dense corridor a slow one, so a 55 mph arterial through
one got 250 m pins and a 450 m lead and the monitor re-aimed two and three pins
ahead at once. The lead is capped at `waypointLeadGapFraction` of the gap the
pins were actually placed at, so the relationship holds whatever the speed. The
turn-commit gate's lookback likewise has to *exceed* `PAST_FORK_METERS`, and did
not — 500 m against 600 — so on a fast road it could not see the turn its own
pin was guarding. See F-48. Both pairs are now the
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

### Every turn gets a pin, whether or not anything says it needs one

Everything above decides pins by *prediction*: route the leg the way the car
would — through BRouter — and pin where that prediction says the car strays.
The maintainer put the obvious objection to it:

> I have a hard time believing that it would actually turn off the road it's on
> to get to that waypoint, especially if it introduces another turn.

That is the right objection, and the answer is that the prediction is only as
good as BRouter's model of Tesla's router. Where the two disagree, a leg that
looked fine is one the car drives its own way, and nothing on the route says
otherwise. Pruning made that worse by removing pins on the strength of the same
model.

So `WaypointExtractor.turnPins` puts a pin past **every turn the route takes**,
found geometrically (`turnsAlong`) and independent of cameras, routing or
prediction, and `refine`'s `protectedPins` stops pruning removing them again.
The reasoning is that a turn is the only place the prediction can cost anything:
carrying straight on is never a wrong answer to a route that goes straight on,
and it is only at a junction that the car has a choice to get wrong. Pinning
them turns the route from *predicted* to *instructed* exactly where prediction
is risky.

The cost is one rate-limited command each as the drive passes them, spread over
hours — which is why this is worth doing generously. Measured on the 615 km
benchmark, and better on every axis:

| | before | with turn pins |
|---|---|---|
| balanced | 27 pins | 82 |
| fewest-cameras | 30 pins | 100 |
| spread of fewest, by tenth | `0 0 1 1 4 3 1 4 4 12` | `2 1 3 6 18 10 8 17 10 25` |
| pin phase | ~33 s | 10 s |

Faster, counter-intuitively: more pins means shorter legs, and a short leg is
quicker to route than a long one. Spacing still applies afterwards, so a dense
grid cannot produce pins closer together than the drive monitor can use.

**A trap for anyone writing tests here.** A hard-cornered fixture now gets pinned
at its corners whatever else is true, which silently makes it useless for testing
the camera and shortcut logic — two existing tests started passing for the wrong
reason. `gentleArc` in `WaypointExtractorTest` exists for that: a divergence with
no turn in it, guarded by its own test asserting `turnsAlong` finds nothing.

### And every camera the route squeezes past gets two

The same argument as turn pins, aimed at the other place where prediction being
wrong is expensive. Reported from a real plan, looking at the map:

> in the first pic it shows a route that the car could easily still route
> through a camera, I don't trust that.

Nothing was wrong with the route. What was missing is that the stretch beside
that camera was held **only by prediction** — the chord test in
`pinAgainstShortcuts` and the leg routing in `WaypointRefiner` both decide a
stretch is safe by asking BRouter what the car would do. Everywhere else that is
a reasonable bet. Next to a camera the route deliberately dodged it is the one
answer that is not good enough, because the cost of BRouter and Tesla's router
disagreeing there is exactly the exposure the whole route exists to prevent.

`WaypointExtractor.cameraGuardPins` therefore brackets it: for every avoided
camera whose closest approach to the route is inside `CAMERA_GUARD_RADIUS_METERS`
(600 m — about one open-road pin spacing), a pin one fork distance before that
point and another one after. The near pin puts the car on our line before the
camera's neighbourhood; the far one holds it there rather than letting it rejoin
immediately. Both are in `protectedPins`, for the same reason they exist:
pruning drops a pin on BRouter's word.

**Only where the route has left the fastest one**, and that gate is what keeps
this from flooding a city. A straight run through a metro passes hundreds of
cameras a street or two over that it never goes near; bracketing each would put a
pin every couple of hundred metres on a road with no decision on it. Where our
route *is* the fastest route the car has no reason to leave it — the same
reasoning that limits turn pins to turns.

Measured on a 330 km benchmark trip into dense metro country, over real tiles
and the real DeFlock set, and cheap:

| | before | with guard pins |
|---|---|---|
| balanced | 73 pins | 113 |
| fewest-cameras | 59 pins | 110 |
| pin phase | 5.6 s | 6.6 s |

### A pin is a coordinate the car snaps, so it must be a sane place to stop

Everything above decides *how far along the route* a pin should go. Nothing
asked whether the resulting point was a sensible thing to hand a car, and two
failures from a real drive are the same mistake:

> Sometimes it can cause the car to pull into a driveway if a waypoint after a
> turn ends up too close on the actual Tesla nav. The shunt waypoint was on a
> turn directly after a turn. Another point had me navigating to a road parallel
> to the planned route.

- **On a junction.** The car treats a waypoint as a *destination*, so it
  arrives: it slows, and it pulls in. Arriving in the middle of a junction means
  the driveway or the side street. `PAST_FORK_METERS` is 600 m — derived from
  what commits a turn on open road, where the next junction is kilometres away —
  so wherever turn B follows turn A closely, the pin for A landed on or past B.
  It stopped instructing A, started implying B, and sat on a junction.
- **Beside another road.** A frontage road, a service road or the far
  carriageway sits tens of metres from ours, and the car snaps our coordinate to
  whichever *its* map calls nearest.

`PinSites` is the gate every pin now passes, whatever made it — turn pins,
camera guards, the shape pass, the refiner's forks. It is a property of the pin,
not of the reason one was wanted, which is why it is one object shared by all of
them rather than a rule in each.

- **Clear of every turn** by `CLEARANCE_METERS` (60 m, about the monitor's
  arrival radius — the distance at which the app already calls the pin reached).
- **A turn pin lives in the block after its turn**, floor to the next turn's
  ceiling. Where two turns are too close to fit one between them — a jog, a
  Michigan left, a roundabout exit — **there is no pin for the first**, and that
  is not a loss: the second turn's pin is only reachable by making both, so it
  instructs both.
- **Not beside a road we can see.** Ambiguous if another line runs between
  `SAME_ROAD_METERS` and `AMBIGUITY_RADIUS_METERS` (3–35 m) away — below 3 m it
  *is* our road, because two routes over the same OSM way share its nodes and
  the distance is zero. Checked against the fastest route and against our own
  line more than `AMBIGUITY_ALONG_METERS` away along it, which is the
  cloverleaf, the switchback and the frontage road we route onto.

`settle` slides a wanted position to the nearest one that qualifies, searching
backwards before forwards — earlier is nearer the thing the pin was placed for,
and a pin that has slid *past* its turn or its camera is no longer doing the job
it was added for. Nothing in reach means **no pin**, which is the safe failure:
a route with fewer pins is still the route we planned, still labelled and still
warned about, while a pin on the wrong road actively steers the car off it.

**This does not weaken the `lead ≤ spacing ≤ past-fork` bracket.** The monitor's
lead is capped at `waypointLeadGapFraction` of the gap the pins were actually
placed at, so a shorter block buys a shorter lead rather than a pin abandoned
before its turn — and the turn-commit gate holds the advance until the bend is
behind the car regardless.

**What it cannot see is a parallel road neither route uses.** There is no
geometry for it here. Answering that properly means asking BRouter's graph which
ways lie near a point, which is engine work — see the roadmap. See F-53.

### Asking the road graph what is actually near a pin

`PinSites` decides ambiguity from geometry over two lines: our route and the
fastest one. That catches a frontage road we route onto, a cloverleaf coming
back near itself, a switchback. It cannot catch a road **neither route uses** —
and that is the one a driver reported twice:

> When a waypoint is set on or near a highway it can sometimes go to the other
> side of a road on Tesla's navigation, leading to routing wanting to go back
> around. […] The car also has a resistance to navigating to the highway if
> other parallel roads are nearby.

The far carriageway of a divided highway carries no part of either route, so
there is no geometry for it. `BrouterRouter.roadsNear` asks the graph instead:
for each point, the distance to every routable way within a radius. **Distances
only** — what the caller needs is whether *something else* is close, and the
road the pin sits on is the one at nought.

**This is the third divergence from upstream BRouter**, and the smallest.
`WaypointMatcherImpl` records a way only when it beats the best match so far,
which is exactly right for "snap this point to a road" and useless for "what
else is near it": once a way is found at distance zero nothing else is ever
recorded. The change computes the same distance the existing block computes, up
front, and records it when the caller has asked for collection. It is **off
unless `MatchedWaypoint.nearbyCollectRadius` is set**, so ordinary routing runs
byte-identical code paths — that is a structural guarantee, not a measurement.
Both edits are marked `SHUNT CHANGE`.

**Best available, not pass-or-drop**, and that is the whole design.
`BrouterPlanner.onUnambiguousRoad` offers each pin a handful of positions along
the route (`PIN_NUDGE_METERS`) and takes the one where the nearest *other* road
is furthest away. Requiring a clear `AMBIGUITY_RADIUS_METERS` would delete every
pin on a divided highway, because its carriageways run tens of metres apart for
their whole length — and a motorway with no pins at all is how a car takes an
exit nobody planned. Only where even the best position is inside
`MIN_OTHER_ROAD_METERS` — close enough that which road the car picks is a coin
toss — is the pin dropped, on `PinSites`' own reasoning.

One batched query per option: each call loads tiles, so asking per pin would put
the tile reader in the inner loop. The seam is a lambda on `BrouterPlanner` like
`route`, and its default answers "nothing near anything", which leaves placement
exactly as the geometry decided — the behaviour before the graph could be asked,
and what every test that does not care about this gets.

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

### Not through the gap the police use

> i do not want it making turns on a highway using the turn lane dedicated for
> emergency vehicles, often times that's recommended by low end nav apps and I
> don't want that. Have to be able to distinguish the Michigan left type turn
> though.

The gap in a motorway or trunk median that exists so a patrol car can turn round
is usually mapped `highway=service` + `service=emergency_access`, very often with
no access tag at all. BRouter's untouched profile grants cars **every**
`highway=service` way that carries no access tag, so those gaps were routable —
and a router hunting for a shorter way past a camera will take one. That is a
manoeuvre this app may never suggest, whatever it saves.

`car-vario.brf` now tests that one tag and nothing else, which is exactly what
keeps the Michigan left. A public median U-turn is an ordinary road or a
`*_link`, and on the rare occasion it is mapped as a service road it says so with
`motorcar=yes` / `access=yes` — both read *before* this rule, so an explicit
permission still wins over our inference. `access=no|private` and
`motor_vehicle=emergency` were already excluded upstream and still are.

What it cannot cover is the crossover mapped as a bare `highway=service` with
nothing to distinguish it. There is no signal there to act on, and the honest
answer is to tag it in OpenStreetMap — the same answer as the search-coverage
problem in §3.

This is the **second** divergence from upstream BRouter (the first being
`NogoIndex`), and the only one in the profile rather than the engine. It is
marked `SHUNT CHANGE` and held by `CarProfileAccessTest`, which evaluates the
shipped profile against tag combinations with no tiles and no search — the same
expression engine BRouter runs per link, handed the tags by hand. That test is
worth knowing about: it is the only way the profile's access rules are checkable
in CI at all.

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

### A waypoint the car has driven away from must be let go of

Every advance gate measures progress **along the route**, which is right while
the car is on it and useless the moment it is not. The projection is forward-only
and windowed, so a driver who takes over and goes their own way stops making
progress by that measure, `metersLeftTo` never falls below the lead again, and
the pin sticks — for the rest of the drive. Reported as *"during navigation, it
can still get caught up on a previous waypoint and I'll have to exit and restart
navigation to fix."*

`strandedOn` is the safety net: a pin more than `passedBehindDegrees` off the
direction of travel **and** getting further away, for `passedFixes` fixes
running, is one the car has driven past. Both halves are needed — *behind* alone
is true for a moment at every junction, *receding* alone is true of any route
that swings away before coming back — and the run resets the instant either
stops holding, so an ordinary bend costs nothing.

It never applies to the destination or to a stop the driver asked for. Both are
handled earlier, and both are places the car is *meant* to arrive at.

### The probe must not redirect the car near a junction

A charging probe re-asserts the final destination to ask its question, so for
those seconds the car is navigating its own way there. `ProbeWindow` rationed
that against the next waypoint and the nearest camera — and not against turns.
Reported: *"we can't have it check for charging if there are any potential turns
around that the car would take if navigating directly to the destination. That
needs to be fixed. We also can't have that happen right before a turn either."*

`clearOfTurnMeters` (2 km) and `clearOfLastTurnMeters` (400 m) close it, from
`DriveMonitorEngine.metersToNextTurn` / `metersSinceLastTurn` — the same bend
test the waypoint commit gate uses, so a junction is a junction by one definition
wherever it is asked about. Wider than the camera gate on purpose: a camera is
something to warn about a little early, while a turn taken wrongly is a
manoeuvre that has to be undone, and under FSD it happens before the driver has
decided anything.

**Free reads are exempt and should stay that way.** A car that already holds the
final destination is read without pushing anything, so there is no redirect to
mistime. Only the re-assert path is gated.

### Share is a search box, and that is the problem

The `share` fallback (§6) is the one place Shunt hands the car a *string* and
lets the car decide what it means. Given a bare `lat,lon` it runs that through
the same place lookup a typed query goes through, and two reported failures
follow: it can resolve to something near the coordinate rather than the
coordinate — *"it should send the actual correct location at the end"* — and
where the lookup finds more than one candidate the car stops and asks the driver
to choose: *"sometimes the car doesn't know what specific location is being
referenced and will ask the user to select which location the car is being sent
to."*

`shareMapUrl` sends the coordinate as a map link, which the car resolves rather
than searches, and `shareValue` stays as the fallback — a car that will not take
the link is no worse off than before. It costs no account and no key of ours,
though the car may resolve the link through its host, which is worth knowing
before this is extended. **Unverified on a vehicle**: this is a diagnosis from
the symptom, and the read-back experiment in `docs/field-notes.md` is what would
confirm it.

### Where the aim moves on, drawn on the map

Requested from a drive: *"it would be nice to visualize on the map where exactly
each waypoint's trigger is so that I can know when to expect the next to get sent
to my car. This will help with sensitivity calibrating."*

`DriveMonitorEngine.triggerPoints` answers it, and the reason it is worth drawing
is that no driver could work it out: the lead is the larger of a floor and
eighteen seconds of driving, capped at half the gap the pins were placed at, and
then the turn-commit gate holds the advance until the bend before the pin is
behind the car — three interacting rules, two of them speed-dependent. The marks
slide along the line as the car speeds up and slows down, which is the honest
picture.

Its own render memo, not the route's: they move every fix, and rebuilding a
cross-state route's lines once a second is exactly what that memo exists to
prevent.

### How far away a waypoint is, is a question about the road

The monitor used to answer it with a ruler: straight-line distance from the car
to the pin. Reported from looking at a planned route:

> When a route passes right next to a waypoint it isn't going to, it could
> trigger that waypoint prematurely, or even if that's a waypoint it is
> currently navigating to.

Which is right, and it is not a rare shape. Any cloverleaf, switchback, or
frontage road beside the carriageway brings the line back within metres of
itself, so a pin on the far pass sits right beside the car while still being a
mile off *along the route*. A ruler calls that arrival; the road says keep
going. Advancing there is the §6.1 failure in miniature — the car is handed a
target it has already passed by, and the pin that was meant to hold the next
turn is gone before the turn.

`DriveMonitorEngine` precomputes `pinAlong` (how far along the route each pin
sits, walked forward from the previous pin so a route crossing itself matches
the right passage) and `advanceOrArrive` gates on `metersLeftTo`, which is
along-route distance. The arrival-radius valve from the section above still
applies, and is still safe: it is reached only after the along-route test has
passed, so it cannot fire from the other side of a loop.

**One trap, and it cost two existing tests.** Along-route position needs the
car's projection *into* the current segment, not the segment's start vertex.
Rounding back to the vertex is exact on a dense line and hopeless on a sparse
one — a re-planned leg or a straight hop between junctions is two points a
couple of kilometres apart, and rounded back the car reads as sitting at the
start of that hop until it reaches the far end, so nothing inside it ever
advances. This is the same trap `sampleSpine` fell into in the planner: **a
polyline's vertices say nothing about the road between them.** `alongOf`
projects; `a sparse route line still measures progress inside a long hop` holds
it there.

### A command the car never got has to be sent again, from where you are now

Reported from a drive: *"it doesn't try again if it fails to update the next
waypoint due to no reception. It should try again with the correct next waypoint
and fix itself if I'm ahead."*

A failed `advanceTo` raised an urgent alert whose text said **"Retrying"** — and
nothing in the system retried. The car kept the pin the driver was about to
pass, and because a pin is a destination the car *arrives* at it: it stops
steering the route and starts arriving at a point behind the driver.

`DriveMonitor.unsent` carries the failure, and `retryUnsent` runs once per fix
after the signals. Four things make it right rather than merely persistent:

- **It re-asks, it does not replay.** The retry sends
  `DriveMonitorEngine.remainingChain()` as it stands *now*. The engine advances
  on GPS alone — it never needed the network — so by the time reception returns
  the current pin may be two further on, and re-sending the coordinate that
  failed would aim the car behind the driver. That is §6.1 in miniature.
- **After the signals, not before.** Retrying first would use the engine's state
  from before this fix, which is the stale answer again.
- **Announced once per episode.** The failure is `Severity.URGENT` and
  interrupts; repeating it every ten seconds for the length of a dead spot
  teaches the driver to ignore it. The recovery *is* announced
  (`Alert.AimRestored`), because otherwise the last thing they heard was that
  route updates are failing.
- **Only what is worth retrying.** `PushResult.Failed.retryable` decides. A
  refusal the car will give again is not fixed by asking a fifth time, and
  carrying it would leave a spinner up for the rest of the drive.

`stoodDown` clears it like every other path to the car. An unsent aim is not a
reason to re-earn control. See F-54.

### After touching the car's destination, put the aim back — always

**From a real drive, and the most instructive kind of bug: every piece worked
and the whole did not.** A charging probe has to redirect the car at the final
destination to ask its question — that *is* the question, "given the whole trip,
what do you intend?" — and the coordinator was left to restore the steering aim
afterwards. It does, on the paths it knows about. It cannot on the others: a
re-assert that reports failure after the car has already taken it, a resume whose
re-plan comes back empty, an exception on the way out. On any of those the car is
left holding the trip's destination.

A car holding the destination drives to it. So the driver leaves the shaped
route, which reads as off-route, which re-plans — and the re-plan put them on a
road with cameras on it that they had a clean route around. The camera exposure
is four steps downstream of a missing push.

`DriveMonitor.reaim` therefore asserts the aim rather than trusting it: every
charging check that changes nothing ends with the car pointed where the monitor
believes it is pointed. That is the only claim worth making after touching the
car's destination, and it costs one rate-limited command every 45 s at worst.

Two conditions on it, both load-bearing:

- **Only while steering.** A car that holds the destination is read for free —
  no push, no redirect, nothing to put back — so re-sending its own destination
  would be pure traffic. A steered car never holds the destination, so every
  probe redirects it and every probe owes it an aim.
- **Never after standing down.** "Unconditional" means whatever the *probe*
  concluded, not whatever the *driver* wants. §6.1 covers this path like every
  other.

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
5. **The route the car could still cut through a camera.** *Fixed, unconfirmed
   on a drive.* From looking at a planned route on the map: a detour around a
   camera with no pin anywhere near the squeeze. The stretch was held only by
   BRouter's prediction of what the car would do, which is the one place that
   bet is not good enough. Camera guard pins bracket it — §6, "And every camera
   the route squeezes past gets two".
6. **A waypoint could trigger while the route was still a mile from it.**
   *Fixed, unconfirmed on a drive.* The monitor measured how far away a pin was
   with a ruler, and any cloverleaf or frontage road brings the line back within
   metres of itself. §6, "How far away a waypoint is, is a question about the
   road".
7. **It would turn across a divided highway through the emergency-vehicle gap.**
   *Fixed, unconfirmed on a drive.* BRouter's stock profile grants cars every
   untagged `highway=service` way, which is how those gaps are usually mapped.
   §6, "Not through the gap the police use".
8. **The screen stalls while a long trip is planned.** *Believed relieved by
   legs, unconfirmed on a phone.* Reported as "kind of a screen freezing issue
   again on longer distances". Planning already runs off the main thread
   (`AppContainer`'s `RoutePlanner` wraps the whole plan in `Dispatchers.Default`,
   not just the routing), so this is contention rather than a blocked UI thread:
   two routing threads at full tilt for a minute or more, plus the GC pressure of
   two tile caches. Cutting the trip into legs takes the foreground plan from 75 s
   to about 9, which is the structural fix; if it still stalls after that, the
   next thing to try is running the routing threads at background priority so the
   UI thread preempts them.
9. **A charging check left the car aimed at the destination, and the recovery
   drove past a camera.** *Fixed, unconfirmed on a drive.* Reported with
   screenshots from a real drive: after a charging check the shaped route was
   abandoned, the car left the route, and the re-plan came back through a camera
   when a camera-free route existed. Two distinct faults in a chain — the aim was
   not restored on every path (fixed, §6, "After touching the car's destination")
   and a mid-drive re-plan is given less time than one avoidance pass needs on a
   long trip, so it can come back as the plain fastest road. The second is the
   known budget tension below and is *not* fixed.
10. **A widen can still cost the driver every camera-avoiding option.** *Open,
   but far less reachable now that long trips are cut into legs — a leg is short
   enough that its routes rarely leave the corridor. Still the correct thing to
   fix, because a leg through dense country can still do it.* On a 583 km trip over
   real tiles, the routes escaped the 60 km corridor, the whole chooser ran a
   second time out of the same plan budget, and the second round ran out —
   `blocked` and `fewest` gave up, `balanced` was skipped, and **the only option
   returned was the fastest road, passing 43 cameras.** The fixed-point loop
   behaved correctly and the outcome is still the one thing this app exists not
   to hand anyone. The same trip at 330 km widens too, but has budget left and
   comes back with all three.

   **Fixed, August 2026, and the fix is the one this entry proposed**: keep the
   previous round's routes and re-label them against the wider camera set. See
   §7, "When the widen runs out, keep what was already found", and field note
   F-26 — reproduced on a real last leg into San Francisco, where it was the
   difference between 126 cameras and 21.

   A later leg of a split trip also gets `LEG_PASS_BUDGET_MILLIS` rather than the
   kerbside figure (see below), which removes the commonest way the second round
   ran out at all, and a leg that still comes back holding only the fastest road
   is written to the diagnostic log rather than accepted in silence.

11. **The last leg of a long trip did not avoid cameras at all.** *Fixed,
   unconfirmed on a drive.* Reported from routes into Washington DC and San
   Francisco, with the observation that identified it: "I have a hard time
   believing they're all unavoidable." They were not. A route may not begin or
   end inside a zone the router has been told is impassable, and Shunt's answer
   was to skip the **whole** hard-block pass whenever any endpoint was watched —
   which on a split trip can only happen on the last leg, because every other
   boundary is chosen for being quiet. See §6, "A watched destination is not a
   reason to give up", and field note F-21.

   **A second, unrelated cause was found for the same report in August 2026**,
   and it is the one that had been doing most of the damage: the chooser opened
   on FASTEST, and the selection is what every later leg is planned to. So the
   last leg was not failing to avoid cameras — it was correctly planning the
   fastest road, because that is what the untouched chooser was asking for. §6,
   "The chooser's selection is the whole trip's", and F-41.

12. **The camera-reach setting moved the warnings but not the route.** *Fixed,
   unconfirmed on a drive.* `CameraCluster` read the base range constants
   directly, so the nogo shapes handed to BRouter ignored
   `CameraVision.rangeScale` entirely. At three times the reach the app returned
   *the identical route it planned at the default* and labelled it with twenty
   cameras — announcing cameras it had made no attempt to dodge, which is worse
   than the setting doing nothing. Field note F-22.

13. **A pin landed on a junction, and another beside a parallel road.**
   *Fixed, unconfirmed on a drive.* Reported mid-drive: a waypoint just past a
   turn that had another turn immediately after it made the car pull into a
   driveway, and a second waypoint had the car navigating a road parallel to the
   planned route. Both are one mistake — a position chosen for what it means to
   us, handed to a car that snaps it to its own road graph and then *arrives* at
   it. `PinSites` gates every pin on being clear of every turn and not beside a
   road we can see. §6, "A pin is a coordinate the car snaps", and F-53.

14. **A waypoint update that failed was never tried again.** *Fixed,
   unconfirmed on a drive.* Reported mid-drive: with no reception the advance
   fails, and the car keeps the pin the driver is about to pass — which it then
   *arrives* at. The alert said "Retrying" and nothing did. Retries now re-ask
   the engine for the current pin rather than replaying the failed one, so a
   driver who covered ground in the dead spot is caught up rather than sent
   back. §6, "A command the car never got", and F-54.

15. **The car was sent to a pin that was not on the route on screen.**
   *Fixed, unconfirmed on a drive.* Reported mid-drive, with a screenshot: the
   next waypoint sat beside a camera, well off the drawn line, and the route
   took a long detour to reach it. Trimming the double-back off the *lead* leg
   updated the map and not the drive monitor, so the car kept being steered to
   pins on a spur that had been deleted from the route. §6, "A leg the driver is
   already on can still be revised", and F-55.

16. **A camera guard pin could settle onto its own camera.** *Fixed the same
   day it was introduced, unconfirmed on a drive.* `PinSites.settleNear` slid a
   pin up to 200 m in either direction, and in a dense area the guard distance is
   250 m — so the pin meant to hold the car on our line *before* a camera could
   settle to 50 m from it, aiming the car at the thing the route detoured around.
   Guards now settle only away from their camera, and no pin may sit within
   `CAMERA_STANDOFF_METERS` of one. F-55.

17. **The monitor could get stuck on a waypoint already passed.** *Fixed,
   unconfirmed on a drive.* Reported mid-drive: "it can still get caught up on a
   previous waypoint and I'll have to exit and restart navigation to fix."
   Along-route progress stalls once the driver leaves the route, and every
   advance gate depends on it. §6, "A waypoint the car has driven away from".

18. **A charging probe could redirect the car just before a junction.** *Fixed,
   unconfirmed on a drive.* `ProbeWindow` gated on waypoints and cameras but not
   turns, so a re-assert could hand the car the destination a few hundred metres
   from a turn it would then take. §6, "The probe must not redirect the car near
   a junction".

19. **The car asked the driver which location was meant.** *Diagnosed, fix
   landed, unverified on a vehicle.* The `share` fallback hands the car a bare
   coordinate string, which it geocodes — so it can land near rather than on the
   destination, and can come back ambiguous. §6, "Share is a search box".

20. **A pin on or near a highway can snap to the far carriageway.** *Fixed,
   unconfirmed on a drive, and unmeasured for cost.* Reported twice: the car
   navigates to the other side of the road and wants to go back around, and it
   prefers a parallel side road over the highway itself. Geometry over our route
   and the fastest one cannot see a road neither uses; BRouter's graph can. §6,
   "Asking the road graph what is actually near a pin", and F-58.

21. **A split trip's first leg was driven to the trip's destination.**
   *Fixed, unconfirmed on a drive.* `drivePlanFor` appended the final
   destination to a chain describing the first leg only, so the car was aimed
   hundreds of kilometres ahead once past the last lead pin — and an extension
   then put the trip's end in the middle of the chain. §6, "A leg's chain ends
   where the leg ends", and F-57.

22. **A charging detour deleted the driver's own stops.** *Fixed, unconfirmed
   on a drive.* The charge leg was planned with no stops and the monitor rebuilt
   its engine from it, so the stops were gone from every later read. §6, "What
   happens when the car adds a charging stop", and F-57.

23. **An unroutable charging leg was fought.** *Fixed, unconfirmed on a drive.*
   Shunt told the driver the car was detouring to a charger its own way, then
   re-aimed the car back at our route — off the charger it inserted because it
   needed the charge. F-57.

### A watched destination is not a reason to give up

BRouter refuses a route that begins or ends inside a zone it has been told is
impassable (`last wpt in restricted area`). In a city centre the destination is
very often within sight of a camera, so this fires often — and what Shunt used to
do about it was skip the hard-block pass altogether and fall back to weighted
avoidance.

That fallback is a **different promise**. A weighted nogo charges (metres inside
the zone × weight), so a road clipping the edge of a cone is cheap and gets
taken; only a hard block makes "fewest cameras" mean *none*. One unavoidable
camera at the kerb was therefore buying a dozen avoidable ones on the approach.

`BrouterRouter.withoutZonesHolding` keeps the offending **zones** instead of
skipping the pass — and `splitAtEndpoints` gives them a *weight* rather than
removing them, which is the difference between "you may end here" and "this
camera does not exist". Deleting the zone let the route drive through that
camera anywhere on the leg, on any approach; measured from a real log, a 221 km
leg into Washington took the plain fastest road through a camera the driver had
seen avoided before. A price is paid per metre inside, so the route enters at the
end where it must and not a kilometre earlier where it need not. The split is
**per camera, before clustering** — a zone is one shape per *site*, so dropping
it used to drop a junction's worth of cameras for the sake of one near the kerb.
See F-51. Everything else stays blocked, so the route is camera-free wherever a
camera-free road exists and passes only the cameras pointed at where the driver
is going — which no route can avoid, because arriving is what triggers them. The
result sheet says so in those words rather than leaving one camera on a clean
route looking like a failure.

Three things to preserve:

- **The containment test is BRouter's own** (`RoutingContext.cleanNogoList`),
  applied to a *ring* around each endpoint as well as the point itself. BRouter
  does not test the coordinate handed to it — it snaps each waypoint onto the
  nearest road first, up to `waypointCatchingRange` (250 m) away, and tests that.
  A margin narrower than the snap lets the original failure back through.
- **Under-dropping is graceful**, which is what makes the margin a judgement
  call rather than a correctness one: BRouter refuses, the pass reports no route,
  and the weighted fallback runs exactly as it did before this existed.
- **Labelling never moves.** Camera counts are measured against the full set
  whatever the engine was given, so a dropped zone can never make a route look
  cleaner than it is. The count shown to the driver is taken from the cameras the
  route *passes*, never from the zones dropped — a dropped zone stands for a
  whole site and the ring reaches past it, so the two numbers genuinely differ.

Measured on a four-leg benchmark trip ending on top of a camera in dense metro:
the last leg went from 3 cameras with the block skipped and two options, to 2
cameras — both at the destination — with all three options and the block
attempted. Legs 1–3 came back byte-identical, which is the check that matters.

### When the widen runs out, keep what was already found

A widen is not a slightly wider camera set. It is **the whole chooser run a
second time out of the same budget** — and when that second round runs out, what
comes back is the one pass cheap enough to always finish: the plain fastest road.
That is the single worst answer this app can give, and it arrives looking like a
considered one.

Reproduced against real tiles on the last leg of a trip into San Francisco, which
is where a driver reported it:

| leg into a dense metro | before | after |
|---|---|---|
| options returned | `FASTEST` alone | `FASTEST` + `BALANCED` |
| cameras on the best one | **126** | **21** |

The breakdown says exactly what happened: round one spent 12 s proving no
hard-blocked route exists, 26 s on the weighted fallback finding none either, and
17 s producing a balanced route — which then left the corridor. Round two got
what was left, ran out, and only `fastest` survived.

So the earlier round is kept (`carried` in `BrouterPlanner.plan`). Two things
make that sound rather than merely better than nothing:

- **The labelling is recomputed from the final camera set**, for every option,
  whichever round it came from — `passedCameras` already was, and
  `exposureMeters` now is too. The rule the fixed-point loop exists to enforce is
  untouched: no route is ever described against cameras it was not measured
  against.
- **Every carried route is genuinely covered by that final set.** The widen
  exists precisely to cover the routes that escaped, and the spine grows to
  include their own geometry — so a route that escaped is on the new spine, and
  one that did not was inside the narrower corridor already.

What is given up is the claim that the route is *optimal* for the wider set, and
the result sheet says so in those words rather than presenting it as a finished
search.

### Cutting a long trip into legs

Planning cost grows faster than distance, and past roughly 500 km it stops
producing a usable answer at all: the routes escape the camera corridor, the
whole chooser runs a second time out of one budget, the second round times out,
and the driver is handed the fastest road (§7.9). Meanwhile the phone is
unresponsive for the minute or two it takes, because two routing threads are
competing with the UI for the same cores.

`LegSplitter` cuts the trip instead. The first leg is planned and handed over;
the rest are planned while the car is already moving.

**How it reaches the driver.** `BrouterPlanner` returns the leg's options plus
`remaining`; the result sheet says plainly that its numbers describe the first
stretch only, with the whole trip's direct distance beside it.

**The rest is planned from the moment the chooser appears, not from Go.** The
phone is idle while somebody reads three options, and waiting meant the map
showed a line stopping in open country for as long as they took to decide.
`AppContainer.planRemainingLegs` owns it — not the plan screen, because planning
has to outlive that screen when the driving sheet takes over — and it runs **to
the destination** rather than a leg at a time, so a slow leg has hours of slack
instead of minutes. Each leg goes two places at once: onto `laterLegs` for the
map, so the line visibly grows to the destination from a standstill, and through
a conflated channel to the drive monitor.

**Every later leg is planned with the heading the leg before it arrives on.** A
boundary sits on the *direct* road and the camera-avoiding continuation often
does not, so with no cost attached to leaving the way the car came, the cheapest
route onto that continuation is frequently back down the road just driven — a
spur, or a loop around a camera and back. That is a generator of the very
doubling-back the trim and the seam re-plan exist to undo, and it was free to
remove. It **biases rather than forbids** (BRouter models `startDirection` as an
imaginary previous position about a kilometre back, so ordinary turn costs
apply), so both repairs stay for the boundary that is genuinely in the wrong
place. See F-42.

**The channel legs travel on is unbounded, and that is load-bearing.** It was
conflated — capacity one, older value dropped — on the premise that each
extension carries the whole of what is left. It does not: `extend` *appends*, so
every leg is a delta and a dropped one is a hole in the route, its pins and its
cameras. Legs are planned from the moment the chooser appears while the monitor
that drains them does not exist until Go, so a driver who read the options for a
minute lost the middle of their trip — and whether it happened at all depended on
how long they looked at the screen. `legExtensionChannel()` exists so the test
builds the production channel rather than one of its own; the monitor drains
every queued leg per fix, not one.

`DriveMonitor.extend` appends it, and the one thing that makes that safe is that
**the new chain starts from the pin the car is aiming at, not from the
beginning.** Rebuilding over the whole chain would reset the monitor to the first
pin, which is an hour behind the driver. Dropping what is already passed makes
the join a plain append, and nothing is pushed to the car — the pin it is aimed
at is still the head of the chain, so an extension is invisible to the vehicle.
The engine inherits which cameras have already been announced, because an
extension is the same drive continuing.

Arriving at a boundary with no leg beyond it raises `Alert.LegBoundaryReached`
rather than announcing arrival. It should be close to unreachable — the boundary
is at least `MIN_LEG_METERS` of driving away and a leg plans in seconds — but
announcing arrival in open country is the worse failure.

**A stop inside the leg window is the boundary**, in preference to anything
`LegSplitter` invents. That is free rather than a tie-break: a stop is a point
the route must pass through whatever happens, so ending a leg there costs
nothing, while an invented cut bends both legs to reach somewhere nobody asked
to be.

A stop *outside* the window changes nothing. Before it, the window floor is
already past it and it travels in the first leg — `split` carries it. Beyond it,
the cut lands short and a later leg reaches it, which is right: forcing a first
leg long enough to include a stop most of a day away hands back the two-minute
plan splitting exists to prevent.

**Both of those were broken until August 2026, in ways that were silent.** The
guard meant to keep a cut from landing in front of a stop was implemented as
*plan the whole trip* — so a 900 km run with a charger 100 km in produced the
unsplit plan §7.10 measures at the fastest road and 43 cameras. And `split`
built the first leg as `[origin, cut]` while dropping the leading points of the
remainder, so a stop before the boundary was in neither list and was **deleted
from the trip**; it classified them by straight-line distance from the origin,
which misorders whenever the road bends. See F-43.

**Where the cut goes is otherwise the entire problem, and it is not a distance.** A leg
boundary is a hard waypoint both legs must touch, so it costs the difference
between the best route *through that point* and the best route overall. Cutting
at a fixed fraction puts it wherever it lands — and on a long trip the fastest
line runs through metros, because that is where the roads are. The maintainer put
the objection before a line was written:

> a leg ending […] somewhere in the middle of [a city] along the fastest line is
> going to end up getting dragged through a bunch of turns and stuff when it
> would be faster and easier to avoid.

So the rule is **cut where there is nothing to avoid.** A boundary is free
wherever every plausible route goes the same way anyway, and that is exactly a
stretch with no cameras near it: with nothing to dodge, the fastest road *is* the
fewest-cameras road, and pinning the route to a point on it constrains nothing
that was going to happen differently. Candidates are the spine points between
`MIN_LEG_METERS` and `MAX_LEG_METERS` along; the winner is the one with fewest
cameras within `QUIET_RADIUS_METERS`, ties going to the later one because every
boundary is a constraint nobody asked for. The city is then planned as one whole
leg with full freedom to route around it.

Camera count is the proxy for "nothing to decide here" — the same proxy and the
same argument as `WaypointExtractor.DENSITY_RADIUS_METERS`, and the only signal
available without planning the very routes the cut exists to make cheap.

`MIN_LEG_METERS` is really a deadline: it is how far the driver must travel
before the next leg is needed, and at 120 km/h 120 km is over an hour against a
leg that plans in about ten seconds. Arriving at a boundary with nothing beyond
it is the one genuinely bad outcome of splitting, and it should never be close.

**That deadline, not a driver's patience, is what bounds a later leg**, and
missing the distinction cost the last leg of long trips its avoidance.
`PASS_BUDGET_MILLIS` (75 s) is a patience figure — how long somebody will stare
at a spinner before giving up — and it has no bearing at all on a leg being
computed in the background while the car drives. Later legs get
`LEG_PASS_BUDGET_MILLIS` (5 minutes), which still lands inside the hour with an
enormous margin and buys the avoidance passes room to finish. This matters most
on the leg it used to hurt: the last one is the only leg ending in the driver's
real destination, so it carries the densest camera set and is the likeliest to
widen its corridor — and a widen costs the whole chooser a second time out of the
same pot (§7.10). A later leg that *still* comes back holding nothing but the
fastest road is written to the diagnostic log, because taking it silently is
indistinguishable from Shunt deciding there was no camera-free route.

**Measured on the 583 km benchmark trip that used to fail outright:**

| | whole trip | in legs |
|---|---|---|
| before the driver can set off | 75 s | **9.4 s** |
| what they get | fastest road, 43 cameras | 218 km, **0 cameras** |
| whole trip, all legs | — | 62.5 s, 776 km, 0 cameras |

And against the same trip planned whole with a 45-minute budget no phone would
ever spend — 130.7 s, 688.7 km, 0 cameras — legs cost **+12.7% distance** for the
same zero exposure. That is the real price of the boundaries, and it is worth
knowing before anyone tunes `MAX_LEG_METERS`: raising it means fewer boundaries
and less added distance, at the cost of a longer wait before the first leg.

**A later leg is planned from *inside* the leg before it, not from the boundary.**
That is the answer to the boundary problem rather than a repair for it. The
constraint doing the damage is Shunt's own — leg N+1 must start exactly where
leg N ended — and that point was chosen on the *direct* road before either route
existed, so leg N optimises arriving there, leg N+1 optimises departing with no
memory of how it arrived, and two individually optimal routes join badly. The
spur, the C-shaped detour and the loop around a camera are all that one
constraint.

`LegJoin.handoverInto` takes a point `HANDOVER_METERS` (15 km) back inside the
previous leg, with the bearing it arrives there on; the next leg is planned from
*that*, and the previous leg is published truncated to meet it. The router
re-decides the handed-over stretch as part of choosing its own first one, and the
join is a vertex of both lines by construction — nothing to trim, no proximity
threshold, no third routing pass.

Two conditions, both load-bearing:

- **The truncation happens at the moment the leg is planned**, not when the next
  one lands. So nothing that has reached the map or the car is ever revised, and
  §6.1 does not arise — there is no route in progress being changed.
- **The lead boundary is exempt.** The lead leg is the chooser's: shown, and
  possibly already pushed to the car. Truncating it *would* change a route in
  progress, and the car would still hold pins for a stretch the next leg has
  taken over. That one boundary keeps the trim and the seam re-plan below; every
  boundary after it is planned right rather than repaired. Finishing the job
  means giving the drive monitor a way to revise a leg it already has.

`rejoinAtBoundary` is skipped wherever a handover happened, which also saves the
three or four graph searches per boundary it spent to use one. The geometric
trim still runs everywhere — it is arithmetic, it finds nothing when the join is
clean, and a route drawn out and back over the same road is the one artifact a
driver definitely must not be shown. See F-43.

**Anything that reshapes a leg has to re-label it.** A route is described by what
it *passes*, measured from its line — and the trim, the handover truncation and
the seam splice all move that line after the measurement was taken. The first two
only remove road, so a stale label over-reports and errs safe. The splice adds
road neither leg drove, and a camera on it was drawn nowhere, counted nowhere and
announced never, on a leg presented as camera-free. `AppContainer.relabel`
re-measures whenever the line moved, and `DrivePlan.cameras` comes from the
reshaped leg rather than the route it was planned from. See F-44.

**The lead boundary can still cost a doubling-back, and that is trimmed rather
than prevented.** Because the cut is chosen on the *direct* road, the leg after it may
be planned freely from a point the camera-avoiding route never wanted to visit —
so the first leg drives out to touch the boundary and the second comes straight
back the same way. Both legs are correct; the spur is the overlap between them,
which neither can see. `LegJoin.trimDoubleBack` finds the last point the two
lines share and cuts both there, which is a trim rather than a re-plan: the join
is a vertex of the first leg lying on the second, so what remains is a sub-path
of what was already planned. Bounded to the join and to the tail of the previous
leg, because a route that legitimately doubles back later — a switchback, a
frontage road, a there-and-back to a charger — must survive intact. See F-24,
including what it deliberately does *not* do to a drive already under way.

**The chooser describes the whole trip as the legs land**, not just the first
one: a running total of time, distance and cameras, growing as each leg arrives.
It sits beside the option cards rather than inside them, and that is a
correctness point rather than a layout one — the cards are a choice *for this
leg*, while later legs are planned once and begin at the same boundary whichever
card is picked, so folding their distance into "Fastest" would describe a trip
nobody is being offered. See F-25.

### A leg's chain ends where the leg ends

`DrivePlan.chain` is the pins followed by the point the leg finishes at, and on
a split trip that is **the boundary, not the trip's destination**. `AppContainer`
has always built later legs that way and says why in a comment; the lead leg is
built in `PlanViewModel.drivePlanFor` and appended `destination.location`
regardless. Two failures follow from the one line:

- **The car is aimed at the destination the moment it passes the last lead
  pin** — hundreds of kilometres ahead, with no avoidance for anything between.
- **`extend` appends the next leg after it**, so the trip's end sits in the
  *middle* of the chain. The car is sent there, then back to leg two's first
  pin. That is the shape of "it navigated to a previous pin and then to the
  correct one" and of a detour nobody planned.

`remaining.first()` is the boundary — the point this leg stops at and the next
one starts from — so the append stays continuous and `isPartial` means what it
says. A trip planned in one go has no `remaining` and still ends at the
destination.

### What happens when the car adds a charging stop

The end-to-end path, because it is the least exercised thing in the app and the
easiest to get subtly wrong.

1. **Noticing.** While the car holds the final destination the read is free and
   runs every 45 s; while Shunt is steering it pin by pin the read costs a
   re-assert and is rationed by `ProbeWindow`. `classify` compares *positions*,
   never names — a destination more than `SAME_PLACE_METERS` from ours is a stop
   the car inserted.
2. **Routing to it.** `startChargeLeg` plans a camera-aware leg to the charger
   and the monitor pushes it, alerts `ChargeStopAhead` with the leg's camera
   count, and puts "Charging first at X" on the driving sheet.
3. **The driver's own stops go with it.** Those before the charger travel in the
   charging leg; those beyond it are **held in `stopsBeyondCharger`** and added
   back to the leg onward. They used to be deleted outright: the charge leg was
   planned with no stops, the monitor built a fresh engine from it, and the next
   check read `remainingStops` from a plan that had none. A stop the driver
   typed in must survive the *car* deciding to charge.
4. **Arriving.** `Arrived` on a charging leg is not the trip's end — the monitor
   says so, sets `ParkedAt`, and probes every minute for the car to decide what
   is next.
5. **Onward.** A read that comes back pointing at the real destination resumes:
   a fresh camera-aware leg, `ResumingToDestination`, and the carried stops.

**When it cannot be routed, the car is left alone.** `LegChange.Unroutable`
alerts that the car is detouring its own way with nothing protecting the leg —
and Shunt must then *not* re-aim it at our route. It did, which both contradicted
what the driver had just been told and steered the car off a charger it inserted
because it needs the charge. The next probe re-tries the leg and usually gets it.

### A leg the driver is already on can still be revised, and the car must hear it

Planning a later leg can shorten the leg *being driven*: `trimDoubleBack` cuts
the spur where a camera-avoiding route drives out to touch a boundary chosen on
the direct road and comes straight back. For later legs that revision is applied
to the plan itself. For the **lead** leg — the only one that can already be under
way — it was published to `trimmedLeadPolyline` and `trimmedLeadWaypoints`, which
only the map reads.

So the line on screen lost the spur while `DriveMonitor` carried on steering the
chain it was handed at Go, pins on the deleted spur included. The car was sent to
a point that was not on the route being drawn, and because a pin is a
destination it drove there — out and back down a road Shunt had decided not to
use. Reported from a drive as *"the next waypoint my car is being sent to is like
right on the flock camera… differing from the planned path"*, and then *"after
taking over to route the correct way it navigated to a previous pin and then to
the correct one"* — a spur pin the engine could not advance past, because
along-route progress never reaches a pin that is off the road being driven.

`LegExtension` carries the revision with the leg, so the monitor drops the
doomed pins from what is still ahead. Three things make it safe:

- **Only what is ahead is touched.** The filter is applied to
  `remainingChain()`, so nothing the driver has already passed is disturbed.
- **A revision that removes the current pin re-aims the car.** An ordinary
  extension is invisible to the vehicle — it appends, and the head of the chain
  does not move. A revision can take the head away, and then the car is holding
  a coordinate on a deleted road. Re-aiming is a change to a route in progress,
  which §6.1 says to be careful with; leaving the car pointed down a spur is the
  worse of the two.
- **The line goes with the pins.** Off-route detection and camera warnings are
  measured against `polyline`, and against an untrimmed one the spur still reads
  as part of the route.

This is the lead-boundary exemption in the roadmap, done for the trim. The
handover truncation still does not use it. See F-55.

### The chooser's selection is the whole trip's

Which makes where it *starts* a routing decision rather than a UI default, and
that was missed for a long time. `requestLaterLegs` plans every later leg to the
selected option's `choice`, and the chooser opened on index 0 — FASTEST. So a
driver who never touched it got a camera-aware first leg and a whole trip after
it planned as the plain fastest road. Reported twice, both times as "the last leg
still is taking the fastest route", and both times it looked like a bug in the
last leg. `PlanViewModel.defaultOption` opens on fewest-cameras, falling back by
camera count where that option does not exist — which is the app's purpose as
well as the fix.

**And the trade-off passed on is not the option's `choice`.** That is the name of
the pass that produced the geometry, and on a leg where every pass finds the same
clean road the options deduplicate to one card labelled FASTEST. Passing that
name on planned every later leg as the plain fastest road: measured from a real
log, a camera-free lead leg followed by legs of 0, 1 and **62** cameras.
`preferenceOf` asks whether the driver settled for more cameras than they were
offered instead — picking the least-watched option available, including when
there is only one, means fewest cameras for the whole trip. See F-46.

**Changing the selection re-plans every later leg, and that must not blank the
map.** Two things make it safe:

- **The legs already drawn are carried** and replaced one at a time as the new
  ones land, with leftovers dropped when the run finishes. Only when the
  remaining trip is *identical*, which is exactly the switch case — a genuinely
  new plan has different points, and showing it the last trip's legs would draw
  a road nobody is going to drive.
- **A cancelled run is not a stopped run.** `legJob?.cancel()` takes effect at a
  suspension point and **a BRouter pass has none** — it is a tight CPU loop
  bounded only by its own timeout. The abandoned run finishes the leg it was
  computing, up to a minute, and everything it writes on the way out lands on
  top of its replacement: it cleared `planningLaterLegs` (so the pending line
  vanished mid-plan), appended its leg to the new run's list, and could hand
  that leg to the drive monitor. `legRun` is a generation counter, and only the
  current run may write anything. See F-41.

**The line to the destination is drawn before it is planned.** A dashed,
semitransparent line runs from the end of what is planned to the destination pin
while later legs are still coming, following the direct road (`directAhead`, a
slice of the spine already computed to choose the boundary) rather than cutting
across country. It must never read as a route, which is what the transparency
and the movement are for.

**It follows the newest leg's slice, not the chooser's.** Every plan computes its
own `directAhead` from the boundary it cut at, and for a long time only the first
leg's reached the map — so the line described the road onward from a boundary the
driver was hundreds of kilometres past. Two faults came out of that, both
reported: past `SPINE_FULL_LIMIT_METERS` the first leg's spine is a *probe*, so
most of its slice is the straight estimate rather than road; and the
camera-avoiding legs wander off the direct road, so the line left the route
sideways to rejoin a chord measured from somewhere else. `laterLegDirectAhead`
carries the newest leg's, which starts exactly where the drawn line ends. See
F-42.

The movement is worth knowing about, because it looks trivial and is not.
MapLibre has no dash *offset* to animate, so it is faked by cycling dash patterns
whose solid run sits half a line-width further along each time — Mapbox's own
recipe, which is **fourteen** steps, seven walking the dash and seven walking the
gap. Shipping the first seven alone snapped the pattern backwards several times a
second. `PENDING_DASHES` carries all fourteen and `PendingDashesTest` holds the
cycle continuous. Two other rules go with it: the phase is not Compose state and
does not restart when a leg lands, and the map's render block skips rebuilding
GeoJSON it has already uploaded — a long route is thousands of points, and the
animation is the only thing on screen moving fast enough to show a dropped frame.

One thing the tiles make non-negotiable: **the trip's whole bounding box is
checked for missing tiles up front**, not the first leg's. Later legs are planned
while moving, possibly with no signal, so everything has to be on disk before the
driver sets off.

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
benchmark is memory-bound in a way a phone may not be. `-PshuntTestHeap=3g`
raises it. Do that before drawing conclusions, and remember the same constraint
is what makes concurrent routing a memory question rather than a correctness one
(§7).

**Pick the region tile that actually contains the trip.** DeFlock's regions are
20° squares named by their south-west corner, so a trip at 33°N, −97° is in
`20/-100.json`, not `40/-100.json`. Put exactly one `cams*.json` in the bench
directory — the benchmark takes the first it finds.

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
- `docs/verification.md` — the list of things believed fixed and never seen
  working in a car, with how to provoke each one. **When a fix lands without a
  drive to confirm it, it goes in there in the same commit.** Losing track of
  that list is how a beta ships with a bug that was already reported once.
- §7 above — mark problems fixed, add new ones.

If you finish a change and have not touched documentation, check again whether
that is really true.

**Commit identity:** `Claude <noreply@anthropic.com>`. (GitHub's own merge
commits show as `noreply@github.com` / "Unverified" — expected, don't amend.)

**Never commit personal location data.** See §3. This is the one mistake that
cannot be undone by a follow-up commit.

---

### Where this is heading (stated by the maintainer, August 2026)

Recorded because it shapes what "finished" means, **not** as licence to start any
of it. None of this should drive a decision on its own.

> I have a long term goal of making this app somewhat like waze, but with a
> modern (almost tron like) sleek electrical theme. Eventually I am going to want
> to give the user the ability to connect their osm account and be able to easily
> add flock cameras they come across as they drive, in the same way that Waze
> lets you report hidden police officers as you drive. Maybe something that lets
> you mark it down and then after your drive you can go back and make edits,
> where precisely it was, which direction it was facing, etc and then confirm.
> That is all for much later though, long after we have made the beta release.

Three things follow that are worth holding now, because they are cheap now and
expensive later:

- **Report-then-refine is the right shape** for camera contribution. A driver at
  70 mph can press one button; direction, exact position and tags are a job for
  afterwards, sitting still. Anything built here should capture a rough mark
  cheaply and edit it later, never demand accuracy at the moment of sighting.
- **An OSM account is the one credential that does not break §3.** It is the
  user's own, it grants nothing to this project, and it cannot be revoked *at*
  us — the same shape as the optional Tessie connection. Contributing back to
  OSM is also the answer this project already gives to missing places and
  untagged crossovers, so it closes a loop rather than opening one.
- **The visual direction is a theme, not a rewrite.** The map is already dark and
  the accent colours already read as electrical. Whatever happens there must not
  cost legibility at a glance — see the driving-text rule below.

### Text a driver has to read is a safety surface

> On a navigation app we cant have long paragraphs and lots of words to read when
> someone's driving. It needs to be simplified, and just give the data.

Taken as a standing rule rather than a one-off tidy-up. **A paragraph shown to
someone driving is not a stronger warning than a sentence — it is a weaker one,
because it does not get read.** The FSD caveat was five lines explaining exactly
which parts are unproven; it is now one line, and it is more likely to do its job.

The rule, concretely:

- Anything on the map, the result sheet, or the driving sheet gets **the number
  and the noun**. Reasons, caveats and history go to settings, the README or the
  field notes, where somebody is sitting still.
- The exception is a choice the driver is actively making — the camera list on
  the result sheet is long *because* it is the thing being decided.
- Settings screens may be as wordy as they need to be. Nobody reads those at
  70 mph, and the ones that change what the app believes about the world
  (practice cameras, camera reach) have to explain themselves.

**And the card itself is part of the map, not something on top of it.** Reported
from the road: *"the front info card covers everything, I need it to swipe down
most of the way and the pov centering needs to match the window that the card
isn't covering."* Two things follow, and the second is the one that would have
been missed:

- **The driving sheet collapses.** A handle drags it down to one row —
  destination, what Shunt is doing, camera count — and back up for the rest. Only
  while driving: everywhere else the sheet *is* the thing being read, and a
  chooser that swipes away by accident mid-decision is the worse trade.
- **The follow camera frames the strip the card leaves, and that inset is
  measured rather than guessed.** `frameDrive` padded symmetrically, so the box
  holding the driver and the next pin was centred in the *view* — behind the
  card. The sheet's height depends on the phase, its content, the screen and now
  on whether it has been swiped down, so `PlanScreen` measures it
  (`onSizeChanged`) and hands the number to `RouteMap`. `followBottomPadding`
  clamps it to `FOLLOW_MAX_INSET_FRACTION`, because a frame squeezed into what an
  open card leaves is worse than one partly behind it — and the driver who wants
  the map has a handle to pull. See F-52.

## 10. Roadmap

Ordered roughly by what unblocks real use.

- **[high] Confirm the concurrent passes and the denser pins on a phone** —
  §7.4 and §6. Long-route planning is confirmed working on a real device
  (489 km in 1 m 06 s, camera-free); what has not been seen on one is the
  concurrency (43 s vs 57 s on the benchmark) or the density-aware pins. The
  concurrency in particular wants watching for memory pressure on a real
  device rather than a container with 16 GB.
- **[high] Confirm leg planning on a real long drive.** Wired through and
  measured in the benchmark (§6, "Cutting a long trip into legs"), never driven.
  The things to watch are in `docs/verification.md` B4.
- ~~**[high] Don't hand the driver the fastest road when the widen runs out**~~
  Done: the previous round's routes are carried forward and re-labelled against
  the wider camera set. §7, "When the widen runs out, keep what was already
  found", and F-26. Measured on a real last leg into San Francisco: 126 cameras
  → 21.
- **[high] Charging re-route on long trips** — §7.1.
- **[high] Waypoint fidelity on the car** — §7.2. Getting a coarse location is
  worse than getting none, because it looks like it worked.
- **[high] Confirm the August 2026 vehicle fixes on a real drive.** Charging
  re-route, waypoint fidelity, standing down, the abandoned-road block, the
  camera guard pins, along-route waypoint advancement, the emergency-crossover
  gate, the watched-destination block (§7.11) and the camera-reach setting
  reaching the router (§7.12) were all diagnosed from field reports or the map,
  and none has been seen working in a car.
- **[high] Confirm the chooser's new default on a real trip.** It opens on
  fewest-cameras now, and nobody has to touch it for that to be what Go pushes
  to the car — so the thing to check is that the route the car receives is the
  camera-free one. §6, "The chooser's selection is the whole trip's", and
  `docs/verification.md` B11.
- ~~**[high] The leg boundary, done properly: an overlap handover.**~~ Done for
  every boundary **except the first**: a later leg is planned from a point inside
  the leg before it, which is then published truncated to meet it. §6, "A later
  leg is planned from *inside* the leg before it", and F-43.
- **[high] Finish the handover at the lead boundary.** The exemption is real
  work, not an oversight: the lead leg has been shown on the chooser and may
  already be pushed, so truncating it changes a route in progress *and* leaves
  the car holding pins for a stretch the next leg has taken over. It needs the
  drive monitor to be able to revise a leg it already has — a
  `reviseLastLeg(polyline, chain)` that drops the pins past the handover point
  without disturbing what the driver has already passed. Until then that one
  boundary keeps `trimDoubleBack` and `rejoinAtBoundary`, which is also why
  neither can be deleted yet.
- ~~**[high] `LegSplitter`'s stop handling has two verified bugs.**~~ Both fixed:
  a stop inside the leg window is now the boundary, and `split` orders stops
  along the spine and carries every one into the leg it falls in. §6, "A stop
  inside the leg window is the boundary", and F-43.
- ~~**[high] Ask the road graph what is near a pin.**~~ Done:
  `BrouterRouter.roadsNear` answers it from BRouter's own tiles, and
  `BrouterPlanner.onUnambiguousRoad` moves each pin to the position where the
  car's map has least to choose between. §6, "Asking the road graph what is
  actually near a pin", F-53 and F-58. **Wants confirming on a drive.** Its cost
  is now its own line in the planning breakdown (`PlanTimings.STAGE_ROAD_CHECK`),
  so the next plan on a real phone answers the one thing that could not be
  measured here.
- **Surface BRouter's own `indexInTrack` and snap distance.** `runRoute` throws
  away `track.matchedWaypoints`, which gives the exact index of each waypoint in
  the returned line, how far it had to snap to reach a road, and whether BRouter
  silently converted it to a beeline. That kills the distance-matching in
  `LegSplitter`, and the snap distance is a diagnostic the project does not have:
  a cut or a charger that snapped kilometres to reach a road is not where the map
  says it is. See F-42.
- **Pin refinement, done lazily** — compute the next few pins rather than all of
  them. Not a speed problem any more; a quality one, since a long route's pins
  currently share one budget between them.
- **OSM coverage:** keep improving nearby-first ranking; add missing local
  places to OpenStreetMap so they become searchable for everyone. Category
  search (§3) covers the common "I want *a* coffee" case; the phrase list in
  `PlaceCategories` is deliberately short and is the cheap place to extend.
  The Nominatim fallback now searches near the driver first and only widens to
  the world when that finds nothing — a `viewbox` alone is merely a preference
  and loses against a name with thousands of namesakes, while `bounded=1` alone
  would make a deliberately distant destination unfindable. See
  `NominatimSearch.suggest` and field note F-11.
- **Charging fine-tuning:** the reserve (`RESERVE_METERS`, now the only margin
  on the car's range estimate), the charger corridor, and the probe cadences are
  first-pass numbers — worth revisiting against real drives.
- On-route arrow, and gray out the traveled portion of the route.
- Simplify the current-location dot to a solid pulsing dot (no accuracy halo).
- ~~Fix one-way arrows pointing the wrong direction on the basemap.~~ Done:
  they were 90° out, because OpenFreeMap's `oneway` sprite is drawn pointing up
  while MapLibre aligns a line-placed symbol's *+X* axis with the line.
  `RouteMap.straightenOneWayArrows` adds the missing quarter-turn, and only when
  the style still carries the values known to be wrong — the style is fetched
  from a server this project does not control, so an upstream fix must not turn
  into a new bug here.
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
- ~~Seeded synthetic cameras for testing where the real ones are gone.~~ Done:
  `PracticeCameras`, switched on in vehicle settings.
- **Let the driver choose where a trip starts.** Everything plans from the
  current location, which makes a whole class of question unanswerable: the way
  to tell a leg boundary constraining an approach from a genuinely unavoidable
  camera is to plan that leg on its own, and there is no way to ask for it.
  *"I can't choose a starting point yet, it just navigates from my current
  location."* See `docs/verification.md` D16.
- **[far future]** Direct Tesla Fleet API integration (connect a Tesla account,
  no Tessie) — only after everything else is fleshed out and tested.
