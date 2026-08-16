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

### A0 · Which share format the car actually honours
*The highest-value experiment available, and the app already contains it.*

**Setup.** Vehicle settings → the navigation command probe. It tries plain
coordinates, a `geo:` URI, an OpenStreetMap link, an Apple Maps link and two
Google Maps forms.

**Why this first.** On a car requiring signed commands, Shunt falls through to
Tessie's `share`, which takes a string the car resolves itself — currently bare
`"lat,lon"`. Observed on a real drive: a house number came out as a *different*
house number and the destination landed on the wrong side of the street, both of
which are what address search does to a coordinate. If a different form is taken
as an exact pin, that fixes the last hundred metres of every trip.

**It matters most for charging.** A Supercharger resolved to a street address is
one the car drives to the road outside, and FSD cannot park in a stall from
there.

**Record which forms landed and how precisely**, and put it in F-19. Do not
change the share format without this — it is the only channel that works on such
a car, and breaking it breaks everything.

**Pass.** Each accepted channel says either "WORKS — the car moved to this point"
or "accepted, but the car did not move to it". Both are answers. Run it parked
and awake; the probe wakes the car to read its state back.

**Fail (as reported, 2026-08-16).** Every accepted channel read "accepted, car
state unreadable", which is not an answer at all — it cannot be told apart from
the car ignoring the command. The cause was reading Tessie's *cached* state
moments after issuing a command, so the read was answered from before the
command was sent. Fixed; re-run before drawing conclusions from that first set
of results.

**Known from that run**, and still true: this car rejects `geo:` URIs and
OpenStreetMap links outright, accepting only bare coordinates and an Apple Maps
link. Google Maps links are probed too now — as strings handed to the car, not
as any call to Google.

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
*Wired through 2026-08-15 and measured in the benchmark; never driven.*

**Setup.** Plan a trip of 300 km or more, ideally one whose fastest line passes
through a town or two.

**Pass.** The chooser appears in well under half a minute and says plainly that
it is showing the first part of the trip, with the whole trip's distance beside
it. Tapping Go starts driving normally. Somewhere in the first few minutes the
route on the map grows to reach the real destination, with no interruption to the
drive and no re-announcement of cameras already passed. The boundary — where the
route grew from — should be out in open country, not in the middle of a town.

**Fail.** Any of: the chooser taking minutes; the sheet showing a leg's distance
as if it were the trip's; the map never growing; the car being re-pushed a
waypoint it already passed as the route grows; cameras announced twice; or the
drive announcing arrival at the boundary.

**The one genuinely bad outcome** is reaching the boundary before the next leg
lands — you would hear "still working out the rest of the route". It should be
impossible in practice (the boundary is over an hour's driving away and a leg
plans in seconds), so if it happens, that is worth reporting in detail.

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
listing them — which makes avoidance impossible to exercise locally. **Practice
cameras** exist for this: vehicle settings has a switch that mixes in a made-up
field of ALPRs, identical on every device and every run, so a route can be
planned and driven against something.

### E1 · Practice mode is impossible to mistake for real data
*Added 2026-08-15.*

**Setup.** Turn on practice cameras in vehicle settings and go back to the map.

**Pass.** A banner across the top says practice cameras are on, for as long as
they are. Tapping one calls it "Practice camera (not real)". Turning the switch
off removes them and the banner together.

**Fail.** Any screen where an invented camera is indistinguishable from a Flock
unit. That is a stop-ship bug, not a cosmetic one: the whole app is a claim about
where you are watched, and a false claim is worse than no app.

### E2 · Avoidance actually works against them
**Setup.** With practice mode on, plan a route across 20-30 km of the field.

**Pass.** The three options differ; fewest-cameras detours visibly around the
dots; pins appear either side of the ones it squeezes past. Driving it, the
approach warnings fire and are spoken.

**Fail.** All three options identical, or a "camera-free" route running straight
through the dots.

The other two ways to test without local cameras are still worth knowing:

- Plan routes into an area that still has real coverage, without driving them,
  and check the route and pins on the map. Most of section B works this way.
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

### D5 · Cancelling actually cancels
*Fixed 2026-08-16.*

**Setup.** Plan a long trip — long enough to be cut into legs — wait a moment for
the line to start growing, then cancel.

**Pass.** The route, the later legs, the pins and the destination pin all
disappear at once, and nothing keeps being planned in the background. Planning
the same trip again re-frames the map on it.

**Fail (as reported).** "When I cancel a route it needs to cancel the leg
calculations too. In fact, the route needs to disappear, it hasn't done that this
whole time." Two causes: the map only ever *set* the route source and skipped it
entirely when there was nothing to draw, so it kept the last line forever; and
the background leg planning belonged to the container, which nothing told to
stop.

---

### B5 · The last leg of a long trip avoids cameras too
*Fixed 2026-08-16.*

**Setup.** Plan a trip long enough to be cut into several legs, ending on a pin
dropped in a **city centre** — the denser the better, and it needs to be a place
a camera actually watches, which is most downtown kerbs. Washington DC and San
Francisco are the two the report came from. Let the later legs finish planning
(the line grows to the destination from a standstill), then look at the last one.

**Pass.** The last leg detours like the others. If it does pass cameras, the
result sheet says how many of them watch where the trip starts or ends and that
no route can avoid those — and the number it names is not smaller than the number
of cameras it lists. Exporting the diagnostic log shows a "next leg ready" line
per leg with a low camera count.

**Fail (as reported).** "The last leg on some longer routes seems to be not
avoiding cameras at all and routing straight through them… I have a hard time
believing they're all unavoidable." Also a fail: the log carrying a "next leg has
NO avoidance option" line, which means the avoidance passes ran out of budget on
that leg — a different problem (§7.10) landing in the same place, and worth
reporting with the log attached.

---

### D6 · The camera-reach setting actually moves the route
*Fixed 2026-08-16.*

**Setup.** Plan the same trip twice through country with cameras on it — once at
100%, once at 300% — and compare the fewest-cameras option's **distance**, not
its camera count.

**Pass.** The distance changes. At a wider reach the route detours further to
keep the extra standoff, and the camera count stays low.

**Fail (as reported).** "I don't think brouter is taking the new values into
account because it is routing us past cameras that are avoidable… It is now
[triggering them], but not changing the route to avoid these new ones." The
signature is an identical distance at both settings with a much larger camera
count at the wider one — the app announcing cameras it made no attempt to dodge.
Measured before the fix: 47.1 km / 2 cameras at ×1 and 47.1 km / **20 cameras**
at ×3, the same route both times.

---

### D7 · The map names the places on it
*Added 2026-08-16.*

**Setup.** Zoom in on a town centre, at street level and a couple of levels out.

**Pass.** Shops, restaurants, schools and parks are named, thinning out sensibly
as you zoom out rather than piling on top of each other. Labels sit *under* the
route line, the camera dots and the pins — never over them.

**Fail.** No names at all (the basemap style changed its source id and
`addPlaceLabels` silently did nothing), or a solid block of text over the roads
the route runs on.

---

### D8 · A trip still being planned says so
*Added 2026-08-16.*

**Setup.** Plan a trip long enough to be cut into legs and watch the map while
the later legs land.

**Pass.** A semitransparent dashed line runs from the end of the planned route
to the destination pin, its dashes marching along it, and it shortens as each
leg arrives and disappears when the last one lands.

**Fail.** The dashed line still there after planning has finished or failed —
it is a promise that something is coming, and it must not outlive that being
true.

---

### B6 · No pointless doubling-back at a leg boundary
*Fixed 2026-08-16.*

**Setup.** A trip long enough to be cut into legs, through country where the
camera-free route leaves the direct road — the boundary is chosen on the direct
road, so the disagreement needs somewhere for the two to differ. Watch the map
as the second leg lands.

**Pass.** The two legs meet and carry on. Where the second leg would have
retraced the first, both are shortened so they join at the point they diverge,
and the diagnostic log carries a "trimmed a … m double-back" line.

**Fail (as reported).** The route runs out to a point, turns, and comes back
along a parallel road — miles out and the same miles back, for a boundary
nobody asked to visit.

**Known limit, not a failure.** On a drive already under way the car still
passes through the boundary: the chain handed to the drive monitor keeps its
pins, because pulling a waypoint out from under a moving car is the §6.1
failure. The map shows the shorter line.

---

### D9 · The chooser describes the whole trip, not just the first leg
*Fixed 2026-08-16.*

**Setup.** Plan a trip long enough to be cut into legs and leave the chooser
open while the later legs land.

**Pass.** A "whole trip so far" line under the banner grows as each leg arrives —
time, distance, cameras, leg count — and settles to "Whole trip … every leg
planned" when the last one lands. Selecting a different option updates it.

**Fail (as reported).** The sheet says "about 3600 km" and then only ever
describes the first 270 km, however long you wait.

---

### B7 · The last leg is not the fastest road
*Fixed 2026-08-16.*

**Setup.** A long trip — several legs — ending on a pin in a dense metro. Let
every leg land and look at the last one on the map.

**Pass.** The last leg detours like the others. If the sheet says "Planning ran
out of time", that is the safety net working: the routes shown came from an
earlier round of the search and their camera counts are still true.

**Fail (as reported).** The last leg drawn as an unbroken line of camera markers
straight down the fastest road. Export the log — a "next leg has NO avoidance
option" line confirms it, and the planning breakdown will show a `(widen 2)`
round where the passes read "gave up — out of time".

---

### D10 · A very long trip plans at all
*Fixed 2026-08-16.*

**Setup.** Plan a trip of 3,000 km or more, across the country.

**Pass.** A chooser appears for the first leg. If planning genuinely cannot
finish it says so in those words.

**Fail (as reported).** "Couldn't plan this trip — No route found — the offline
map for this area may be incomplete", listing tiles that are all present. That
message means the map; if you see it with the tiles there, the real cause was the
clock and the message is lying.

---

### C5 · The map follows the drive without fighting you
*Fixed 2026-08-16.*

**Setup.** Start a drive and leave the map alone. Then pan away deliberately and
wait.

**Pass.** The map keeps your marker and the next pin both in frame, tightening as
you approach. When you pan away it stays where you put it, and resumes framing
about twelve seconds after you stop touching it.

**Fail.** The map snapping back while you are still moving it, or never resuming.
The first is the worse one — that is the app overriding you.

---

### D11 · Nothing on a driving screen takes more than a glance
*Added 2026-08-16.*

**Setup.** Look at the result sheet and the driving sheet as if at a set of
lights.

**Pass.** Numbers and short labels. The FSD line is one sentence.

**Fail.** Any paragraph. Reasons and caveats belong in settings or the README.

---

## Keeping this file honest

When something here is confirmed working in a car, say so in the entry with the
date, and move the corresponding item out of "unconfirmed" in CLAUDE.md §7 and
`docs/field-notes.md`. When something fails, add what actually happened to the
field notes — the observation is worth more than the fix, because the fix can be
re-derived from it and the reverse is not true.
