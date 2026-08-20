# Field notes — observed real-world behaviour

A running log of what Shunt and the car actually did on real drives, as opposed
to what the code intends. Anything here was **observed**, not reasoned about.

This exists because the vehicle side of Shunt cannot be unit-tested against a
real car: the tests prove the client sends what we think it sends, not that the
car does what we think it does. Almost every genuinely surprising thing this
project knows about Teslas came from a drive, and would have been lost without
being written down.

**Privacy rule:** never record where a drive actually went. No coordinates, no
town or road names, no business names, no times that could pin a person to a
place. Describe the *shape* of the problem ("a long trip that needed charging",
"a waypoint behind the car"), never the trip.

---

## Open

### F-1 · Charging re-route does not fire on long trips
*Observed: pre-2026-08 build, several long drives. Cause found; fix landed,
awaiting a real drive to confirm.*

Expected: send a long destination, the car inserts a Supercharger, Shunt sees
that, and re-navigates to the charger via a camera-avoided leg — arriving there
is a leg end, not the trip's end.

Actual: the app shows the route, but the car just navigates to the final
destination. Shunt never updates when the car routes through a Supercharger.

**Cause.** `AppContainer.chargeStopCoordinator()` returned null whenever the
trip was being steered pin by pin, so on those trips nothing watched for a
charging stop at all. The reasoning was that a steered car is aimed a few miles
up the road and so cannot answer a question about the trip. That much is true —
but the conclusion did not follow. It means the question costs a *re-assert*
(hand the car the destination, read, put the steering back) rather than being
free, and the coordinator already implements and rations exactly that.

It was propped up by a second assumption: that steering is only chosen when the
trip has range to spare. The gate for that treats an *unknown* range as plenty,
and "not short" includes tight. Neither is a promise the car won't stop to
charge — and long trips are precisely where it will.

**Fix.** The coordinator is now told whether the trip is being steered, and
treats a steered car as one that does not hold the destination — so checks go
down the rationed re-assert path instead of the free-read path that could only
ever read back our own pin.

Surfaced while fixing it: when the car answered "going straight to the
destination", the coordinator returned "nothing changed" without restoring the
steering chain — leaving the car pointed at the final destination and the shaped
route silently abandoned. Only reachable while steering, which is why it had
never been reachable at all.

### F-2 · A waypoint can send the car to a different nearby location
*Observed: pre-2026-08 build.*

The waypoint drawn on the phone map sometimes puts the car somewhere else
nearby. It is markedly worse when the next waypoint is **behind** the car's
current position.

Maintainer's read, which is worth taking seriously: this feels like the old
Google-Maps-share-to-Tesla behaviour, where sharing a destination too quickly
navigated to the centre of the city, then the state, then the country — i.e. the
car received something coarse and resolved it itself. Possibly Shunt is
effectively naming the centre of a road or an area rather than a point.

Corroborating mechanism in the code: on a car that requires signed commands,
`TessieVehicleNavClient` falls through to Tessie's `share` command, which takes
a **string** the car resolves on its own — the same class of mechanism as the
Google Maps share. That is the first place to look.

**Two contributing causes found and fixed. Neither is confirmed to be *the*
cause; both were real.**

1. *The car was aimed at the wrong point of the chain.* While steering pin by
   pin, `ChargeStopCoordinator` restored steering by sending the whole remaining
   chain. A single-destination car collapses a chain to its **last** point — the
   trip's destination — so every probe that found nothing changed quietly
   re-pointed the car at the end of the trip while the phone went on showing the
   next pin. `DriveMonitor` already aimed correctly; the coordinator bypassed it.
2. *Coordinates could reach the car in scientific notation.* The share value was
   built from Kotlin's default `Double` rendering, which emits `9.5E-5` for
   small magnitudes. A consumer that can't parse that as a number may fall back
   to treating the value as a place name — precisely the "navigated to the
   middle of a town" failure. Now always plain decimal degrees to six places.

**To confirm on a real car**, the experiment that would settle it: push a
destination whose coordinates sit clearly *between* named places (not on a
building or a road centre), then read the active route back and compare
`active_route_latitude`/`longitude` with what was sent. If the car reports a
different point, it is re-resolving the string and the fix is to stop using
`share` for shaping — not to nudge the coordinates.

### F-3 · A closed road was routed onto, and leaving it went badly
*Observed: pre-2026-08 build. Half addressed.*

The route used a road that was closed. Once the driver left it, the app did not
cope well.

Two separate needs fall out of this:

- **Direction of travel on a re-plan — done.** An answer that begins with a
  U-turn is not an answer at 60 mph. The GPS fix's bearing now flows from
  `DriveLocationUpdates` through `DriveMonitor.headingOf` (null when stopped, so
  a parked car's noise bearing can't pin the route) into `BrouterRouter`, which
  sets BRouter's `startDirection` with `forceUseStartDirection`. Shipped in the
  work merged 2026-08-09.
- **A road the driver cannot use should stop being offered — done.** The stretch
  of route immediately ahead of where the driver left it is now handed to the
  router as impassable, so the re-plan cannot put them back on it.

**How the missing half was built.** `RouteRequest.blocked` carries points the
router treats as impassable, separate from cameras (those are field-of-view
shapes; these are plain circles). `DriveMonitor` fills it with `stretchAhead` of
the route from where the driver left, 4 km at 100 m spacing — spacing under
twice the blocking radius, because a gap between circles is a thread the router
will happily use.

Three judgement calls worth keeping:

- **Only the stretch ahead**, not the whole remaining route, which would block
  the trip rather than the road.
- **Never persisted.** A road closed this afternoon is open tomorrow, and Shunt
  has nowhere to keep that belief and no business trying.
- **Dropped rather than failing.** Blocking is a heuristic and in a town it can
  take a parallel street with it; if routing comes back empty, `BrouterPlanner`
  retries without the block. No route is a worse answer than a route back onto a
  road the driver refused.

**What "did not cope well" actually was** (maintainer, 2026-08-09), and it is
worse than any of the guesses above:

> It kept sending the waypoint back to my car repeatedly every time it
> rerouted, so when I tried to override it on my car it would override me
> trying to override it, until I cancelled the navigation in the app.

Plus: it kept routing back onto the closed road, and the alerts would not stop.

So this is not only a routing bug. **Shunt was fighting the driver for control
of their own car and winning.** The loop: the route wants the closed road →
driver leaves it → off-route → re-plan → push → the car turns back toward the
closed road → driver leaves it again. Every turn of that loop overwrote whatever
the driver had just set on the car's own screen, and the only way out was to
cancel navigation in the app — which is not something anyone should have to work
out while driving.

**Fixed: Shunt now stands down.** More than three re-plans in five minutes and
it stops commanding the car entirely and says so once — no more re-plans, no
more pushes, no waypoint advancement. Camera warnings continue, because they
cost the car nothing and are the half of Shunt that still works when the route
has lost the argument. It is deliberately one-way for the rest of the drive:
Shunt cannot observe "the road stopped being closed", so there is nothing that
should re-earn control automatically.

Three is well above what an ordinary drive produces — a missed turn re-plans
once — and well below the number of overrides it takes for a driver to notice
they are being fought.

**The alerts that would not stop had the same root**, and it was not the alert
code. A re-plan builds a fresh `DriveMonitorEngine`, and a fresh engine had no
memory of which cameras it had already announced — so every camera still in
range was warned about again. One re-plan, one repeat; a loop of them, an alert
storm. The replacement engine now inherits what the old one had already said.

Worth knowing for anything else that replaces the engine mid-drive: state that
exists to stop repetition has to survive the replacement, or it is not doing
the job it was written for.

### F-4 · Long routes take minutes to plan
*Observed: pre-2026-08 build. **Confirmed fixed on a real phone, 2026-08-10** —
489 km in 1 m 06 s with a camera-free route. Changes since that reading
(concurrent passes) are benchmark-only and want a phone.*

A route of about 5 hours took roughly 5 minutes to calculate. Unusable for real
driving, and dangerous where mid-drive re-planning is involved, since a re-plan
that takes minutes arrives long after the decision it was needed for.

Cause found so far: pin refinement was unbounded. It costs one full pass over
the road graph per candidate pin per option, and a long camera-dense trip wants
dozens — while the route itself is already decided before refinement starts.
Three fixes landed: the fastest option is no longer refined at all (it is the
road the car picks anyway), legs are memoised across options, and refinement now
has a time budget it settles within — 20 s parked, 4 s while moving.

**Confirmed on a real device (2026-08-09):** the slow stage is **"Planning
routes"** — the route-deciding passes, not pin refinement. So the fixes above
addressed the part that was not the bottleneck.

A temporary breakdown now appears on the result sheet ("Planned in …"), splitting
the time by stage and the routing stage by what each search over the road graph
was for. Two readings to take from it: whether `(widen N)` passes appear, which
would mean the whole graph is being re-searched because the routes left the
camera box; and whether one avoidance pass dominates, in particular a `blocked`
pass that fails and forces the `fewest (fallback)` pass — two searches for one
option. Remove the instrumentation once this is resolved.

**Measured after the corridor change**, same phone, three trips:

| Trip | Before | After | Routing stage |
|---|---|---|---|
| 241 km | 59.3 s | 10.4 s | 51.9 s → 6.4 s |
| ~400 km | 1 m 49 s | 25.4 s | 93 s → 16.1 s |
| ~470 km | 12 m 46 s | 41.0 s | 12 m 05 s → 31.8 s |

No `widen` on any of them, and camera lookup fell to 0.0 s (cached). Usable.

**On computing pins lazily** (maintainer's suggestion — only work out the next
few rather than all of them): half of it is already true by accident, and worth
not undoing. `WaypointRefiner.refine` rescans the chain from the start on every
pass and stops at the first leg that needs a pin, so it resolves the trip
front-to-back. When the budget runs out, what you have is the *early* pins —
exactly the ones about to be driven — and the later stretches unrefined.

The missing half is refining further ahead as the drive progresses. That needs
the drive monitor to call back into refinement and push the results, which is
the same path that fought the driver for the car, so it wants building carefully
and confirming on a drive rather than at a desk. Note also that pins are no
longer a speed problem (3.8–9.2 s of a 10–41 s plan) — the reason to do this now
is *quality* on long routes, where one budget is shared across every pin.

**Chicago, from a real phone (2026-08-09):** 1 m 20 s, where it previously ran
long enough to be abandoned. The budget worked, and the breakdown showed exactly
what it cost:

```
fastest (spine)   4.9 s      blocked          (skipped — over budget)
fastest           4.7 s      fewest (fallback)(skipped — over budget)
balanced         42.6 s
```

**Read wrong the first time, and the breakdown is why.** That `balanced 42.6 s`
line looked like a pass that worked. It was not — it hit its own timeout and
returned nothing. A timed-out pass and a successful one had exactly the same
shape on screen, so the option the driver was actually offered was the plain
`fastest` road, and the summary sent back to them said "balanced". The
instrumentation built to stop an option going missing silently was itself
hiding one. Passes now say how they ended.

**And the ordering was backwards.** `balanced` ran before `blocked`, so on the
trips where the budget actually binds — long ones into dense metro, precisely
where avoidance is worth most — the convenience option spent the whole allowance
and the *product* was never attempted. Fewest-cameras now goes first, capped
short of the whole budget so the fallback that covers its failure still has
room.

So the trip completed and the driver got a route — but not a camera-free one,
which is the app's whole point. And the passes only account for 52 s of the 80 s
in the routing stage. **The missing ~28 s was labelling, not routing.**
`toResult` counted cameras by asking each one to walk the whole route — cameras
× points, once per option — and that happens *inside* the budget, so it was
spending the hard-block pass's time without doing any routing with it. Both
counts are indexed now, which should hand `blocked` back the room it needs.

**Chicago again, after reordering (2026-08-09):**

```
fastest (spine)                    5.6 s
fastest                            5.0 s
blocked (no route)                41.9 s
fewest (fallback) (no route)      27.8 s
balanced (skipped — over budget)   0.0 s
```

Two things in that, both actionable.

`fewest (fallback) (no route)` cannot be literally true. It is a *weighted*
penalty, not a block — every road stays passable — so if `fastest` found a route
in 5 s, the fallback has one too. It ran out of time and was mislabelled:
BRouter catches its own timeout and reports it through `errorMessage` rather
than letting the exception out, and the code only looked for it in the throw.

And `blocked` spent 41.9 s proving something knowable in microseconds. **A hard
block cannot begin or end inside a zone it blocks**, and in a city centre the
destination is very often within sight of a camera. That is both the case where
the block is guaranteed to fail *and* the case where failing is most expensive,
because a failing block exhausts every reachable road before concluding. Those
41.9 s are what starved the fallback.

Endpoints are now checked first, which is sound rather than merely likely: the
nogo shapes are built to *contain* what `CameraVision.sees` covers, so a point
that is seen is certainly inside the block. A point that is not seen may still
be, and that case runs exactly as before.

**Chicago, third attempt (2026-08-09).** Honest labels at last:

```
fastest (spine)                            3.5 s
fastest                                    3.4 s
blocked (gave up — out of time)           42.9 s
fewest (fallback) (gave up — out of time) 28.5 s
balanced (skipped — over budget)           0.0 s
```

The endpoint check correctly did *not* fire — the destination is far enough from
any camera that a hard block was worth attempting. Both fewest-cameras passes
simply ran out of time.

**What the numbers say about where the time goes.** `fastest`, carrying no
cameras, is 3.4 s. `balanced` (a finite penalty) and `blocked` (impassable) are
both ~43 s on the same graph. Those two have completely different search spaces
and near-identical cost, which rules out the search being the expense: what they
share is *checking every expanded link against every zone*. **The number of
zones is the cost.**

And the camera list shows why there are so many: six Illinois State Police
cameras inside about thirty metres, all watching one junction. Six zones
describing one piece of road. A real ALPR site is not one camera.

So cameras are now grouped into one shape per *site* before they reach the
router. Deliberately conservative, because merging may only ever grow what is
blocked: members must be within 35 m **and** agree on where they are looking.
Cameras facing different ways stay separate — facing is what lets a route pass
*behind* a camera, and merging that away would delete real roads from
consideration.

`NogoCoverageTest` sweeps every point each member can see and asserts it falls
inside the merged polygon, and asserts the merge actually happened — otherwise
it would pass just as well on no merging at all.

**Clustering barely moved it** (42.9 → 42.2 s), which falsified the idea that
grouping gantries would be enough. Two reasons, and the second is the real one:
the threshold was 35 m while the observed site spans 42 m, so it mostly did not
fire; and even fired perfectly, a handful of junctions is nothing against the
number of cameras in the set.

**The set is the number that matters.** At a 60 km half-width — inherited from
the tile margin, never chosen for cameras — a 489 km trip drew from about
59,000 km², which through this part of the country means three metro areas. At
15 km it is about 15,000 km². Safe to narrow *only* because the fixed-point loop
verifies: a route leaving the corridor is never labelled, the spine grows, and
it plans again. Too tight costs a second pass; too wide costs every trip.

Also fixed here: the budget belonged to a single routing *call*, and planning
makes several — the spine, then one per widen — so each got a fresh allowance
and the real worst case was a multiple of the number nominally in force. One
deadline now covers the whole plan.

**Root cause, finally measured rather than inferred (2026-08-09).** With network
access to the tile CDN, the trip can be planned locally against real tiles and
the real camera set, and the answer was not the corridor, nor clustering, nor
concurrency. `RoutingContext.calcDistance` runs for every link the search
expands and scanned the *entire* nogo list each time. BRouter is built for a
handful of hand-drawn nogos; Shunt hands it thousands of cameras.

Indexing that loop (`btools/router/NogoIndex.java`):

| 490 km trip, whole call | full scan | indexed |
|---|---|---|
| 608 nogos (2 km corridor) | 221.7 s | 28.1 s |
| 1181 nogos (5 km corridor) | 422.3 s | 30.2 s |
| 2349 nogos (15 km corridor — the default) | 1036.5 s | 36.1 s |

And the thing worth knowing beyond the numbers: **a camera-free route to the
metro destination existed the whole time.** Every "gave up — out of time" was a
successful search being cut off, not an absence of an answer. At the 15 km
corridor the trip now returns all three options in about 36 s, the
fewest-cameras one passing zero cameras for roughly 11% extra distance.

Verified answer-preserving by planning the same trip with and without the index
and comparing the geometry point for point — identical fingerprint, 7.9× faster.

**Still not usable at the top end.** A route from the Upper Peninsula to Chicago
still runs long enough that the maintainer closed the app rather than see it
finish — so its breakdown has never been observed, which is the first problem to
solve rather than the second. Planning now abandons the remaining avoidance
passes once `BrouterRouter.PASS_BUDGET_MILLIS` is spent and names each one it
dropped in the breakdown, so that trip should come back with *something* and,
more importantly, with an account of where the time went.

Two hypotheses worth separating when that breakdown arrives:

- **`fewest (fallback)` appears.** Then the hard-block pass is failing — likely,
  since a camera-free route into a dense metro may not exist — and the trip is
  paying for two exhaustive searches. A failed blocked search is the worst case
  there is: it explores everything reachable before concluding nothing works.
- **No fallback, just large numbers.** Then it is raw scale. The lever is
  concurrency, and its safety is now established rather than assumed — see
  CLAUDE.md §7. BRouter's `ProfileCache` is synchronized and carries a
  `profilesBusy` flag specifically to keep two threads off one profile context,
  so the engine expects concurrent use. The real constraint is phone memory:
  each engine builds its own tile cache. Cap at two and measure.

**The first attempt at a budget did nothing**, and the reason is worth keeping.
It checked the clock *between* passes — but a BRouter search is a tight CPU loop
with no suspension point, so nothing outside it can interrupt it, and the check
never gets a turn while a single pass is the thing running long. The maintainer
reported planning still running well past the budget, which is exactly what that
design produces.

BRouter has its own `maxRunningTime`, tested on every node it expands, and
Shunt was passing **zero** — which BRouter reads as *no limit*. That had been
the value since the engine was vendored. Each pass is now given whatever is left
of the budget, and the value handed over is never allowed to be zero, because
zero is indistinguishable from "unlimited" at that seam.

**Known gaps:** the budget covers one `route()` call, and planning makes more
than one (the spine pass, then the avoidance passes, then a widen if the routes
escape the corridor), so the true worst case is a small multiple of it. And the
budget is a property of the router, so a mid-drive re-plan gets the same
generous allowance as a driver sitting at the kerb. The refine budget is already
split that way; this should be too. If that is still slow, the remaining levers are
concurrency across the avoidance passes and shrinking the nogo set — see
CLAUDE.md §7 for why the second one is dangerous.

#### "On my end I saw no improvements" (2026-08-10)

The phone showed `blocked` and `balanced` finishing at 20.5 s each — the index
working — and then a `(widen 2)` round that timed out, leaving the driver the
fastest road alone. The corridor narrowing had outlived its reason. It was made
narrow *because* the camera set was the cost of routing; the index removed that
relationship, so the trade was backwards, and a widen is not a slightly wider
camera set but the whole chooser run again out of the same budget.

Measured on the 490 km trip with the index in place:

| corridor | cameras | passes |
|---|---|---|
| 15 km | 2,349 | 36.1 s |
| 30 km | 3,580 | 37.5 s |
| 60 km | 5,395 | 40.7 s |

Four seconds for four times the cameras. `CAMERA_CORRIDOR_METERS` is back at
60 km, and the 615 km trip plans with no widen at all.

#### The pins were five times the routing (2026-08-10)

With the widen gone, the 615 km trip planned end-to-end for the first time
against real tiles — and the breakdown said something nobody had seen, because
until now the routing stage had always dominated:

```
Finding cameras     0.1 s
Planning routes    52.3 s
Placing pins      349.2 s      <- against a 20 s budget
```

Two bugs, both the same shape as passing zero to BRouter's own timeout, one level
up:

1. `WaypointExtractor.pinAgainstShortcuts` asked every avoided camera whether it
   saw a chord. A chord early in that loop spans most of the trip and a camera
   walks a line at ten-metre samples, so one check is (trip ÷ 10 m) × cameras —
   and there is a check per insertion. It goes through `CameraIndex` now.
2. Neither extraction nor the refiner's leg routing carried a ceiling. The
   refiner checks its clock *between* legs, which bounds nothing when one leg
   runs long, and each leg fell back to the router's default: a whole pass
   budget, per leg. Legs now get what is left of the refinement deadline, and
   extraction shares it.

Fixing (2) alone changed the total by 7 s, which is what identified (1) — the
time was not in routing at all.

**Same trip after both: 72.8 s** (routing 52.3, pins 20.2 — the budget, exactly),
all three options, and *more* pins than before (49 and 37 against 33 and 17)
because the time is no longer wasted. This is the trip the maintainer abandoned
after twenty minutes. Measured in the sandbox against real tiles, not on a phone;
the phone number is the one that counts.

#### Confirmed on a real phone (2026-08-10)

489 km into dense metro, **1 m 06 s**, all three options, fewest-cameras
genuinely camera-free — and the maintainer zoomed in and verified the route
against the camera layer rather than trusting the label. No `widen`. The
breakdown read:

```
Finding cameras   0.2 s      fastest (spine)  3.7 s
Planning routes  51.2 s      fastest          3.4 s
Placing pins     14.5 s      blocked         22.0 s
                             balanced        21.6 s
```

That closes F-4 as a *planning speed* problem. Routing is now three quarters of
the time, and the two remaining questions were answered by measurement rather
than argument.

**Is avoidance slow because of the nogo lookup, or the search?** Displacing every
camera six degrees north keeps the nogo count identical while removing them from
anywhere the route would go — same per-link work, `fastest`'s search space:

| 615 km trip, 5,388 nogos | real positions | displaced |
|---|---|---|
| `fastest` | 3.6 s | 3.5 s |
| `blocked` | 16.0 s | 4.0 s |

Half a second of lookup, twelve seconds of genuinely larger search. **Indexing is
finished as a lever** — worth knowing before someone spends a week on it.

**So the passes now overlap.** `blocked` and `balanced` are independent; on the
615 km trip routing fell 38.5 s → 24.2 s and the whole plan 57.1 s → 42.7 s, with
all three options identical to the metre. The cost is memory — peak heap 230 MB →
302 MB, one tile cache per lane — so the app asks `ActivityManager.getMemoryClass()`
and stays sequential on a device without room.

### F-5 · Pins are too sparse where the streets are dense
*Observed: 2026-08-10, from the same drive-planning session. Addressed; unconfirmed on a drive.*

> In denser cities with more possible turns and more Flock cameras there's a
> higher likelihood that the car may pick an unexpected path that strays from the
> waypoint and it may be too late and pass by a Flock camera without warning.

Two constants, both single values tuned for open road, both wrong the same way in
a city. See CLAUDE.md §6 for the full reasoning; the short version:

- **Pin spacing was 800 m everywhere.** The real constraint is the drive
  monitor's lead distance, `max(150 m, speed × 18 s)` — pins closer than that are
  one constraint, not two. At highway speed that is ~550 m; at city speed ~230 m.
  So 800 m was discarding pins that would have worked, and *the refiner's pins
  first*, since those sit `PAST_FORK_METERS` past a fork and are inside 800 m by
  construction.
- **The pin went 250 m past a fork.** In a grid that can be past the next
  junction or two, so the car has turns available and still arrives at the pin.

Both now slide with local camera density, to 250 m and 120 m. Camera count stands
in for junction density — a polyline cannot see side streets, and ALPRs go where
the junctions are.

Safe to tighten because the refiner **verifies rather than assumes**: a pin
placed too early is caught next iteration when the leg still strays, and another
goes in; a pin placed too late is not caught, because the leg looks clean. The
two failures are not symmetrical.

Measured on the 615 km trip: fewest-cameras 44 → 51 pins, the additions in the
dense final tenth, routes unchanged.

**A worry that measurement killed.** The obvious suspicion was that the 20 s
refinement budget was truncating pins at the far end of the trip — which on a
trip *into* a metro is the dense end. It is not: re-run with a 120 s budget,
refinement still settles in 19 s with byte-identical pins. It reaches a fixed
point. The pin histogram by tenth of trip also shows the concentration is already
where it should be (`0 0 4 1 4 3 3 5 6 25` on the fewest-cameras route).

### F-6 · A red range warning on a trip that plainly fits
*Observed: 2026-08-10. Cause found and fixed; unconfirmed on a drive.*

> I definitely can make it to Wausau on a 55% charge idk what's going on there.

240.9 km route, 55% battery, and Shunt said "about 177.6 km of usable range".

Shunt reads `est_battery_range` — the figure Tesla computes from *recent
consumption*, so it already has real driving in it. On top of that a 0.75
"real-world" derate was applied, whose KDoc described deraing the **EPA-rated**
range. Sound reasoning, wrong field, and the two compounded: the car's own
estimate was 258 km, which fits the route with about 17 km in hand.

The estimate is now taken at face value and the entire margin is `RESERVE_METERS`
(16 km), so there is one number to reason about rather than two multiplying. The
same reading now reports **tight** — makes it, not by much — which is both the
honest answer and the driver's. A unit test pins that exact reading.

Worth keeping in mind for anything similar: a warning that fires on trips that
are plainly fine is not a conservative warning. It is one people learn to
dismiss, and that costs the real ones too.

### F-7 · Concurrency shipped switched off
*Observed: 2026-08-10, from the breakdown on a real phone.*

The two avoidance passes were meant to overlap. The breakdown read
`spine 4.0 / fastest 2.9 / blocked 17.9 / balanced 18.0` against a routing stage
of 43.4 s — which is their *sum*, so they ran one after the other.

`ActivityManager.getMemoryClass()` is not the device's RAM, it is the per-app
Java heap ceiling, and 256 MB is a common value on phones with 8 GB or more. The
gate demanded 384, sized from a peak-usage reading taken in a container with a
lazy collector and no ceiling to push against, so it ruled out hardware that runs
this comfortably — and nothing looked wrong, because a sequential plan is a
correct plan.

Re-measured the way the question is actually posed: the 615 km plan run *under* a
256 MB cap. It completed, peaked at 235 MB, returned the same three routes, and
took the routing stage from 44.5 s to 24.1 s. The 302 MB was uncollected garbage,
not demand. Gate is 256 now.

**A general lesson worth not relearning:** a capability gated on a threshold that
never passes fails silently and looks like the feature not being worth much. If a
gate exists, something has to be able to say which side of it a device landed on.

### F-8 · Pins in the wrong places, both directions at once
*Observed: 2026-08-10. Addressed; unconfirmed on a drive.*

> Some pointless waypoints being put one after the other on the same straight
> road, and other long stretches where it seems like there isn't enough
> waypoints to expect that the car is going to follow it.

Both halves turned out to be the same missing rule, and it is the drive monitor
rather than the geometry. The monitor advances to the next pin once the car is
within `max(150 m, speed × 18 s)` of the current one, so:

| speed | monitor re-aims at | pin 250 m past a fork |
|---|---|---|
| 30 mph | 241 m out | 121 m *before* the fork |
| 45 mph | 362 m out | 112 m *before* the fork |
| 70 mph | 563 m out | 313 m *before* the fork |

`PAST_FORK_METERS` had been 250 m for most of the project, so above about 35 mph
a pin was abandoned before the car reached the turn it existed to force. That is
the under-pinned half — and it was invisible, because the refiner verifies that
the car *routes* to a pin correctly and has no model of the monitor dropping it.

(An earlier attempt at this made it worse: a `DENSE_PAST_FORK_METERS` of 120 m,
reasoning that a city turn is committed immediately. True of the car, irrelevant
to the monitor — at 20 mph it re-aims 161 m out, so the pin was abandoned 41 m
before the fork. Wrong at every speed.)

The constants are now the lead distance at the speed each stretch is driven —
600 m open road, 250 m dense — and spacing is paired to them, because a spacing
floor wider than the fork distance discards the refiner's own pins. Bracketed:
`lead ≤ spacing ≤ past-fork`. A test in `:app` holds it, since only that module
can see both sides.

The *over*-pinned half is now handled by evidence rather than a rule.
`pruneIdlePins` removes a pin when routing the merged leg the way the car would
shows it doing nothing — no camera reached, and the car still on our line within
60 m. That second condition matters: on cameras alone, pruning cut the balanced
option from 52 pins to 25 by letting the car pick its own camera-free way between
sparse pins, which is not the route on the screen and reads as off-route to the
monitor. With it, 27.

**The cost is a deeper dependence on BRouter modelling the car**, since pins are
now dropped on its word that the car would follow the road anyway. If a car
strays somewhere a pin used to be, suspect that first.

### F-9 · Five things from a real drive
*Observed: 2026-08-10, second drive. All addressed; none confirmed on a drive.*

**It pulled the car out of a turn lane.** Stopped at a red light in a centre
lane waiting to turn, a little short of a waypoint just past the junction. The
monitor's lead floors at 150 m for crawling traffic, the car was inside it and
stationary, so the waypoint was advanced past — and the next one was straight
ahead, so FSD moved to leave the turn lane.

The driver's suggested fix was to scale the advance radius with speed. It already
does (`max(150 m, speed × 18 s)`); the floor is what bit, and the floor is there
for a good reason — the car treats a waypoint as a *stop* and slows for it, so
advancing late is its own failure. The fix is a different question: not *how
close* but *whether the turn is behind us*. `DriveMonitorEngine` precomputes the
last sharp bend before each waypoint and refuses to advance until the car is past
it. **A waypoint dropped before its turn is worse than no waypoint — it steers
the car the wrong way, at a junction, under assistance.**

**The car took a different route than Shunt expected.** The refiner only asked
whether the car's own path entered an *avoided camera*. A different road that
happens to be camera-free passed that test, so nothing pinned it. It now asks
both — camera or divergence from our line — and `pruneIdlePins` uses the same
predicate negated, so the two agree. See CLAUDE.md §6.

Two things fell out of that. It has to *walk* each leg rather than check its
vertices — a car path's vertices can all lie on the planned line while the road
between two of them goes elsewhere, the same trap `sampleSpine` fell into. And
the phase got slower, so its budget went 20 s → 45 s; it converges in ~34 s on
the 615 km trip. At 20 s it was cut off, which showed up as *more* pins than the
converged answer, because pruning never ran.

**It auto-navigated before charging was worked out.** `rangeCheck` was null both
for "no claim can be made" and for "still reading", and Go treated both as
"plenty of range", so it set off steering pin by pin on a trip that may have
needed a charge. `checkingRange` separates them and Go waits, bounded.

**No audio at all.** Alerts were vibration and a notification — the wrong channel
for the one moment they matter, and unusable under FSD where reading a phone is
the last thing a driver should do. `SpokenAlerts` uses Android's on-device TTS
(offline, no key) with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, which is what
makes a phone route audio into a car over Bluetooth and duck music. Speech is
best-effort and never load-bearing: every alert still vibrates and notifies.

**No idea what the app was doing.** Waypoint pushes, charging probes and
re-plans were all silent unless they failed. `DriveActivity` puts the current one
on the driving sheet — *sending waypoint 3 of 12*, *asking your car about
charging*, *re-planning from here*, *not steering — the car is yours*. A driver
who cannot see it working cannot tell a quiet moment from a broken one, and
cannot describe afterwards what it did.

### F-10 · One-way arrows point the wrong way
*Observed: long-standing, reported again 2026-08-11. Fixed; wants an eyeball on a phone.*

Not Shunt's data — the arrows come from the OpenFreeMap basemap style, which is
fetched at run time. Worth writing down because the answer was not the obvious
one and because the method generalises.

The style's two layers are internally consistent (`oneway == 1` → `icon-rotate: 0`,
`oneway == -1` → `180`), so the natural guess is that the arrows are *reversed*
and want another 180°. They are not. Decoding the sprite sheet shows the `oneway`
icon is an arrow drawn pointing **up**, while MapLibre's `symbol-placement: line`
aligns a symbol's **+X (right)** axis with the direction of the line.

Confirmed by rendering that sprite and layout over lines of known bearing in
headless Chromium — the same style, the same sprite, lines this end authored:

| line runs | arrow points |
|---|---|
| west → east | north |
| east → west | south |
| southwest → northeast | northwest |

Consistently 90° anticlockwise. Note the first two are opposites, which settles
the question the layers raised: *direction* is honoured, it is the axis that is
wrong. Re-rendered at `icon-rotate: 90` the arrows land on the road.

`RouteMap.straightenOneWayArrows` adds the quarter-turn after the style loads,
**and only when the layers still carry the broken values.** The style belongs to
someone else; if it is corrected upstream, an unconditional patch would silently
create the mirror-image bug, and nobody would go looking for it.

The general lesson: a 180° guess would have looked plausible, shipped, and been
wrong. Rendering the actual sprite against a line of known bearing took a few
minutes and gave a number instead of a hypothesis.

### F-11 · Searching for places is a headache
*Observed: 2026-08-11. Category search added; wants using in anger.*

> let's try to find a different way to query places because it's genuinely a
> headache with how it is now. Is Google really going to be the only option or
> is there a privacy preserving option?

Short answer: no, Google is not the only option, and the problem turned out not
to be coverage.

Measured against the public instances from a neutral point in Kansas. For
**brand names** the keyless stack is already good — Photon put Walmart,
Starbucks, Taco Bell and QuikTrip within 0-7 km, which is what you want. For a
**kind of place** it collapses:

| typed | Photon | Nominatim |
|---|---|---|
| `coffee` | — | Coffee County, Alabama (1,490 km) |
| `gas station` | "Gas Station (Not)", a farm track (219 km), then Korea | Gas Station, 8,987 km |
| `grocery` | shops called "Grocery" in Dubai (12,465 km) | Grocery, 2,039 km |

That is not a ranking bug. Both are *text* geocoders and both answered the
question asked: find things named that. The question a driver meant — a cafe,
near me, now — was never being put to anything.

Three keyless ways to ask it properly, and only one survived:

- **Overpass**, which the app already uses for Superchargers. Rejected on
  measurement: 30-40 s per query and frequent 504s/resets across three public
  endpoints. Fine for a one-shot charger lookup, hopeless for a typeahead.
- **Photon's search endpoint with `osm_tag`.** The filter works — every result
  really was `amenity=cafe` — but `q` is still matched against *names*, so it
  returned cafes that happen to be called "Coffee". Wrong question, narrower.
- **Photon's `/reverse` endpoint with `osm_tag`.** Takes the filters *and* a
  radius and needs no query text at all. `grocery` → Dillons 2.6 km, La Tapatia
  3.7 km. `toilets` → the restrooms 1.2 km away. About a second, same host,
  same keyless terms.

So `PlaceCategories` maps the words a driver types to OSM tags and
`PhotonSearch.nearby` asks the reverse endpoint. Whole-query matching only:
"Bank of America Stadium" is not a request for a cash machine and "Food Lion" is
a supermarket chain, so a substring rule would hijack real searches. An empty
result falls through to the name search rather than showing a blank screen —
in open country there may genuinely be no cafe within reach.

**The Nominatim fallback, fixed the same day.** It was returning cafes 666 to
11,500 km away for "starbucks", including one in Japan — and it only runs when
Photon finds nothing, so it was worst exactly when it was the last resort.

It already sent a `viewbox`, which turns out to buy almost nothing: a viewbox is
a *preference*, and against a name with thousands of namesakes the preference
loses. The local Starbucks were not ranked badly, they were **not in the
response at all**, so no amount of re-ordering could have rescued them.

`bounded=1` fixes that outright — 0 km, 5 km, 5 km on the same query — and would
have been the obvious one-word change, except that measuring the other half of
the job showed it breaks it:

| query, typed from Kansas | bounded | unbounded |
|---|---|---|
| `starbucks` | 0 km, 5 km, 5 km | 889 km, 920 km, 1,869 km |
| `Fontano's Subs Chicago` | **nothing** | found, 948 km |
| `Willis Tower` | **nothing** | found, 950 km |

Someone planning a drive names somewhere far away *on purpose* — that is the
app. So it is bounded first and unbounded only when that comes back empty: the
near search answers "a real place down the road that Photon didn't have", the
wide one answers "the place in the city I'm driving to", and the second
rate-limited request is only spent when the first found nothing.

Both geocoders now also share `rankByProximity`, which had been Photon's alone.

The general shape, worth keeping: the obvious fix and the correct fix differed,
and what separated them was checking the case the change would *break* rather
than the case it would fix.

### F-12 · Roundabout arrows
*Observed: 2026-08-11, after the one-way rotation fix. Changed; unverified on a device.*

With the rotation corrected the arrows sit along the road, but roundabouts still
looked wrong, and for a second reason: the basemap's sprite is 21 px of mostly
*tail*. MapLibre rotates each symbol to the local direction of the line, but the
symbol itself is a straight stroke — so on a tight curve it cuts the chord of
the circle instead of following it. At the style's 200 px spacing a small
roundabout also gets exactly one, which reads as a stray mark rather than as
circulation.

Shunt now supplies its own arrowhead: compact, no tail to disagree with the
curve, drawn pointing +X so the rotation is correct by construction rather than
by patching someone else's value, and spaced closer so a roundabout carries
several. Drawn at display density and tagged with it so it scales like the
style's own sprite sheet.

**The size is the one thing that could be off** — it was chosen to match the
21 px sprite it replaces and never seen on a screen from here.

### F-13 · "Couldn't reach search" while typing
*Observed: 2026-08-11. Fixed; unconfirmed in use.*

> as I type I think I get rate limited because it will throw up an error message
> if I type too fast and only fix itself if I add and remove a character

Two separate things, and the headline one is not a rate limit at all.

**Typing was reporting itself as a network failure.** `onQueryChange` cancels the
search in flight when the next keystroke arrives, and the search is wrapped in
`runCatching` — which catches `CancellationException` like any other throwable.
So the cancelled coroutine fell into the failure branch and wrote
`searchFailed = true`, and the UI said "Couldn't reach search — check your
connection" about a search that was working perfectly.

It looks like rate limiting because of *when* it appears. Typing straight
through the 350 ms debounce cancels inside `delay`, which is outside the catch
and therefore safe. Pausing mid-word — long enough for a request to actually go
out, not long enough to see the answer — is what triggers it.

Worth noting how it hid: the existing debounce test supersedes a query *before*
the debounce fires, so it exercised precisely the safe path. And the banner is
transient, cleared by the next search that completes, so a test asserting the
settled state passes too. The test that catches it has to look at the moment
just after the keystroke.

Fixed with `ensureActive()` after the `runCatching`, plus a staleness guard: an
answer to a query the box has moved on from is not written at all. Cancellation
usually covers that, but it is a race, and a stale suggestion list is worse than
a late one because it is wrong about what the user is looking at.

**And the rate limit was real, just not the cause.** Photon threw on any
non-2xx, including 429. `NominatimSearch` had treated 429/503 as "no answer this
time, not a failure" since the same bug bit there — the reasoning was already
written down, it had simply never been applied to the other client. Photon now
matches, which also lets `PlaceSearch` fall through to the other geocoder when
this one is briefly unwilling. A 500 still surfaces: throttling is temporary and
expected, a server error is neither, and hiding it leaves someone staring at an
empty list with no explanation.

Measured before changing anything: ten Photon queries at the debounce interval
produced no 429s at all from here, which is why this went in as the *second*
fix rather than the first.

### F-14 · "I have a hard time believing it would turn off the road it's on"
*Raised: 2026-08-11, looking at a planned route rather than from a drive. Addressed; unconfirmed in a car.*

> idk if the cars navigation is going to follow this route […] I just have a hard
> time believing that it would actually turn off the road it's on to get to that
> waypoint, especially if it introduces another turn. […] I think there should be
> more waypoints, just to be certain. Its not like we're hurting for API calls

A fair challenge to the whole pin design, and it lands on the assumption that was
already written down as the weak point: every pin decision was made by *asking
BRouter what the car would do*. Insert where BRouter says the car strays; prune
where BRouter says it wouldn't. Sound reasoning, entirely dependent on BRouter
modelling Tesla's router — and where they disagree, the route quietly stops being
guaranteed, with pruning actively removing the pins that would have saved it.

The fix is to stop deciding those pins by prediction at all. A turn is the only
place the prediction can cost anything: carrying straight on is never a wrong
answer to a route that goes straight on, and it is only at a junction that the
car has a choice to get wrong. So every turn on the route now gets a pin past
it, found geometrically, regardless of cameras, routing, or what BRouter thinks —
and pruning is forbidden from removing them.

Measured on the 615 km benchmark, this is better on every axis at once:

| | before | after |
|---|---|---|
| balanced | 27 pins | 82 |
| fewest-cameras | 30 pins | 100 |
| spread of fewest, by tenth of trip | `0 0 1 1 4 3 1 4 4 12` | `2 1 3 6 18 10 8 17 10 25` |
| pin phase | ~33 s | 10 s |
| whole plan (2 lanes) | 59 s | 47 s |

The speed-up is worth understanding rather than enjoying: more pins means
shorter legs, and a short leg routes faster than a long one. The pin phase was
never bounded by pin *count*, it was bounded by how far each leg had to be
searched.

**A trap this laid for the test suite.** Two existing tests started passing for
the wrong reason — a hard-cornered fixture now gets pinned at its corners
whatever else is true, so they no longer exercised the camera and shortcut logic
they were written for. `gentleArc` exists for that: a divergence with no turn in
it, with its own test asserting `turnsAlong` finds nothing, so if the fixture
ever grows a corner the guard fails rather than the coverage silently vanishing.
Worth remembering the general shape — adding a mechanism that fires everywhere
can quietly satisfy the preconditions of tests for other mechanisms.

### F-15 · Three things from looking at a planned route
*Raised: 2026-08-13, from the map rather than from a drive. All three addressed; none confirmed in a car.*

> Okay, the pins are almost perfect just a couple things I'd like to flag. First,
> in the first pic it shows a route that the car could easily still route through
> a camera, I don't trust that. […] Second, […] When a route passes right next to
> a waypoint it isn't going to, it could trigger that waypoint prematurely, or
> even if that's a waypoint it is currently navigating to. […] Also i do not want
> it making turns on a highway using the turn lane dedicated for emergency
> vehicles […] Have to be able to distinguish the Michigan left type turn though.

Three unrelated reports that happened to arrive together. Each turned out to be a
different kind of thing, which is worth separating.

**The camera the car could still cut through — the same argument as F-14, one
step further.** F-14 established that a turn is a place where relying on
BRouter's prediction can cost a wrong road. This is the other such place, and the
cost is higher: next to a camera the route deliberately dodged, "BRouter thinks
the car would stay on our line" is not good enough, because being wrong there is
the exposure the whole route exists to prevent. Every avoided camera whose
closest approach to the route is within 600 m now gets a pin one fork distance
before it and another after — instructed rather than predicted — and both are
protected from pruning, since pruning's own test is that prediction.

The gate that keeps this affordable is that it only applies **where the route has
left the fastest one**. A straight run through a metro passes hundreds of cameras
a street over that it never goes near, and bracketing all of them would mean a
pin every couple of hundred metres on a road with no decision on it. Where our
route *is* the fastest route, the car has no reason to leave it.

Measured on a 330 km benchmark trip into dense metro country, over real tiles
and the real DeFlock set: balanced 73 → 113 pins, fewest-cameras 59 → 110, pin phase
5.6 s → 6.6 s.

**The premature trigger was a units problem, not a tuning one.** The monitor
asked "how far is the car from this waypoint" and answered with a ruler. Any
cloverleaf, switchback, or frontage road beside the carriageway brings the route
back within metres of itself, so a pin on the far pass sits beside the car while
still being a mile off *along the road*. That is not a threshold to tighten; the
question was wrong. `DriveMonitorEngine` now measures along the route.

The interesting part was what that broke. Along-route position needs the car's
projection *into* the current segment; rounding it back to the segment's start
vertex is exact on a dense line and hopeless on a sparse one — a re-planned leg
or a straight hop between junctions is two points a couple of kilometres apart,
and rounded back the car reads as sitting at the start of that hop until it
reaches the far end, so nothing inside it ever advances. Two existing
`DriveMonitorTest` cases caught it immediately, which is the system working.
**This is the third time the same trap has been sprung in this project** — after
`sampleSpine` dropping cameras between sparse vertices, and `straysFrom` checking
a car path's vertices rather than the road between them. A polyline's vertices
say nothing about what happens between them; if code treats a vertex as a
position, check what happens when the vertices are kilometres apart.

**The emergency crossover was a data question with a clean answer.** The gap in a
divided highway's median that exists for patrol cars is usually mapped
`highway=service` + `service=emergency_access`, and BRouter's stock car profile
grants cars *every* `highway=service` way that carries no access tag — so those
gaps were routable, and a router looking for a shorter way past a camera would
take one.

Distinguishing the Michigan left needed no cleverness once the tags were read.
`lookups.dat`'s planet histogram has 8,290 ways tagged `service=emergency_access`
and one tagged `service=crossover`; a public median U-turn is an ordinary road or
a `*_link`, and where it is a service road it says `motorcar=yes` / `access=yes`
— which the profile reads *before* our rule, so an explicit permission still
wins. `access=no|private` and `motor_vehicle=emergency` were already excluded
upstream. The change is one `assign` and one line of `caraccess` in
`car-vario.brf`, marked `SHUNT CHANGE`.

What it cannot cover is the crossover mapped as a bare `highway=service` with
nothing to tell it apart. There is no signal there, and the answer is the same as
for search coverage: tag it in OpenStreetMap.

Two things came out of this that were not asked for:

- **The profile had two checked-in copies** — `app/src/main/assets/brouter/` for
  the phone and `brouter/src/main/resources/brouter-data/` for JVM tests — which
  had stayed identical by luck. They can drift, and when they do the benchmark
  measures and the tests vouch for a profile no user is running. The second copy
  is gone; `:brouter` copies the shipped one onto the classpath at build time.
- **The profile's access rules are now testable in CI.** `CarProfileAccessTest`
  evaluates `car-vario.brf` against tag combinations using BRouter's own
  expression engine, with no tiles and no search. Everything else about routing
  needs an `.rd5` file too large to commit, so this is the only routing
  behaviour CI can actually check.

### F-16 · A widen can cost the driver every camera-avoiding option
*Raised: 2026-08-13, from the repository's own benchmark. Open.*

Not a field report — this came out of running the benchmark on a new trip while
measuring something else, which is why it is worth writing down before it is
seen in the wild.

A 583 km trip over real tiles: the routes escaped the 60 km camera corridor, so
the fixed-point loop widened and ran the whole chooser again out of the same plan
budget. The second round ran out — `blocked` and `fewest (fallback)` gave up,
`balanced` was skipped over budget — and the plan came back with **one option:
the fastest road, 583.5 km, 43 cameras.** Total 75 s.

```
  fastest (spine)                       6.6 s
  fastest                               3.3 s
  blocked                              22.1 s
  balanced                             19.8 s
  fastest (widen 2)                     3.5 s
  blocked (gave up — out of time) (widen 2)     11.4 s
  fewest (fallback) (gave up — out of time) (widen 2)      7.6 s
  balanced (skipped — over budget) (widen 2)      0.0 s
```

Every part behaved as designed. The corridor check is what keeps labels honest,
the budget is what stops a plan running forever, and giving up a pass is
supposed to be safer than corrupting one. The *outcome* is still the worst one
available: a driver who asked for a camera-avoiding route is shown the fastest
road and 43 cameras. The 330 km version of the same trip also widens, but has
budget left over and returns all three options.

**Update, 2026-08-15.** Long trips are now cut into legs before this can happen
(CLAUDE.md §6), and a leg is short enough that its routes rarely leave the
corridor — the same 583 km trip now returns a camera-free first leg in 9.4 s
instead of the fastest road in 75 s. That does not *fix* this: a single leg
through dense country can still escape and still lose its options. It makes it
rare rather than routine, which is a reason to keep the entry open rather than
close it.

The direction that looks right and has not been built: when the widen round runs
out, fall back to the **previous** round's routes and re-label them against the
wider camera set. That keeps the rule the loop exists to enforce — a route is
never labelled against cameras it was not measured against — and gives up only
the claim that the route is *optimal*, which is a much smaller loss. It would
need saying plainly on the result sheet.

Worth noting what is *not* the answer: widening `CAMERA_CORRIDOR_METERS` further.
It is already at 60 km, matching the route bounding box, and a route that detours
60 km sideways on a 330 km trip is not an anomaly to size around — it is what a
camera-free route across dense country looks like.

### F-17 · A charging check handed the car back to the destination
*Observed: 2026-08-15, on a real drive, with screenshots. Fixed; unconfirmed in a car.*

> after checking for charging, shunt does not automatically send the waypoint
> back to the car after doing so. […] After the car leaves the route, a new route
> is chosen that goes through a camera when a camera free route still exists.

Two separate faults in one chain, and worth separating because only one of them
is fixed.

**The first is the missing push, and it is the interesting one.** A charging
probe cannot be asked without redirecting the car: the question is "given the
whole trip, what do you intend", so the car has to be holding the whole trip to
answer it. `ChargeStopCoordinator` therefore pushes the final destination, reads,
and restores the steering aim. It restores on the paths it was written for. It
does not on the rest — a re-assert that reports failure after the car has already
taken it, a resume whose re-plan comes back empty, an exception on the way out —
and on every one of those the car is left holding the destination.

Nothing looks wrong at that moment. The failure only appears three steps later:
the car drives to the destination it is holding, that reads as off-route, the
off-route handler re-plans, and the driver is somewhere they never chose. **The
missing push and the camera are four steps apart**, which is why this survived
the last round of charging fixes — the restore code exists and is correct, and
it was the paths that skip it that mattered.

The fix moves the responsibility rather than adding another branch to it.
`DriveMonitor` now re-aims after *any* charging check that changes nothing,
instead of trusting the coordinator to have done it. The monitor is the thing
that knows where the car should be pointed, and asserting it costs a rate-limited
command every 45 s at worst.

**The second fault is not fixed, and needs saying plainly**: the re-plan came
back through a camera *when a camera-free route existed*. That is the mid-drive
budget tension already recorded in CLAUDE.md §7 — a re-plan gets
`REPLAN_PASS_BUDGET_MILLIS` (12 s) while one avoidance pass on a long trip costs
around 20 s, so a re-plan early in a long drive can only return the fastest road.
This is the first time it has been seen happening rather than predicted. Leg
splitting should help a great deal, since what gets re-planned is then a leg
rather than a cross-state trip, but that wants confirming with a measurement
before anyone believes it.

**A trap this laid for the test suite**, worth recording because it took three
attempts. The obvious test — drive a steered plan through a probe, assert the car
ends up aimed at a pin — passes without the fix, because an ordinary waypoint
advance earlier in the drive is *also* an aim at a pin, and asserting on "the
last advance" finds that one. The assertion has to be on the last call of any
kind: what the car is driving to is the last thing it was told, and the bug is
precisely that the last thing it was told was the destination. The fixture also
needed its own geometry, far enough short of the first pin that nothing advances,
so that every call the vehicle sees comes from the charging path.

### F-18 · "All of the missing locations"
*Raised: 2026-08-15. Partly addressed; the tempting fix was measured and thrown away.*

> I am really not liking all of the missing locations that needs to be fixed.

The obvious diagnosis is the keyless constraint, and it is wrong. Both geocoders
already read OpenStreetMap; the trouble is that they read a *pre-built index*
tuned for "what place in the world is named this", so a small local business is
ranked against every namesake on the planet and loses. Photon's public index also
trails OSM by weeks, so somewhere mapped last month simply is not in it.

**The tempting fix, measured and discarded.** Overpass queries OSM itself,
minutes behind live, so `nwr["name"~"birch",i](around:…)` finds the local shop
neither geocoder will rank. Built as a last-resort tier — one query per settled
search, only after both geocoders had already failed, only where the alternative
was "no such place exists". Measured against the public endpoint:

| query | time | results |
|---|---|---|
| 50 km radius | rate-limited (HTTP error) | — |
| 15 km radius | 73.8 s | 0 |
| 5 km radius | 72.0 s | 0 |

Narrowing the radius by a factor of a hundred in area changed nothing, which is
the tell: a case-insensitive regex on `name` cannot use an index, so Overpass
scans the area whatever its size. The code was written, unit-tested, measured,
and deleted. **CLAUDE.md §3 already said Overpass was 30-40 s and hopeless for
search; the lesson is that "but a name query is cheaper than a tag sweep" was a
guess, and it was worse, not better.**

**Update, 2026-08-15 — most of it was our own query.** The complaint recurred,
Google was asked for and then withdrawn within minutes (*"I just want something
that actually works"*), and measuring the pipeline properly — rather than the
geocoders in isolation — found the real fault. My first measurement used
well-mapped chains, which every geocoder gets right; it was too weak a test to
learn anything from. Typed the way somebody actually searches from a small town:

| typed | Photon returned |
|---|---|
| "Concordia Public Library" | a library in **Hong Kong** |
| "brown grand theatre" | a theatre in **Warsaw** |
| "Main Street Concordia" | a school in **Tomball, Texas** |

`location_bias_scale` is a *preference* and loses to OSM "importance". Adding a
hard `bbox` around the driver returns the actual local library, the actual Brown
Grand Opera House, and the right town's streets. **No ranking change could have
fixed this** — `rankByProximity` sorts what it is given, and it was given Hong
Kong. Two stages, near then wide, so a deliberately distant destination stays
findable.

The lesson worth keeping is about the measurement, not the parameter: testing a
geocoder with queries it is certain to get right proves nothing, and it very
nearly bought a paid dependency to fix a bug we had put there ourselves.

**What also helps, and shipped alongside.** Recents are now matched as the
driver types, not just offered on an empty box. That matters more than it sounds,
because press-and-hold on the map already reverse-geocodes a point, routes to it,
and files it in Recents — so **any** place the map data cannot name by name can be
pinned once and is findable by name from then on. It is instant, works with no
signal, and cannot be missing, which is the opposite of every other row in the
search results.

The empty-search message now says so, rather than reading as a dead end.

The other half of the answer is the one CLAUDE.md §3 has always given: add the
place to OpenStreetMap, so it is searchable for everyone and not just here.

### F-19 · The destination the car takes is not quite the one that was sent
*Observed: 2026-08-16, on a successful real drive. Not fixed — the experiment that settles it is in the app already.*

The drive itself worked: routed around cameras, no trouble for 95% of it. What
was wrong was the last hundred metres.

> routing to [a house number] shows as [a lower house number] on my car and wants
> to pull over slightly before my driveway. And then routing out there, on the
> app it shows the destination being off to the left side of the road, but when
> it got sent to my car it was on the right side of the road

Two symptoms, one cause, and it is the sharpest evidence yet for F-2. On a car
that requires signed commands, Shunt falls through to Tessie's `share`, which
takes a **string** the car resolves itself — and what it is given is bare
`"lat,lon"`. Tesla appears to put that through *address search* rather than
treating it as a pin: address search snaps to the nearest known house number
(hence the lower number) and to a side of the street (hence the wrong side).
Six decimal places of precision does not help if the string is not being read as
a coordinate at all.

**Do not guess the fix.** `NavCapabilityProbe` already exists for exactly this
question. Run it from vehicle settings and see which forms land and how
precisely. Changing `shareValue` on a hunch risks breaking the one channel that
currently works at all.

**First run, 2026-08-16, on the maintainer's car — and the probe answered
nothing.** Every channel was either rejected or "accepted, car state
unreadable":

```
x navigation_waypoints_request          rejected
x navigation_gps_request order=1        rejected
x navigation_gps_request order=3        rejected
x share coordinates                     accepted, car state unreadable
x share geo: URI                        rejected
x share OpenStreetMap link              rejected
x share Apple Maps link                 accepted, car state unreadable
```

Two things came out of it, and the second matters more than the first.

**The car is narrower than assumed.** Only two forms are accepted at all —
bare coordinates and an Apple Maps link. `geo:` and OpenStreetMap were rejected
outright, so the obvious "send something unambiguous" fixes for F-19 are not
available on this car. That is a real finding.

**The probe could not tell whether either of them worked**, which makes it
useless for the question it exists to answer. `TessieAccountClient.activeRoute`
read `use_cache=true` — Tessie's *cached* state — so the read-back six seconds
after a command was answered from state captured before the command was ever
sent. It reads `use_cache=false` for the probe now, which wakes the car; that is
the right trade for an explicit parked diagnostic and the wrong one for the
drive monitor's charging reads, which stay cached and free.

"Accepted, car state unreadable" was also doing double duty for *the car could
not be asked* and *the car was asked and is navigating nowhere* — and the second
is the damning one, because it means a server accepted a command the car then
ignored. The two are reported separately now.

Google Maps links were added to the probe at the maintainer's request ("add
Google's in there too since I've had luck with that"), in both the `api=1`
search form and the short `maps.google.com/?q=` form a phone's share sheet
produces. **This does not breach the keyless rule in §3**: nothing calls Google.
Shunt hands the *car* a URL string through Tessie's share command, exactly as a
person sharing a link would, and the car resolves it with whatever credentials
Tesla already has. No account, no key, and nothing that can be revoked.

Re-run the probe on this build before drawing any further conclusions — the
previous results say only that the read-back was broken.

**This matters more than a hundred metres**, because of what it implies for
charging. A Supercharger sent as something the car resolves to a street address
is a Supercharger the car will drive *to the street outside*, and FSD cannot
park in a stall from there. The maintainer put it directly: "we need to make sure
it's actually routing to the charger as a location not an address, so fsd will
properly park in a charging stall automatically." Whatever the probe says the
right form is, chargers need it most.

### F-20 · A ruled line across the map, and a Supercharger that was not routed to
*Observed: 2026-08-16, from the chooser. Both fixed; unconfirmed.*

Two separate faults in one screenshot, both introduced by leg splitting.

**The line.** A straight blue line ran across country from the top of the map to
the destination, alongside the real route. The legs of a long trip were being
concatenated into a *single* LineString for drawing, so wherever two of them did
not share an endpoint exactly, the map drew a straight segment between them —
which at that zoom is a ruled line across two states. Each leg is now its own
feature, which cannot produce a joining segment whatever the legs contain. Worth
remembering as a general rule: **merging polylines that are not guaranteed
contiguous is a drawing bug waiting to happen.**

**The Supercharger.** Adding one put a marker on the map but the route did not go
there. It had fallen *past the first leg's boundary*: the leg was cut at a
synthetic quiet point that came earlier, so the route being shown ignored the
stop entirely and only some later leg would have reached it. `LegSplitter` now
never cuts in front of a stop the driver added — which is the right rule
regardless, because a stop is a better boundary than anything the splitter can
invent: it is a point the route must pass through, chosen by the person driving.

Found while fixing it, and worse than either: the leg being handed to the drive
monitor was given the *trip's* final destination as its last waypoint rather than
its own end. On a three-leg trip that aimed the car hundreds of kilometres past
the leg the moment the extension landed.

---

### F-21 · The last leg of a long trip does not avoid cameras at all
*Observed: 2026-08-16, from planned routes. Reproduced in the benchmark, fixed;
unconfirmed on a drive.*

> I did however notice that the last leg on some longer routes seems to be not
> avoiding cameras at all and routing straight through them. One I noticed in
> Washington dc, and another going to San Francisco. I have a hard time
> believing they're all unavoidable.

They were not, and the last observation is the one that identified the cause.

**Why the last leg, and only the last leg.** A split trip's intermediate
boundaries are chosen by `LegSplitter` precisely because *nothing is watching
them* — that is the whole cut rule (CLAUDE.md §6, "cut where there is nothing to
avoid"). So every leg but one begins and ends somewhere quiet. The last leg is
the only one whose end is the driver's actual destination, which on these trips
was a pin dropped in a city centre.

**What that triggered.** A route may not begin or end inside a zone the router
has been told is impassable — BRouter throws `last wpt in restricted area` — and
Shunt's answer was to skip the hard-block pass **entirely** whenever any endpoint
was seen by any camera, falling back to weighted avoidance. Weighted avoidance is
a different promise: BRouter charges (metres inside the zone × weight), so a road
clipping the edge of a cone is cheap and gets taken. One unavoidable camera at
the kerb bought a dozen avoidable ones on the way in.

Reproduced in `RealWorldPlanningBenchmark`, on a four-leg trip ending on top of a
camera in a dense metro:

```
before   leg 4  14.3 s  186.9 km  3 cameras
                options: FASTEST=31c, FEWEST_CAMERAS=3c
                blocked (skipped — an endpoint is inside a camera's view) 0.0s
after    leg 4  41.9 s  306.0 km  2 cameras
                options: FASTEST=31c, BALANCED=3c, FEWEST_CAMERAS=2c
                blocked (2 at an endpoint, unblockable) 4.5s
```

Both remaining cameras are the ones at the destination, and they are now named as
such on the result sheet. Legs 1–3 came back byte-identical, which is the check
that matters: the change is a no-op wherever no endpoint is watched.

**The fix is to drop the offending zones, not the pass.** `withoutZonesHolding`
removes only the impassable zones that contain an endpoint — using BRouter's own
containment test from `cleanNogoList`, applied to a ring around each point
because BRouter tests the waypoint *snapped to a road*, up to
`waypointCatchingRange` (250 m) away. Everything else stays blocked, so the route
is still camera-free wherever a camera-free road exists.

Two properties worth keeping if this is ever touched:

- **Under-dropping is graceful.** BRouter refuses the request, the pass reports
  no route, and the weighted fallback runs exactly as it did before. That is what
  makes erring tight safe, and it is why a snap margin is a judgement call rather
  than a correctness one.
- **Labelling never moves.** Camera counts are measured against the full set
  whatever was handed to the engine, so a dropped zone can never make a route
  *look* cleaner than it is.

A second contributor, fixed alongside: later legs were planned with
`PASS_BUDGET_MILLIS`, which is a *patience* figure — how long a driver will watch
a spinner. Nobody is watching a later leg; the car has at least
`LegSplitter.MIN_LEG_METERS` of road ahead of it, an hour at motorway speed. They
now get `LEG_PASS_BUDGET_MILLIS` (5 minutes), which is the same failure mode as
§7.10 arriving at the leg most likely to widen its corridor. A later leg that
still comes back holding nothing but the fastest road is written to the
diagnostic log rather than accepted silently.

---

### F-22 · The camera-reach setting changed the warnings but not the route
*Observed: 2026-08-16, alongside F-21. Fixed; unconfirmed on a drive.*

> Also on the camera distance, I don't think brouter is taking the new values
> into account because it is routing us past cameras that are avoidable but it
> doesn't seem like it would be triggering them at the default value. It is now,
> but not changing the route to avoid these new ones.

Exactly right, and measurable. `CameraVision.rangeScale` is honoured by
everything that asks a `CameraVision` directly — the counting, the drive
warnings, the cones on the map — but `CameraCluster.rangeMeters` read
`CameraVision.OMNI_RANGE_M` / `DIRECTIONAL_RANGE_M` straight off the companion
object. The nogo shapes handed to BRouter were therefore always default-sized,
however far the slider was pushed.

Measured on a real 32 km metro trip, before and after:

| camera reach | fewest-cameras, before | fewest-cameras, after |
|---|---|---|
| ×1 | 47.1 km, 2 cameras | 47.1 km, 2 cameras |
| ×3 | **47.1 km, 20 cameras** | 81.6 km, 3 cameras |

The middle cell is the bug in one number: at three times the reach the app
returned *the identical route it planned at the default* and labelled it with
twenty cameras. Turning the setting up made Shunt announce cameras it had made no
attempt to dodge — worse than the setting doing nothing, because it reads as
avoidance having been tried and failed.

**Why no test caught it.** Every existing test of the scale exercised
`CameraVision.sees`, and every existing test of the nogo shapes used the default
scale, where the two agree. `NogoCoverageTest` holds the real invariant — the
blocked shape must contain what the counter sees — and it now runs at several
scales, which is the only version of that test that could have failed.

---

### F-23 · The map does not name anything
*Observed: 2026-08-16. Fixed; wants a look on a phone.*

> I want names of various places to show up on the map just like osm and Google
> maps.

The basemap draws city, town, street and water names and nothing else — no
shops, no restaurants, no parks, no schools. It is not a bug in Shunt's code so
much as a property of the style: OpenFreeMap's dark style descends from Dark
Matter, which was designed as a *backdrop* for somebody else's data and
deliberately omits POIs so they do not compete with it. Inspecting the style
confirms it — 15 symbol layers, drawing only `place`, `transportation_name` and
`water_name`.

On a navigation map that is a real gap. A driver looking at a camera detour has
nothing to recognise the turn by, and no way to tell what is around them.

**The names were already being downloaded.** The tile manifest at
`tiles.openfreemap.org/planet` carries a `poi` source-layer from z11 and a
`park` one from z4, both with `name` and `rank` — the style just has no layer
drawing them. So `RouteMap.addPlaceLabels` adds two symbol layers over the
source that is already there: no extra request, no new host, nothing that could
need an account.

Three things worth knowing if these ever need adjusting:

- **The font stack is not a choice.** `Noto Sans Regular` is the only stack the
  style ships glyphs for; naming any other renders nothing at all, silently.
- **They are added at style-load time, before any of Shunt's own layers**, so
  every route line, camera dot and pin sits above them. Labels must never be
  able to obscure the two things a driver is looking for.
- **Density is managed by rank, not by hiding.** `symbol-sort-key` is the POI's
  own OpenMapTiles rank, so when MapLibre thins colliding labels it keeps the
  more significant one rather than whichever drew first. Below z16 the low
  ranks are filtered out entirely, because a town centre at z14 is otherwise a
  solid block of text over the roads the route runs on.

---

### F-24 · A leg doubles back over the one before it
*Observed: 2026-08-16, from a planned route. Fixed; wants a look on a phone.*

> a leg needs to go backwards after it found the way to the next spot, we should
> make it so if it has to double back it can just delete the useless part of the
> leg up to the point where it doesn't need to double back.

Seen on the map as a route running east, turning south, and then running west
again along a parallel road — miles out and the same miles back.

**Why it happens, and why it is nobody's bug.** A leg boundary is a *hard
waypoint*: the first leg must end at it, the next must start from it.
`LegSplitter` picks it on the **direct** road, because before anything expensive
has run that is the only geometry there is — and the direct road is not where a
camera-avoiding route goes. So the two legs can disagree about the boundary: the
first drives out to touch it, the second is planned freely from there and comes
straight back the way it came. Both legs are perfectly correct routes. The spur
is the *overlap between them*, which neither one can see.

`LegJoin.trimDoubleBack` finds the last place the two lines are still together —
the point after which the second stops retracing the first — and cuts both
there. That is a trim, not a re-plan: the join point is a vertex of the first leg
that also lies on the second, so what is left is a sub-path of what was already
planned, and it can only be shorter.

Three properties hold it in place:

- **Only at the join.** The retrace is walked from the *start* of the second
  leg and stops at the first point that is not on the first leg. A switchback, a
  frontage road or a there-and-back to a charging stop all bring a route near
  its own path later on, and every one of those is real route that must survive.
- **Only near the end of the first leg** (`MAX_SPUR_METERS`). A boundary detour
  is short by nature, so a cap means this can never remove a large piece of a
  leg however oddly two lines happen to overlap — and it bounds the cost, which
  is otherwise every point of one polyline against every point of another.
- **Only a spur worth cutting** (`MIN_SPUR_METERS`). Legs meeting at a shared
  waypoint always overlap a little; that is what a shared waypoint *means*.

Camera labelling is safe by construction and the direction of the error is the
right one: a trimmed route passes a **subset** of what the untrimmed pair
passed, so a count carried over from before the trim can only overstate
exposure. Overstating is safe here; understating is the thing this project
exists to prevent.

**What this does not yet do:** the chain already handed to the drive monitor
keeps its pins. Pulling a waypoint out from under a car that may be driving
toward it is the §6.1 failure, and the trim is not worth that risk — so on a
drive already under way the car still passes through the boundary while the map
shows the shorter line. Worth closing properly later, by trimming the pending
extension rather than the live chain.

---

### F-25 · The chooser only ever described the first leg
*Observed: 2026-08-16. Fixed.*

> the fastest and fewest cameras menu should update information as legs are
> calculated because right now it only gives info on the first leg

True, and the banner said so — but "about 3600 km" next to "270.5 km" is a poor
substitute for the trip actually being described. The later legs land over the
following seconds and nothing was using them.

The sheet now carries a **whole-trip line** that grows as each leg arrives:
summed time, distance and camera count, with the number of legs so far, and a
note saying whether more are still coming.

**It is deliberately not folded into the option cards**, and that is the
interesting part rather than a shortcut. Those three cards are a *choice between
routes for this leg*. The later legs are planned once, as camera-free as they
can be, and they begin at the same boundary whichever card is picked — so
adding their distance into "Fastest" would describe a trip nobody is being
offered. The selected option plus the later legs is a real trip; "Fastest" plus
the later legs is not a thing that exists.

---

### F-26 · The last leg still took the fastest route
*Observed: 2026-08-16, from a planned route into San Francisco. Reproduced,
fixed; unconfirmed on a drive.*

> we are still having a problem where the last leg still is taking the fastest
> route

With a screenshot showing the last leg drawn as an unbroken line of red camera
markers all the way in. F-21 had already stopped a watched destination cancelling
the hard block, and it was not enough — this is a **different cause with the same
symptom**, which is why it survived the first fix.

**Reproduced against real tiles and the real camera set**, a two-leg trip ending
in the Bay Area:

```
leg 2   75.1 s   104.3 km   126 cameras
        options: FASTEST=126c/104km
        blocked (7 at an endpoint, unblockable) (no route)      12.2 s
        fewest (fallback) (no route)                            29.4 s
        balanced                                                20.0 s
        fastest (widen 2)                                        0.7 s
        blocked (...) (gave up — out of time) (widen 2)           6.6 s
        fewest (fallback) (gave up — out of time) (widen 2)       4.4 s
        balanced (skipped — over budget) (widen 2)                0.0 s
```

Read that from the top and the whole thing is there. Round one found no
hard-blocked route (12 s), no weighted fallback (29 s), and **did** produce a
balanced route (20 s). That route left the camera corridor, which forced a widen
— and a widen is the entire chooser run again out of the same budget. Round two
had seconds left, so everything except the trivially cheap `fastest` pass gave
up. `fastest` never leaves the corridor, so the loop declared the camera set
settled and returned it alone.

**The balanced route was found, and then thrown away**, which is the part worth
being annoyed about. This is §7.10 / F-16 exactly, arriving on the leg most
likely to trigger it: the last one is the only leg ending at the driver's real
destination, so it carries the densest camera set and is the likeliest to detour
outside its corridor.

The fix is the one §7.10 had already argued for and nobody had built: keep the
earlier round's routes and re-label them against the wider camera set. Same trip,
same tiles, same 75 s budget:

| leg 2 | before | after |
|---|---|---|
| options | `FASTEST` alone | `FASTEST` + `BALANCED` |
| best available | **126 cameras** | **21 cameras** |
| whole trip | 128 cameras | 23 cameras |

Sound because the labelling is recomputed from the final camera set for every
option whichever round it came from, and because a carried route is always
covered by that set — the widen exists to cover the escaping routes and grows the
spine to include their geometry. The sheet says the search ran out of time, so a
route that is merely *available* is not presented as the best one found.

**Two loose threads from this measurement, neither chased yet.**

- The weighted `fewest` fallback returned **no route at all** after 26–29 s.
  A weighted nogo is a price, not a wall, so a route should always exist. Worth
  suspecting `FEWEST_WEIGHT` (20,000/metre) overflowing BRouter's integer cost
  accumulation on a long route through dense zones.
- A camera-free route into that metro may genuinely not exist — every bridge
  approach is watched — which would make the hard block's "no route" correct
  rather than a bug. The 21-camera balanced route is the honest answer either
  way, and it is 105 fewer than what was being shown.

Note the app gives a later leg `LEG_PASS_BUDGET_MILLIS` (5 minutes) rather than
the 75 s the benchmark used, so on a phone round two has far more room and may
finish outright. The measurement above is the worst case, which is the right one
to fix against.

---

### F-27 · "No route found" on a very long trip
*Observed: 2026-08-16, routing to San Francisco. Cause identified, fixed;
unconfirmed.*

The plan failed outright — "No route found — the offline map for this area may be
incomplete" — with a diagnostic listing thirty-odd tiles all present, including
the ones covering the destination. The message was wrong twice over: the map was
complete, and re-downloading it would have changed nothing.

**The clock ran out, and the spine took it.** The direct-road pass runs over the
*whole* trip while every pass after it works on a single leg, so on a
cross-country route it is the expensive search rather than the cheap one — the
app's own breakdown, from the screenshot before this one, reads
`fastest (spine) 1 m 04 s` of a `1 m 11 s` plan on a 3,598 km trip. It was given
`budgetLeft()`, meaning all of it. A slightly harder trip spends the lot, every
pass after it gets the 1 ms floor, even the trivially cheap `fastest` pass times
out, `routes` comes back empty, and that is reported as no route existing.

Two fixes, and the second matters as much as the first:

- **`SPINE_BUDGET_SHARE`** caps that pass at a third of the plan's budget.
  Running out *there* is recoverable in a way running out anywhere else is not:
  the spine falls back to the straight line between the trip's points, and the
  only cost is a corridor drawn around a cruder shape. Every other pass failing
  has no fallback at all, so this is exactly the pass to take time from.
- **The failure now says which failure it was.** Blaming the offline map for a
  timeout sends somebody to re-download hundreds of megabytes they already have.
  The passes label themselves when they hit a ceiling, so the message reads
  those labels rather than guessing.

**Capping the time was not enough, and the second attempt found the real
mechanism.** With the cap in place the trip still failed, because of what the
fallback did: a spine that runs out falls back to the straight line between the
trip's own points — **two points** — and `LegSplitter.cut` walks that looking for
a candidate between `MIN_LEG_METERS` and `MAX_LEG_METERS`. On two points there
are none: index 0 is at 0 m and index 1 is 3,000 km along. So it cut *nothing*,
and the whole continental trip was then planned in one go out of whatever budget
was left, which cannot succeed. **A trip whose spine failed is exactly the trip
that most needs splitting**, and it was the only one guaranteed not to be.

Two changes, and the first is the structural one:

- **The spine stops short of the destination on a very long trip**
  (`spineProbe`). It exists to choose a cut and draw this leg's corridor, and
  both questions are answered inside the first `MAX_LEG_METERS` of road —
  routing the remaining thousands of kilometres to answer them is the whole
  cost. This makes the pass a function of leg length rather than trip length.
- **The fallback spine is sampled**, so even when routing fails outright there
  are candidates to cut on.

**Only above `SPINE_FULL_LIMIT_METERS` (1,500 km), and that threshold is the
interesting part.** The probe aims along the great circle, and the road out of a
town rarely leaves along it — so a probe spine picks its cut from a road the trip
would not have taken. Measured on the 1,165 km benchmark: probing everywhere cost
**+88 km of driving for identical exposure** (1,165 km vs 1,078 km, 2 cameras
either way). Below the threshold the full spine is both affordable (about 4 s at
1,000 km) and a *better* guide. Above it, the full spine is what makes the plan
fail. So the trade is made only where the alternative is failing outright, and
the benchmark trip comes back byte-identical to before this change.

---

### F-28 · Paragraphs on a screen someone is driving past
*Observed: 2026-08-16. Fixed.*

> On a navigation app we cant have long paragraphs and lots of words to read when
> someone's driving. It needs to be simplified, and just give the data. The fsd
> warning needs to be simplified to get the message across, because no driver is
> going to be reading a whole paragraph about the limitations.

The FSD caveat was five lines carefully explaining which parts of the vehicle
path are proven. It is now one: *"FSD nav is in early testing — stay ready to
take over."* That is not a weaker warning. **A paragraph shown to a driver is
weaker than a sentence, because it does not get read at all** — the detail was
protecting the project, not the person.

Same pass over the result sheet: the leg banner explaining *why* Shunt splits
long trips became `3598 km total · rest planned as you drive`; "Still planning
the rest — these numbers grow as each leg lands" became `still planning…`; the
camera block's three-sentence explanations became a phrase each. Every number
survived. The rule is now written down in CLAUDE.md §9 so it does not have to be
rediscovered: on the map and the driving screens, the number and the noun;
reasons go where somebody is sitting still.

---

### F-29 · The map does not follow the drive
*Observed: 2026-08-16. Fixed; unconfirmed on a drive.*

> as someone is moving along the route, the app should automatically adjust the
> map zoom and position to include the driver marker and the next waypoint, but
> only if the driver hasn't touched the map positioning or zoom after a certain
> amount of time.

Implemented as a **fit rather than a follow-the-dot camera**, which is the one
design decision here worth arguing. Centring on the car at a fixed zoom is what
most navigation apps do and it answers "where am I". The question this app exists
to answer is "where am I going, and what is between me and it" — a question about
the gap between two points. So the frame is the box containing the driver and the
pin the car is aiming at, and it tightens by itself as the car closes on it.

The pin comes from a new `onAim` callback on `DriveMonitor`, published as the
chain advances, so the map is always framing the stretch actually being driven.

**Any gesture suspends it for `FOLLOW_RESUME_MILLIS`.** That is the §6.1 rule
applied to the screen rather than the car: a map that snaps back while somebody
is deliberately looking a few miles ahead is the app overriding a person, and
being right about the framing does not make it less annoying. Only
`REASON_API_GESTURE` counts — listening to camera movement generally would have
the follow camera read its own easing as a manual pan and switch itself off on
the first frame.

---

### F-30 · The screen turns off and the app fails silently
*Observed: 2026-08-16. Mitigated, cause still unknown.*

> we need to make sure the app keeps the screen awake while the app is open.
> Screen turns off and the app will silently fail

`FLAG_KEEP_SCREEN_ON` while Shunt is in front. The flag rather than a wake lock
because the system scopes it to the window — it cannot outlive the app or leak,
and it releases the moment Shunt is backgrounded. A navigation app is looked at
in glances, so the phone's idea of "idle" is wrong about it by construction:
nobody taps the screen while driving.

**This is a mitigation, and the underlying fault is still unexplained.** The
drive monitor is a *foreground service* and should survive the screen going off;
whatever actually breaks when it does — Doze, location updates throttled with the
screen off, the Compose/MapLibre side dying and taking something with it — has
not been identified, and keeping the screen lit hides it rather than fixing it.
The silence is the dangerous half: whatever it is, it must end up raising an
alert, and spoken alerts and the notification both still work with the screen
off. See verification C6.

---

### F-31 · The leg double-back, re-checked
*Checked 2026-08-16 against real tiles. No spur found on the trips available.*

Reported as still present after `LegJoin` landed. The benchmark now prints
whether a trim fired at each leg boundary, and on a four-leg 1,165 km trip over
real tiles every join reports **clean — no spur at the boundary**. So on the
routes reproducible here, `LegJoin` is not failing to fire; there is nothing at
the join to trim.

Which leaves two possibilities, and it is worth being clear that this has *not*
been settled:

- The shape being seen is a detour **inside one leg** — out along one road and
  back along a parallel one — rather than a retrace across a boundary. `LegJoin`
  cannot help with that by design: it compares consecutive legs, and a single
  leg's geometry is whatever BRouter decided was cheapest given the cameras.
- Or the thresholds are wrong for the case in question (`OVERLAP_METERS` 60,
  `MIN_SPUR_METERS` 400) and the trips available here do not produce it.

Telling them apart needs the trip that showed it. The useful bug report is a
diagnostic log export from a plan that does it — a "trimmed a … m double-back"
line means the machinery fired and the shape is something else; no such line with
a visible out-and-back at a leg boundary means the thresholds are wrong.

---

### F-32 · A waypoint left standing off the route
*Observed: 2026-08-16, diagnosed by the maintainer from the map. Fixed.*

> That waypoint was placed when a leg was facing that way, and then it got
> cropped, but the waypoint stayed. We need to crop that waypoint too.

Exactly right, and it is the half of F-24 that was missed. `LegJoin.trimDoubleBack`
cuts the *polylines* where two legs double back over each other; the pins placed
along the removed spur came through untouched, so one was left floating beside a
route that no longer goes near it.

That is worse than untidy. **A pin is a constraint sent to the car** — the drive
monitor aims the vehicle at each in turn — so a pin out on a deleted spur would
steer the car back down the very road the trim exists to remove, at a junction,
under FSD. `LegJoin.pinsOn` drops them, on both sides of the join and in the
chain handed to the monitor as well as the line drawn on the map.

The threshold errs toward keeping a pin (`PIN_ON_ROUTE_METERS`, 400 m): a pin
sits *beside* the road it holds and the line it is checked against has been
sampled, so a kept pin is at worst redundant while a dropped one loses a turn.

---

### F-33 · "camera-free" on a trip that is not camera-free
*Observed: 2026-08-16. Fixed.*

> I feel like the fastest and fewest cameras buttons are kind of confusing from a
> UI perspective since it says camera free even though as a whole we end up on a
> route that isn't camera free.

A fair reading of a flat contradiction: the badge said **camera-free** directly
below a whole-trip line reading **13 cameras**. Both were true — the badge is
about the leg being chosen, the total is about the trip — and nothing said so.

On a split trip the badge now reads "leg is camera-free" and the line under the
cards "This leg passes no cameras." Unsplit trips are unchanged, because there
the two cannot differ and the extra word would be noise.

---

### F-34 · Still hitting avoidable cameras on the last leg
*Investigated 2026-08-16. One real waste removed; the camera count did not move.*

Reported against the carried-forward build: the last leg into a metro now returns
a balanced route rather than the fastest road, but "there are still some
genuinely avoidable cameras that are getting hit."

Measured on the same San Francisco leg, and the breakdown is worth reading in
full because it does **not** support the obvious conclusion:

```
blocked (7 at an endpoint, unblockable) (no route)   12.0 s
fewest (fallback) (no route)                         27.7 s
balanced                                             19.4 s
... widen 2, out of time
```

The hard block **proved** no camera-free route exists — it reported *no route*,
not a timeout, after a full 12-second search. So into that metro 21 cameras may
genuinely be near the floor for the weighted approach, and the honest position is
that this is not yet shown to be avoidable.

**The `FEWEST_WEIGHT` suspicion from F-26 was confirmed and is not the answer.**
At 20,000 the weighted fallback returns no route after 28 s; at 2,000 it returns
one in 20 s. So the weight *is* what makes that pass fail. But the route it finds
at 2,000 passes **23** cameras against balanced's 21, and leg 1 got worse too
(2 cameras → 4). A lower weight buys a working pass and a worse answer, so
lowering it is not a fix. A weight *ladder* — retry lower when the high weight
finds nothing — would not have helped here either, for the same reason.

**What did come out of it:** the hard block was being re-run on the widen round,
spending another 11-15 s re-proving the same impossibility, out of the budget the
pass that actually produces a route then had nothing left of.
`RouteRequest.hardBlockProvenImpossible` stops that, and the argument is
monotonicity: a widen only ever **adds** cameras, so the zones the later round
gets are a superset of the ones the block already failed against, and a route
that did not exist among fewer obstacles cannot appear among more. Set only on a
proved *no route* — a timed-out search has proved nothing, and skipping it later
would be discarding an option on no evidence.

Measured: the widen round's block goes from 12.0 s to 0.0 s. The camera count on
this trip is unchanged at 21, so **this is a budget saving, not a fix for the
report**. Where it should tell is any leg whose widen round currently starves the
pass that would have found something.

Still open, and the next thing to try: the *fastest* option into that metro
passes 126 cameras and balanced passes 21, which is a very wide gap — a weight
between 500 and 20,000 may find a better knee. That wants measuring across
several metros rather than tuning against one.

---

### F-35 · A C around the direct road at a leg boundary
*Observed: 2026-08-16. Fixed; unconfirmed on a phone.*

> is it possible to just go back and redo further back up to a point there's not
> a bunch of waypoints around? I'm noticing that it still isn't really taking an
> optimal route and making sort of a C around a more direct path

Right diagnosis and the right fix. `LegJoin.trimDoubleBack` only removes an
**exact retrace** — the second leg driving back down the first leg's own road.
What a boundary usually produces is a **loop**: the first leg bends out to touch
a point on the direct road, the second leaves it by a different road, and the
pair draw a C around the line the trip wanted. No road is driven twice, so there
is nothing to trim. The only thing wrong is the constraint, and the constraint is
a waypoint nobody asked for, chosen on the direct spine before either route
existed.

So `LegJoin.seamOf` takes a window either side of the boundary — points both legs
already drive through — the join is routed again *without* the boundary, and
`spliceSeam` puts the result in. Falls back to trimming when it finds nothing.

**"This doesn't create new seams does it?"** — the question that found the real
hole, and worth recording with its answer.

*Geometrically, no.* The replacement starts at a point on the previous leg and
ends at a point on the next, and the handover is a single shared vertex, so the
legs meet exactly. More importantly the **new** boundary sits on a road both legs
already drive, whereas the original was chosen on the direct spine before either
route existed — which is exactly why the original bent both legs and this one
cannot.

*But the pins were a hole.* A replacement seam is new road carrying **no pins**;
the ones in that window belonged to the geometry being replaced, and producing
new ones means running the refiner over the seam — a routing pass per candidate,
on a leg being planned in the background. Without pins the car drives the window
its own way, so a seam that is camera-free *because of avoidance routing* would
be precisely the road the car does not take.

The fix is to splice the **plain fastest** path through the window rather than
the fewest-cameras one. That sounds backwards for this app and is not: the
fastest path is what the car drives unaided, so it needs no pins to be honoured.
The safety comes from the acceptance test instead — the seam is taken only if it
is shorter by more than `MIN_SEAM_GAIN_METERS` **and** passes no more cameras
than the two legs currently pass through that same window, measured against the
same camera set. Anything else, or any error at all, and the old join stands.

---

### F-36 · The pending line was jumpy
*Observed: 2026-08-16. Fixed.*

> the pending path animation is kinda buggy and jumpy

Self-inflicted. The marching-dash phase was Compose state, so every frame changed
a remembered value and recomposed the whole `AndroidView` update block — which
re-ran `renderRoute`, `renderChargers`, `renderCameras` and the fit check
**eleven times a second**, each rebuilding GeoJSON for the entire route. The line
stuttered because the map was being rebuilt underneath it.

Setting one paint property on one layer is all it ever needed, so the animation
now runs straight against the style from a `LaunchedEffect` and Compose is never
told. General lesson worth keeping: **an animation driven through recomposition
costs whatever the whole composable costs, not what the animation costs.**

Also asked for and done: the pending stretch now follows the **direct road**
rather than cutting a straight line across country. The spine is already computed
to choose the leg boundary, so `PlanOutcome.Routes.directAhead` is a free slice of
it, and each leg that lands replaces a piece with the camera-avoiding version. It
falls back to the straight line when the spine could not be routed, which is all
there is in that case.

---

### F-37 · All pins vanish after the first leg
*Observed: 2026-08-16. Fixed same day; introduced the same day.*

> We lose all pins after the first leg

With a screenshot showing leg one properly pinned and everything after it bare.

Self-inflicted, one line, and worth recording because of the shape of the
mistake rather than its size. The trimmed-lead plumbing added for F-32 published
`trimmedLeadWaypoints` — **one leg's** surviving pins after a spur was cut — and
the map used it like this:

```kotlin
steeringWaypoints = state.trimmedLeadWaypoints ?: overlay.waypoints
```

`overlay.waypoints` is every leg's pins merged. So the moment a trim or a seam
re-plan fired at the first boundary, a value describing *one leg* replaced a
value describing *the trip*, and every later leg's pins went with it. Before a
trim fired it looked perfect, which is why it survived a build.

The fix substitutes at the right level — the trimmed pins replace the lead leg's
inside the overlay, and the later legs merge on top afterwards.

**Display only, which is the one piece of luck here.** The chain actually sent to
the car is built per leg in `AppContainer.planRemainingLegs` and reaches the
monitor through its own channel, so the vehicle was still being steered through
the later pins the whole time. The map was lying about the route, not losing it.

General lesson, since this is the second time a leg-scoped value has been used
where a trip-scoped one belonged (see also the leg chain in F-20): **name and
place these so the scope is obvious at the call site.** A `trimmedLead…` value
belongs next to the lead, not next to the trip.

---

### F-38 · The map jumps back to your location when you back out of planning
*Observed: 2026-08-16. Fixed.*

> I don't want the map to go right back to my location after I hit back from the
> route planning

The auto-centre effect was keyed on the route's length, so it re-fired every time
a route went *away* — backing out of planning pulled the map from wherever the
driver had been looking straight back to their own dot.

The intent behind it was sound and is kept: someone who has just opened the app
and looked at nothing yet wants the map on them, so the nearby cameras load
without panning there first. That is a **once** though. After it, the map is
where the person chose to put it, and moving it is the app overruling them — the
same rule as the drive-follow camera, and §6.1 applied to the screen.

So it centres once and never again, and does not centre at all if they have
already moved the map themselves — which is the clearest possible statement that
they did not want to be there.

---

### F-39 · The spine times out and the trip is planned on a straight line
*Observed: 2026-08-16, from the app's own breakdown. Fixed.*

The breakdown read `fastest (gave up — out of time) (spine) 24.7 s` on a 1,289 km
trip. That is `SPINE_BUDGET_SHARE` doing its job and the trip being harder than
the threshold assumed: **under** `SPINE_FULL_LIMIT_METERS`, so no probe was used;
**over** the clock, so no answer came back. The fallback is the straight line,
which is why the pending stretch drew as a diagonal across three states — and,
worse, why the leg boundary was chosen on a line rather than a road.

A distance threshold cannot decide this, and trying was the mistake. The spine
now **retries with the probe** when the full road runs out of time. The full
version is still preferred where it works, because it is the better guide to
where a cut belongs; the probe is what happens when "where it works" turns out to
be false for this particular trip. No threshold guessing, and the straight line
goes back to being what it should be — the last resort when no road can be
routed at all.

---

### F-40 · The chooser meant nothing past the first leg
*Observed: 2026-08-16. Fixed.*

> the seam is still making an unnecessary c detour over a more direct route just
> to avoid one camera

Not the seam. Later legs were planned as **fewest-cameras regardless of what the
driver picked** — `minByOrNull { it.camerasPassed }`, hard-coded — so a driver
who deliberately chose Balanced still got legs that would detour miles around a
single camera, and had no way to say otherwise because the chooser only ever
appears for the first leg. A C detour to avoid one camera is exactly what
fewest-cameras means, and exactly what had not been asked for.

The choice now governs the whole trip: it is passed to `planRemainingLegs`, and
changing the selection re-requests the later legs against the new trade-off.

Two details worth keeping:

- **Restarting throws away landed legs.** That is the honest cost of changing
  the trade-off and it only happens on a deliberate tap.
- **The fallback errs toward the preference, never past it.** A leg through empty
  country produces one route and nothing to choose between; when the preferred
  option does not exist the fewest-cameras one is used, so nobody is ever handed
  *more* cameras than they asked for.

---

### F-41 · The pending line stuttered, vanished on a switch, and vanished again a leg later
*Observed: 2026-08-19, from real use. Fixed; the animation is confirmed by test,
the rest wants a real long trip.*

> the line animation is still jumpy and weird and the whole line disappears when
> I switch between camera free and fastest. also it should start calculating
> camera free by default first. also it seems like the line still disappears
> after calculating a leg

Four reports and, as it turned out, four separate causes. Worth keeping together
because the middle two are the same bug seen twice, at two different delays.

**The stutter was half a dash sequence.** MapLibre has no dash *offset* to
animate, so the marching ants are faked by cycling patterns whose solid run sits
half a line-width further along each time — Mapbox's own "animate a line"
recipe, which is **fourteen** steps. Shunt had the first seven. Those walk the
leading dash from 0 to 3 widths; the other seven walk the *gap* the same way, and
without them the pattern snapped 3.5 widths backwards every 630 ms. `dashPhase`
also restarted at zero whenever the tail moved, so the one moment the driver is
actually watching — a leg landing — was the one moment it jumped. Both fixed;
`PendingDashesTest` holds the cycle continuous, and it fails on the old sequence
with a 4.0-width step at the wrap.

**A cancelled leg run does not stop.** `legJob?.cancel()` looks like it ends the
previous run, and it does not: cancellation only takes effect at a suspension
point, and **a BRouter pass has none** — it is a tight CPU loop bounded by its
own timeout. So the abandoned run finished the leg it was computing, which can
be a minute, and only then unwound. Everything it wrote on the way out landed on
top of the run that replaced it:

- its `finally` cleared `planningLaterLegs`, which is what draws the pending
  line — so the line disappeared *while its replacement was still working*;
- its leg was appended to the new run's list, which is a leg planned to a
  trade-off nobody chose;
- and it could hand that leg to the drive monitor.

That is both "the whole line disappears when I switch" and "the line still
disappears after calculating a leg" — the same write, and the gap between the
two reports is exactly how long the abandoned pass took to notice. `legRun` is a
generation counter; only the current run may write anything.

**Switching also blanked the map on purpose, and shouldn't have.** Changing the
trade-off empties the later legs and re-plans them, which on a cross-state trip
is most of a minute showing a route that stops in open country. The legs are now
carried and replaced one at a time as the new ones land, with any leftovers
dropped when the run finishes. Carried **only when the remaining trip is
identical** — a genuinely new plan has different points, and showing it the last
trip's legs would be drawing a road nobody is going to drive.

**And the chooser opened on the fastest road.** `selected = 0`, which is FASTEST.
Wrong twice: the app exists to avoid cameras, so opening on the road that avoids
none makes the driver opt in to the point of it — and, far worse, the selection
*governs every later leg*. A driver who never touched the chooser got a
camera-aware first leg and a whole trip after it planned as the fastest road.
That is the "the last leg still is taking the fastest route" report, twice, and
it had nothing to do with the last leg. `defaultOption` opens on fewest-cameras,
falling back by camera count where that option does not exist.

One more thing came out of it: the pending line lost its shape the instant Go
was tapped, because `directAhead` is carried on the Solved phase and nothing read
it afterwards. The road onward does not stop being true when the car starts
moving, so `PlanScreen` keeps it until a new trip replaces it.

---

### F-42 · Three faults from one long trip, and a research brief that found a fourth
*Observed: 2026-08-20, from a 1,839 km plan. Fixed; none confirmed on a drive.*

> let's have the fewest cameras route show up first on top of the fastest one
> instead of underneath it. Also the line stays but for some reason doesn't
> follow the route. Can't have that. Also it has a weird angle to it. Also had a
> weird detour where the navigation goes away from the route, towards a camera,
> circles around it out of range, and goes back to the route.

**The card order was pointing the wrong way.** The options come back
fastest-first because that is the order the passes run in, and the sheet drew
them in that order — so the road that avoids nothing sat at the top of an app
whose purpose is avoiding it, with the option actually selected underneath it.
Reading order is a recommendation whether or not it was meant as one. The cards
are now sorted by cameras passed; the indices handed to `onSelectRoute` are still
the original ones, so nothing downstream knows about the display order.

**The pending line was drawn along a road from hundreds of kilometres back.**
Every plan computes `directAhead` — a slice of the spine it routed to choose its
own cut — and the planner's own comment says "every leg that lands replaces a
piece of it with real road". Nothing was doing that: only `Phase.Solved`'s copy,
from the **first** leg, ever reached the map. Two visible faults, both in the
screenshots:

- *It did not follow roads.* This trip is over `SPINE_FULL_LIMIT_METERS`, so the
  first leg's spine is a **probe** — deliberately bounded just past the leg
  window. Past that, `directAhead` is the straight sampled estimate. At 473 km
  planned out of 1,839, essentially all of what was being drawn was that
  estimate.
- *It left the route at an angle.* The camera-avoiding legs wander off the direct
  road, so the line ran sideways from the route's tip to rejoin a chord measured
  from a boundary the driver was two legs past.

`AppContainer.laterLegDirectAhead` now carries the newest leg's own slice, which
starts exactly where the drawn line ends.

**The detour circling a camera** is the leg boundary again, and the research
brief named the cause: later legs were planned with **no heading at all**.
`BrouterRouter` only sets `startDirection` when one is given, so a leg setting off
from a boundary had no cost attached to leaving the way the car arrived — and
since the boundary sits on the *direct* road while the camera-avoiding
continuation often does not, the cheapest way onto that continuation is
frequently back down the road just driven. Each leg now gets the bearing of the
previous leg's final segment. It **biases rather than forbids** (BRouter models it
as an imaginary previous position about a kilometre back), so the trim and the
seam re-plan both stay.

**And the brief found one nobody had reported, which is the most serious of the
four.** `legExtensions` was a **conflated** channel — capacity one, older value
dropped — on the stated premise that each extension carries the whole of what is
left. It does not: `DriveMonitor.extend` appends, so every leg is a delta.
Later legs are planned from the moment the chooser appears and each takes
seconds, while the monitor that drains the channel does not exist until Go is
tapped. So a driver reading the options for a minute on an eight-leg trip had
several legs waiting in a channel holding one, and the drive jumped from the end
of leg 1 to whichever leg happened to be last — skipping the road between, its
pins, and **its cameras**. Whether it broke at all depended on how long somebody
looked at the screen, which is why it would have read as "leg splitting is
unreliable" rather than as a bug with a shape. The channel is unbounded and the
monitor drains all of it per fix; `legExtensionChannel()` exists so a test can
build the production channel rather than one of its own choosing.

**Still open from the brief, in its own order of value:** the overlap handover
(§3 — plan leg N+1 from a point *inside* leg N with leg N's heading, which
deletes `trimDoubleBack` and `rejoinAtBoundary` rather than debugging them),
`LegSplitter`'s stop handling (a stop inside the leg window suppresses splitting
entirely; stop ordering uses straight-line distance and can delete a stop from
the trip), surfacing BRouter's own `indexInTrack` and snap distance instead of
matching by proximity, and the `pass1coefficient` sweep. The brief itself is
worth reading before touching any of it — it cites file and line for each claim.

---

### F-43 · The boundary, done by not creating one
*Landed 2026-08-20, with the maintainer's go-ahead. Measured only in tests.*

> Line animation is still feeling like it has a low frame rate. Also needs to
> get rid of the dots that stay there I want just the moving dashes. […] You're
> green to work on the overlap and the two stop bugs.

**The animation was eleven frames a second, and the dots were the line caps.**
The dash cycle stepped half a line-width every 90 ms — smooth in principle,
choppy in fact. It steps a quarter width every 40 ms now, the same pace along
the line in two and a half times as many frames, and the cycle is *generated*
from the offset rather than written out, so the step is a dial and the
missing-half-the-sequence bug of F-41 cannot recur.

The dots were subtler and worth remembering. A pattern that walks its solid run
along the line must contain zero-length dashes at the ends of the cycle —
seventeen of the twenty-eight phases have one — and `LINE_CAP_ROUND` draws a
zero-length dash as a **full circle**. They sit at fixed multiples of the period,
so they read as stationary dots while the dashes move past them. Butt caps draw
nothing for a zero-length dash.

**`LegSplitter`'s two stop bugs, both as the research brief described them.**

- A stop inside the leg window did not end the leg, it *suppressed the split
  altogether* — the KDoc said one thing and the code did another. On a 900 km
  trip with a charger 100 km in, that returned the unsplit plan CLAUDE.md §7.10
  measures at the fastest road and 43 cameras. A stop inside the window is now
  the boundary, which is free: the route must pass through it anyway.
- `split` built the first leg as `[origin, cut]` and `dropWhile`d the rest, so a
  stop before the boundary was in **neither** list — deleted from the trip. And
  it classified stops by straight-line distance from the origin, which misorders
  whenever the road bends. Both are ordered along the spine now, and every stop
  lands in the leg it falls in.

The rule that replaces "a cut is never made before the first stop" is narrower
and stated in the KDoc: a stop *outside* the window changes nothing, because
before the window it simply travels in the first leg, and beyond it the cut lands
short of it and a later leg reaches it. Forcing a first leg long enough to
include a stop most of a day away hands back the two-minute plan splitting exists
to prevent.

**And the overlap handover.** Everything else in `LegJoin` repairs a boundary
after the fact, and it is hard because the constraint that creates the damage is
Shunt's own: leg N+1 must start exactly where leg N ended. That point was chosen
on the *direct* road before either route existed, so leg N optimises arriving
there, leg N+1 optimises departing with no memory of how it arrived, and the join
between two individually optimal routes is jointly wrong. The spur, the C-shaped
detour and the loop around a camera at Neligh are all that.

So a leg is now planned from a point **inside** the leg before it —
`HANDOVER_METERS`, 15 km — carrying that leg's heading there, and the leg before
it is published truncated at that point. The router re-decides the last stretch
as part of choosing the first one, which is the decision it should have been
making all along, and the join is a vertex of both lines by construction.

Two things make it safe rather than merely better:

- **The truncation happens before anything is published.** A leg is cut back at
  the moment it is planned, not when the *next* one lands, so nothing that has
  reached the map or the car is ever revised. That is what rules out §6.1: there
  is no route in progress to change.
- **The lead boundary is exempt, deliberately.** The lead leg is the chooser's —
  shown, and possibly already pushed — so shortening it *would* be a change to a
  route in progress, and the car would hold pins for a stretch the next leg has
  taken over. That one boundary keeps the trim and the seam re-plan; every
  boundary after it is planned right rather than repaired. Doing it properly
  needs the monitor to be able to revise a leg it has already been given, which
  is its own piece of work.

`rejoinAtBoundary` is skipped wherever a handover happened, which also removes
the three or four graph searches per boundary the brief flagged as wasted. It and
`trimDoubleBack` stay for the lead boundary and for a leg too short to give any
of itself away.

---

### F-44 · A leg is labelled before it is reshaped
*Observed: 2026-08-20, from a real plan. Fixed; unconfirmed on a drive.*

> Also it labeled a route with a camera as camera free again with an avoidable
> camera

**Every route Shunt shows is labelled by measuring it** — `passedCameras` comes
from `CameraIndex.seeing(polyline)` — and three things change a leg's line
*after* that measurement is taken:

| what | effect on the line | effect on a stale label |
|---|---|---|
| `trimDoubleBack` | removes a spur | over-reports; safe |
| the handover truncation | removes the tail | over-reports; safe |
| `spliceSeam` | **adds road neither leg drove** | under-reports; not safe |

The third is the one that bites, and the research brief had already flagged it:
`rejoinAtBoundary` guards the *count* — it refuses a replacement that passes more
cameras than the join it replaces — but never writes the replacement's cameras
back onto the leg. So a camera on a spliced seam was drawn nowhere, counted
nowhere, and announced never, on a leg presented as camera-free.

`AppContainer.relabel` re-measures a leg against the line it ends up with,
whenever any of the three moved it. One cached camera lookup, only on the legs
that changed. A lookup that fails leaves the old label and *says so in the log*,
because a count nothing measured is exactly what this exists to stop.

Two things went with it. `DrivePlan.cameras` was `chosen.passedCameras` — the
route before it was reshaped — so the drive monitor was warning about the line
that had been planned rather than the one the car was being sent along; it takes
the reshaped leg's now. And the geometric trim runs again at handover boundaries:
skipping it there was an optimisation on the assumption the join must be clean,
and it is pure arithmetic that finds nothing when it is. The expensive
`rejoinAtBoundary` is still skipped, which is where the three or four saved graph
searches actually were.

**The pending line is thinner and fainter too.** At the same weight as the route
a dash running along a real road is impossible to tell from a spur of the route
itself, which is what it was being read as.

---

### F-45 · A clean leg with red dots on it, and a spine that gave up
*Observed: 2026-08-20, from a 1,424 km plan. Fixed; unconfirmed on a drive.*

> Er, something happened. […] Also make the export log download not share

**"Camera-free" and four red camera dots on the same screen, both true.** The
result sheet's verdict describes the leg on the chooser; the map draws
`overlay.passedCameras`, which merges *every* leg planned so far. So a clean
first leg with cameras two hundred kilometres further on showed a green
"This leg passes no cameras" above a route with red dots on it. Reported twice
as the app labelling a route camera-free with an avoidable camera on it, and
that reading is the reasonable one — two true statements that contradict each
other on a safety surface are worse than one wrong one. The verdict now names
what the rest of the trip carries: "N cameras later in the trip", in the same
red the dots are drawn in.

**The spine spent a third of the plan budget failing.** The breakdown read:

```
fastest (gave up — out of time) (spine, full)   24.7 s
fastest (spine)                                  2.2 s
fastest / blocked / balanced             0.2 / 0.4 / 0.5 s
```

`SPINE_BUDGET_SHARE` is a third of 75 s, and the full-road attempt spent all of
it and came back with nothing; the probe then did the same job in 2.2 s. That
share was set when running out of it cost only a cruder corridor — F-39 added
the retry, and from then on a failure cost the whole share *plus* the probe, out
of the budget the avoidance passes need. Running out there is how a driver gets
handed the fastest road (§7.10).

`SPINE_FULL_ATTEMPT_SHARE` puts the full attempt on a third of the spine's share
— about nine seconds — and leaves the rest for the probe. It is a bound on what
a failure costs rather than a guess about which trips can finish, which is the
distinction F-39 says a distance threshold gets wrong. **Reasoned from one
measurement and wants the benchmark**, per CLAUDE.md §8.

**The diagnostic log saves instead of sharing.** Asked for directly. A share
sheet asks who to send a file to before the person has read a word of it, and
hands it straight to whatever they tap — on a log that can contain every road
they drove, that is the wrong order of operations. It goes through the system
document picker now, to a file they choose, on their own device. The
`FileProvider` and its cache directory are gone with it, and anything left in
that directory by an older build is deleted at startup: it was a copy of a log
that has since expired, quietly outliving the week the log itself promises.

---

### F-46 · The label on a single option planned the whole trip fastest
*Observed: 2026-08-20, from a real diagnostic log. Fixed; unconfirmed on a drive.*

> And yeah it still is hitting avoidable cameras.

**The log settled it in four lines.** Two trips minutes apart, same phone:

```
planned FASTEST 250km/9cam/0pins, FEWEST_CAMERAS 270km/0cam/34pins
  → legs: 190/0, 282/0, 255/0, 299/0, 346/0, 251/0, 247/0 cameras

planned FASTEST 260km/0cam/0pins
  → legs: 255/0, 235/1, 154/62 cameras
```

The second trip's lead leg was **camera-free**. Every pass found the same clean
road, so the options deduplicated to one card — and that card is labelled
`FASTEST`, because that is the pass that ran first. `requestLaterLegs` passed
`option.choice` on as the trip's trade-off, so every later leg was planned as the
plain fastest road, and the leg into the metro took **62 cameras it had options
to avoid**.

It is the same shape as F-41 through a different door. There the selection was
wrong; here the selection is right and its *name* is wrong. The choice is the
name of the pass that produced the geometry, not a statement about what the
driver wants.

`preferenceOf` asks the question that actually matters: did the driver settle
for more cameras than they were offered? Picking the least-watched option
available — which includes the case where there is only one — means fewest
cameras, for the whole trip. Only a deliberate tap on something worse carries
speed forward.

**Two other things the log showed.**

`next leg failed to plan` ended both runs and said nothing about why. A routing
error, a missing map tile and an empty option list want completely different
responses, and the line covered all three. It names the failure now.

And `0pins` on a 260 km lead leg is not a bug: the fastest option is never
refined, by design, because it is the road the car takes unaided. Worth knowing
before anyone chases it — it does mean that when the options collapse to one, the
car is steered by nothing at all.

**The pending line regressed, and the fix from F-42 caused it.** Preferring the
newest leg's `directAhead` is right near the tail and wrong further out: every
later leg's spine is a *probe*, bounded just past its own leg window, so most of
its slice is the straight estimate — while the chooser's, on a trip whose full
spine ran the whole way, is road to the destination. Preferring the newest one
blindly therefore replaced road with a ruled line. Reported as "it was better
before". `directAheadRoadPoints` marks where road ends in each slice, the leg
publishes only its road, and the chooser's fills in beyond it. Neither source is
right alone; together they are road as far as anything has routed it.

---

### F-47 · The preference fix worked; what is left needs the log to say more
*Observed: 2026-08-20, from a real log after F-46 landed. Partly fixed, partly
still open — recorded honestly as such.*

**F-46 worked, and the log is how we know.** Same 1,424 km trip to Washington,
same phone, after the fix:

```
planned FASTEST 260km/0cam/0pins
  → legs: 255/0, 140/0, 201/0, 270/0, 234/0, 265/0, 216/1 cameras
```

Six camera-free legs and a whole trip at 1,362 km camera-free, against the same
trip's 0/1/**62** before. The single remaining camera is on the last leg into the
city.

**What is still open, and why it could not be settled from the log as it was.**

- *The last leg's one camera.* The line read "NO avoidance option — the fastest
  road is all that came back (1 cameras)", which does not say whether an
  avoidance option **existed**. `unavoidableAtEndpoints` is exactly the number
  that decides it — a camera pointed at where the driver is going cannot be
  dodged, because arriving is what triggers it — and nothing was logging it.
  Later legs now log every option they were offered, with its camera count, its
  endpoint count and whether the hard block failed, the way the chooser has
  logged the lead leg since the beginning.
- *That message fired on six clean legs and once where it mattered.* A leg
  through empty country also comes back with one option, because every pass finds
  the same road and they deduplicate — so the warning that exists to catch a leg
  with no avoidance was mostly announcing legs with nothing to avoid. It is
  gated on the leg actually passing a camera now.
- *Where a boundary is.* Every artifact reported in this stretch of work — the
  spur, the C, the loop around a camera, a pair of parallel lines — has come down
  to "is this at a leg boundary?", and the log could not say. The handover line
  carries its point now, so with locations enabled every boundary can be found on
  the map.

**Two real bugs found while looking.**

`FEWEST_CAMERAS` was being honoured by *name*: `options.firstOrNull { it.choice
== preference }`. The pass names do not always rank the way they read — when the
hard block fails and the weighted fallback takes over, the option labelled
FEWEST_CAMERAS can pass more cameras than the balanced one beside it. Asking for
the label would hand the driver the worse route and report it as the best. It
takes the fewest-camera option now, whatever it is called.

And the straight-line fallback spine was being marked as **road**. When a leg's
spine fails to route entirely, `spine` falls back to the sampled straight line
between the trip's points — and `directAheadRoadPoints` counted those as routed
road, so the pending line followed a straight chord *in preference to* road
another leg had really routed. Reported as the line showing "as a straight line,
and then fix itself for portions and break again", which is precisely a per-leg
coin flip on whether that leg's spine routed.

**What remains genuinely unknown, stated plainly.** Past where any spine has been
routed there is no road data at all, and the pending line is a straight estimate
by construction — on this trip the chooser's probe reaches about 337 km of 1,424,
and each later leg adds only its own lookahead. A chord across Lake Erie is
honest and looks broken. The options are to route more spine (slow, for a line
nobody drives), to end the line where road knowledge ends (no longer reaches the
destination), or to leave it. Not a decision to make from a desk.

---

## Resolved

*(none yet — move entries here with the commit that fixed them and what the
real cause turned out to be, so the reasoning survives.)*
