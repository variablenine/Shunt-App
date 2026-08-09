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

**Still not done:** not routing back onto the abandoned stretch in the first
place (design above), and reviewing the alert cadence, which the maintainer
also flagged. Standing down bounds the damage from both; it does not fix
either.

### F-4 · Long routes take minutes to plan
*Observed: pre-2026-08 build. Partly addressed — needs re-measuring.*

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
- **No fallback, just large numbers.** Then it is raw scale, and the levers are
  concurrency across the independent passes (BRouter thread-safety unknown, and
  a wrong route from a data race would be far worse than a slow one) or a
  tighter corridor.

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

---

## Resolved

*(none yet — move entries here with the commit that fixed them and what the
real cause turned out to be, so the reasoning survives.)*
