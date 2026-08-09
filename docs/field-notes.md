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
*Observed: pre-2026-08 build, several long drives.*

Expected: send a long destination, the car inserts a Supercharger, Shunt sees
that, and re-navigates to the charger via a camera-avoided leg — arriving there
is a leg end, not the trip's end.

Actual: the app shows the route, but the car just navigates to the final
destination. Shunt never updates when the car routes through a Supercharger.

Notes: `ChargeStopCoordinator` implements the intended behaviour, so the
question is why it never runs or never concludes. `AppContainer.chargeStopCoordinator()`
returns null in several cases; the range gate treats *unknown* range as "plenty",
which is the wrong default for exactly the long trips where charging matters.

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

### F-3 · A closed road was routed onto, and leaving it went badly
*Observed: pre-2026-08 build.*

The route used a road that was closed. Once the driver left it, the app did not
cope well.

Two separate needs fall out of this:
- Re-planning must respect the **direction of travel** while moving. An answer
  that begins with a U-turn is not an answer at 60 mph.
- A road the driver cannot actually use needs to stop being offered.

### F-4 · Long routes take minutes to plan
*Observed: pre-2026-08 build.*

A route of about 5 hours took roughly 5 minutes to calculate. Unusable for real
driving, and dangerous where mid-drive re-planning is involved, since a re-plan
that takes minutes arrives long after the decision it was needed for.

---

## Resolved

*(none yet — move entries here with the commit that fixed them and what the
real cause turned out to be, so the reasoning survives.)*
