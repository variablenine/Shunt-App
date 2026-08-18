# Leg planning and the pending-road line — investigation

*2026-08-16. Branch `fix/leg-planning-pending-line`.*

Leg planning and the pending-road line (`PlanOutcome.Routes.directAhead`, drawn
on the map for the stretch not yet planned) were misbehaving in a way that
varied between trips and could not be characterised from the outside.

**Varying between trips is the finding, not an obstacle to it.** It is the
signature of behaviour that depends on which *branch* a trip happens to take
rather than on anything about the trip. There are four branches the spine can
land in and nothing was pinning any of them.

---

## What was reproduced

`LegPlanningCharacterizationTest` exercises `BrouterPlanner.plan` with
`maxLegMeters` set, against a routing lambda that returns a dense straight road —
no network, neutral placeholder coordinates in the central US. It asserts, for
each regime, how far `directAhead` reaches relative to the destination, whether
`remaining` starts where the leg's options end, and whether `wholeTripMeters`
agrees with the legs actually planned.

Two of the four regimes failed, and they are exactly the two that use the probe:

| Regime | Trip | Pending line |
|---|---|---|
| Full spine (under `SPINE_FULL_LIMIT_METERS`) | 800 km | reaches the destination |
| **Probe spine** (over it) | 2,000 km | **stops 1,662 km short** |
| **Forced-probe retry** (full spine timed out) | 800 km | **stops 462 km short** |
| Straight-line fallback (spine unroutable) | 2,000 km | reaches the destination |

### The mechanism

The hypothesis under test was confirmed exactly as stated.

`spineProbe` deliberately stops the direct road just past the leg window on a
long trip — the spine only exists to choose a cut and draw the first leg's camera
corridor, and routing the remaining thousands of kilometres to answer those two
questions is what used to make a continental trip fail outright (F-27).
`directAhead` is then `spine.subList(cut.index, spine.size)`, so it inherits that
truncation and stops wherever the probe did.

### Why it looked inconsistent

Two separate reasons, and the second is the important one:

- The **length threshold**: a 1,400 km trip uses the full spine and gets a
  complete pending line; a 1,600 km trip uses the probe and does not. Same app,
  same behaviour, different-looking result.
- The **forced-probe retry is entered on a timeout**. So the *same trip* can take
  the full-spine branch on one run and the probe branch on the next, depending
  on how busy the phone was. That is the inconsistency the report described, and
  no amount of staring at one trip would have shown it.

---

## What was ruled out

**Callers are clean.** Every caller of `plan(...)` was audited against the
precondition in the `maxLegMeters` doc comment — that it must stay off until all
of them handle `Routes.remaining`:

| Caller | `maxLegMeters` | Handles `remaining` |
|---|---|---|
| `AppContainer.planRemainingLegs` | opted in | yes — loops on it, feeds `legExtensions` to the drive monitor |
| `AppContainer`'s `RoutePlanner` (plan screen) | opted in | yes — `Phase.Solved.remaining`, then `requestLaterLegs` |
| `AppContainer.replanFrom` (mid-drive) | null | n/a — deliberately unsplit, per the doc comment |

No caller opts in without extending the drive at the boundary, so that
explanation for "broken leg planning" is dead.

**`remaining` and `wholeTripMeters` were never wrong.** In every regime,
`remaining.first()` is within a kilometre of where the leg's options end, and
`remaining.last()` is the real destination. `wholeTripMeters` describes the whole
trip in both spine regimes. Only `directAhead` was short.

**The straight-line fallback is not implicated.** When no spine can be routed at
all, `sampleSpine(points)` runs origin to destination, so the pending line is
complete — cruder, but complete. An early version of the fixture failed this
regime and that turned out to be the fixture failing the chooser pass as well as
the spine, which is a different thing entirely ("no route exists"). Corrected
before drawing any conclusion.

---

## What changed

One change, in `BrouterPlanner.plan`, where `directAhead` is built.

Past what the spine actually routed there is no road anybody has computed, and
computing it is the cost the probe exists to avoid. So `directAhead` now
continues from the end of the routed part as the **straight chain through the
stops still to come**, sampled at the same `SPINE_SAMPLE_METERS` spacing so the
line stays uniform instead of ending in one enormous chord.

The contract on `PlanOutcome.Routes.directAhead` was corrected to say so. It
previously claimed the pending stretch followed "roads that exist", which was
true only up to the probe point and is the kind of comment that makes the next
person trust a value further than they should.

**This is an honesty fix more than a visual one.** The map already appended the
destination when drawing, so the rendered line did reach it — with a single
straight segment across most of a continent. What changes is that the line is now
uniform, the truncation is documented, and the estimate is explicit rather than
emergent from two components disagreeing about whose job it was.

### Deliberately not changed

- **The 5 km sampling** (`SPINE_SAMPLE_METERS`), which is why the line cuts
  corners across winding roads. It is a slice of the spine, and the spine is
  sampled that coarsely on purpose — every camera lookup walks it. Now pinned by
  a test so the resolution is a stated property rather than a surprise. Drawing
  it at street zoom would want its own geometry.
- **`RouteMap.onwardOf`**, which filters pending points by "closer to the
  destination than the leg's end". That is a crude ordering proxy and could drop
  or keep the wrong point on a route that curves back; it did not show up in any
  regime here, and changing it is not what these tests measure.
- **The probe itself.** Stopping the spine short is correct and is what makes a
  continental trip plannable at all (F-27). The bug was never the probe; it was
  a consumer inheriting the probe's truncation without knowing about it.

---

## What still needs a real drive

- **A trip over 1,500 km straight-line.** The probe regime is only reachable
  there, and everything above was measured against a synthetic straight road. The
  thing to watch is that the dashed line now runs the whole way to the
  destination and shortens as each leg lands.
- **A trip where the full spine times out.** The forced-probe branch cannot be
  provoked deliberately from the app — it needs a real phone under real load.
  The breakdown shows it: `fastest (gave up — out of time) (spine)` followed by a
  second spine pass.
- **Whether the straight tail reads as a road.** It is an estimate drawn in the
  same dashes as the routed part. If it misleads on a real trip, marking it
  differently — a lighter dash, or a different colour past the routed end — is
  the follow-up, and the join is already computable from where the spine stopped.

---

## Out of scope, untouched

Charging re-route, waypoint POI resolution by the car, widen-round budget
starvation, and off-route recovery were all excluded from this run and none was
modified.
