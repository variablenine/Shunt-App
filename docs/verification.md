# Verification — what still has to be seen working in a car

Almost everything in Shunt's vehicle path was diagnosed from a field report or
from looking at a planned route on the map, and fixed without a car in the loop.
That is the only way it could have been done, and it leaves a specific debt:
**a list of things believed fixed and never observed working.** Losing track of
that list is how a beta ships with a bug the maintainer already reported once.

This file is that list, plus how to provoke each item on purpose.

**Privacy rule, same as everywhere else.** Tests here are described by the
*shape* of the road — "a divided highway with a median crossover", "a route that
doubles back within a few hundred metres of itself" — never by place. Anyone
running these picks roads near themselves that match the shape. Do not commit a
worked example with real place names in it, however convenient; see CLAUDE.md §3.

---

## How to use this

Each entry has a **setup** (the road shape and app state you need), a **pass**
(what you should observe), and a **fail** (what the bug looked like when it was
reported, so it is recognisable when it recurs).

Work down the priority order. The first section is everything that can put the
car somewhere the driver did not choose, which is the only category that can
actually hurt someone.

When something fails, the fastest useful bug report is the diagnostic log export
plus one sentence about what you expected. Keep the app open afterwards — the log
is rolling and a week of driving will eventually push a bad drive out of it.

---

## A. The car goes somewhere it shouldn't

### A1 · The charging check hands the car back correctly
*Fixed 2026-08-15 (F-17), never seen working.* **The most recently reported
failure, and the one most likely to still be wrong.**

**Setup.** A trip long enough that the car considers a charging stop, on a
vehicle being steered pin by pin (the driving sheet says "Guiding your car
waypoint by waypoint"). Drive it for at least a few minutes so several charging
checks fire — they run about every 45 seconds when the conditions allow.

**Pass.** The car's own screen keeps showing a point a few miles ahead, and it
changes as you pass each one. The driving sheet's activity line flickers to
"asking the car about charging" and back to "watching for cameras", and the car's
destination does *not* become the final destination while that happens.

**Fail (as reported).** After a charging check the car's screen shows the trip's
real destination, the car leaves the planned route, the app announces off-route,
and the re-plan comes back through a camera.

**Also worth watching.** Even with the aim restored, the recovery re-plan is
given 12 s while one avoidance pass on a long trip needs about 20 — so a re-plan
early in a long drive can legitimately come back as the fastest road. That is a
known open problem, not a regression. If you see off-route → re-plan → cameras,
note whether the car had been handed the destination first (A1 failing) or not
(the budget problem).

### A2 · Standing down when the driver disagrees
*Fixed, never seen working.* This is the most important behaviour in the app.

**Setup.** Start a drive, then deliberately refuse the route: take a different
road at a junction and keep going. Do it repeatedly — four or five times inside a
few minutes.

**Pass.** Shunt re-plans a couple of times, then says once that it has stopped
commanding the car, and after that it stops pushing anything. Camera warnings
keep working. Your own destination on the car's screen stays put.

**Fail (as reported).** "It kept sending the waypoint back to my car repeatedly
every time it rerouted, so when I tried to override it on my car it would
override me trying to override it, until I cancelled the navigation in the app."

**Note.** Standing down is one-way for the drive by design — Shunt cannot see
that a road has reopened, so nothing silently re-earns control. Ending the drive
and starting a new one is the reset.

### A3 · The road you refused stops being offered
*Fixed, never seen working.*

**Setup.** As A2, but leave the route once and let it re-plan.

**Pass.** The new route does not put you back on the stretch you just left.

**Fail.** The re-plan turns you around toward the road you refused.

### A4 · A waypoint is not abandoned before its turn
*Fixed, never seen working.*

**Setup.** A route with a turn at a signalled junction, ideally one where you
will wait at a red light in a turn lane. The waypoint sits just past the corner.

**Pass.** The car stays aimed at the point past the turn while you wait, and only
advances to the next one after you have made the turn.

**Fail (as reported).** Sitting at the light, the app advanced past the waypoint,
the next one lay straight ahead, and FSD moved to leave the turn lane.

### A5 · A waypoint is not triggered by passing near it
*Fixed, never seen working.*

**Setup.** A route that comes back close to itself — a cloverleaf, a frontage
road alongside the carriageway, a switchback, or any place where the outbound and
return legs run within a few tens of metres. Plan a route that uses one, with a
waypoint on the *later* pass.

**Pass.** Passing the waypoint's position on the earlier leg does nothing. It
advances when you actually reach it, by road.

**Fail.** The waypoint advances while you are still a mile from it by road, and
the car is handed a target it has already driven past.

### A6 · No turns through the emergency-vehicle gap
*Fixed, never seen working. Needs a route that would want one.*

**Setup.** A divided highway (motorway or trunk) with the maintenance/patrol
crossovers in the median, and a camera sited such that turning round would help
the route. Plan a fewest-cameras route along it.

**Pass.** The route uses a proper interchange or a public median U-turn. It never
routes you through a crossover.

**Fail.** The route turns across the median at a gap that is plainly not a public
road.

**Caveat worth knowing before calling it a failure.** The rule keys on the OSM
tag `service=emergency_access`. A crossover mapped as a bare `highway=service`
with nothing to distinguish it is invisible to this, and the fix is to tag it in
OpenStreetMap. If you find one, that is a mapping contribution, not a bug.

### A7 · The waypoint the car receives is the waypoint on the screen
*Two causes fixed, cause not confirmed.*

**Setup.** Any steered drive. Compare the point the car's screen names against
the pin the app is showing.

**Pass.** They correspond. The car's named destination is a place near the pin,
not the centre of a town, county, or state.

**Fail (as reported).** The car navigates to somewhere coarse — the middle of a
city — the way an over-quick Google Maps share used to.

**The experiment that would settle it** is in field note F-2: push a destination
whose coordinates sit clearly between named places, then read the active route
back and compare what the car reports against what was sent.

---

## B. The route itself

### B1 · A camera the route squeezes past is pinned on both sides
*Added 2026-08-15, never seen in a car.*

**Setup.** Plan a route that dodges a camera by taking the next street over —
where the avoided camera ends up within a few hundred metres of your line.

**Pass.** On the map there is a waypoint shortly before the squeeze and another
shortly after it. Driving it, the car stays on the planned street rather than
rejoining the main road early.

**Fail.** A long unpinned stretch running past an avoided camera, with nothing
holding the car onto the detour.

### B2 · Every turn carries a pin
*Added, never seen in a car.*

**Setup.** Any route with several turns.

**Pass.** There is a waypoint shortly after each turn the route takes. Straight
stretches have few or none.

**Fail.** Turns with no pin near them, and the car carrying straight on through
one.

### B3 · Pin density rises in town
*Added, never seen in a car.*

**Setup.** A route that runs from open country into a built-up area.

**Pass.** Pins are roughly half a kilometre apart on the open road and tighten to
a couple of hundred metres where the streets and cameras are dense.

**Fail.** The same spacing everywhere, or — worse — pins so close together in
town that the app is pushing a new one every few seconds.

### B4 · Long trips are cut into legs
*Solver done and measured; **not yet wired into the app**, so there is nothing
to test here until it is. Left in the list so it is not forgotten.*

When it lands, the things to check are: the first leg arrives quickly enough to
set off on; the boundary is in open country rather than in a town; the rest of the
route appears while driving without interrupting anything; and arriving at a
boundary that has not been extended yet says so rather than announcing arrival.

---

## C. Alerts, audio, and the screen

### C1 · Spoken alerts reach the car's speakers
*Added, never confirmed in a car.*

**Setup.** Phone connected to the car over Bluetooth, music playing, a route with
a camera on it.

**Pass.** The warning is spoken aloud through the car's speakers, the music ducks
rather than stopping, and it works with the phone screen off and no signal.

**Fail.** Silence, or the alert coming out of the phone's own speaker while
connected to the car.

### C2 · Cameras are not re-announced after a re-plan
*Fixed, never seen working.*

**Setup.** A route with a camera on it. Leave the route near that camera so a
re-plan fires while the camera is still in range.

**Pass.** The camera is announced once per tier. The re-plan does not start the
announcements over.

**Fail (as reported).** "The alerts would not stop."

### C3 · The activity line says what the app is doing
*Added, never watched during a real drive.*

**Pass.** The line on the driving sheet moves between watching for cameras,
sending waypoint *n* of *m*, asking the car about charging, re-planning, and
stood down — and matches what you can see happening.

---

## D. Planning, search, and the map

### D1 · Range warnings are neither missing nor crying wolf
*Changed 2026-08; the derate that caused a false alarm was removed.*

**Setup.** A trip that the car's own estimate says fits, but not by much.

**Pass.** No warning on a trip the car itself says it can make. A warning when
the camera-avoiding detour genuinely outruns what is left.

**Fail (as reported).** A red "not enough range" on a trip the driver knew fitted
— the car said 258 km against a 241 km route and Shunt claimed 178 km usable.

### D2 · Category search returns nearby places
**Setup.** Type "coffee", "gas", "groceries" while somewhere with those things.

**Pass.** Real nearby places, in a second or so.

**Fail.** A county in another state, or a shop literally named "Grocery" on
another continent.

### D3 · Search does not report an error while typing
*Fixed 2026-08.*

**Pass.** Typing quickly never shows a failure message; results simply catch up.

**Fail.** "Couldn't reach search" appears mid-word and only clears when a
character is added and removed.

### D4 · One-way arrows point the right way
*Fixed 2026-08; the sprite was 90° out, not 180°.*

**Pass.** On a one-way street, the arrows point the way traffic goes. Check both
a north–south and an east–west one — a 90° error looks correct on one axis.

---

## E. Testing where there are no cameras

Some counties have removed their ALPRs entirely, and DeFlock will eventually stop
listing them — which makes avoidance impossible to exercise locally. A seeded
synthetic camera mode is planned for exactly this (see the roadmap). Until it
lands, the options are:

- Plan routes into an area that still has coverage, without driving them, and
  check the route and pins on the map. Most of section B can be checked this way.
- Use the repository benchmark (CLAUDE.md §8) with a region tile that has real
  cameras in it. It routes over real map tiles and prints what the app would show.

---

### C4 · The diagnostic log records enough to be worth sending
*Added 2026-08-15, never used in anger.*

**Setup.** Drive anything, then open vehicle settings and look at the export
count. Export with locations off, read the file.

**Pass.** There is a line per waypoint sent, per charging check, per re-plan, and
one per plan with the options that were offered. With locations off, no
coordinates appear anywhere in the file. With them on, the header says so in
capitals.

**Fail.** An export that is empty after a drive, or one that leaks coordinates
with the toggle off — the second is a privacy bug and should stop a release.

---

## Keeping this file honest

When something here is confirmed working in a car, say so in the entry with the
date, and move the corresponding item out of "unconfirmed" in CLAUDE.md §7 and
`docs/field-notes.md`. When something fails, add what actually happened to the
field notes — the observation is worth more than the fix, because the fix can be
re-derived from it and the reverse is not true.
