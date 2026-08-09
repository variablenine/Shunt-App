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
- **A road the driver cannot use should stop being offered — not done.** After
  going off-route there is nothing stopping the re-plan from routing straight
  back onto the stretch just abandoned, which on a closed road is a loop.

**Design for the missing half**, so it can be picked up cold. Give
`BrouterRouter.route` an explicit list of points to treat as impassable,
separate from cameras (cameras are field-of-view shapes; this is a plain
blocked circle), thread it through `BrouterPlanner.plan` and
`AppContainer.replanFrom`, and have `DriveMonitor` pass the portion of the old
route immediately ahead of where the driver left it. Keep it to the *abandoned
stretch* rather than the whole remaining route, and let it expire with the plan
— a road closed this afternoon is open tomorrow, and Shunt has nowhere to
persist that belief and no business trying.

**Not yet known, and it changes the design:** what "did not cope well" was.
A re-plan that turned the car around, a re-plan straight back onto the closed
road, no re-plan at all, or alerting that would not stop are four different
bugs. Worth pinning down before building.

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

**Still to check on a real phone:** how long the route-deciding passes alone
take on a long trip. If that is still slow, the remaining levers are
concurrency across the avoidance passes and shrinking the nogo set — see
CLAUDE.md §7 for why the second one is dangerous.

---

## Resolved

*(none yet — move entries here with the commit that fixed them and what the
real cause turned out to be, so the reasoning survives.)*
