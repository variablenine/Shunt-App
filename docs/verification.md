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

### C6 · The screen stays on, and the drive survives it going off
*Mitigated 2026-08-16; the underlying fault is not understood.*

**Setup.** Start a drive and leave the phone alone.

**Pass.** The screen stays lit while Shunt is in front. Then background the app
deliberately, let the screen sleep, and drive past a camera: the spoken warning
still comes and the notification is still live.

**Fail (as reported).** "Screen turns off and the app will silently fail." The
second half of the setup is the one that matters — the flag stops the screen
sleeping, it does not explain why anything broke when it did. If the second half
fails, say so with a log export: that is the actual bug.

---

### B8 · A continental trip plans
*Fixed 2026-08-16.*

**Setup.** Plan a trip of 2,500 km or more — coast to coast.

**Pass.** A chooser appears for the first leg within a minute or so, and later
legs land behind it.

**Fail (as reported).** "Couldn't plan this trip", either blaming the offline map
or saying planning ran out of time. Both mean the same thing here.

---

### B9 · Every leg keeps its pins
*Fixed 2026-08-16.*

**Setup.** Plan a trip long enough to be cut into legs and let them all land.

**Pass.** White pins appear along **every** leg, not just the first.

**Fail (as reported).** Leg one pinned, everything after it bare. Note this was a
map bug rather than a routing one — the car was still being steered through the
later pins — so a drive would have behaved correctly while the screen looked
wrong. Worth knowing, because "the map shows no pins" and "the car has no pins"
are very different problems.

---

### B10 · The line survives switching between the options
*Fixed 2026-08-19.*

**Setup.** Plan a trip long enough to be cut into legs — over
`LegSplitter.MAX_LEG_METERS` — and wait for at least two later legs to land.
Then tap between Fastest and Fewest cameras on the chooser.

**Pass.** The route stays drawn the whole time. The later legs are replaced one
by one as the new ones arrive; the dashed pending line to the destination keeps
running throughout, and keeps running after a leg lands.

**Fail (as reported).** The whole line back to the first boundary disappeared on
the tap, and the pending line disappeared again a while later — the delay being
how long the abandoned pass took to notice it had been cancelled. See F-41.

**What to watch for that a test cannot see.** The carried legs are the *previous*
trade-off's until they are replaced, so for a few seconds the line beyond the
first boundary is the old choice. That is deliberate; a blank map was worse. If
a leg ever fails to plan after a switch, everything from that point is dropped
rather than left standing.

---

### B11 · The chooser opens on camera-free, and later legs follow it
*Fixed 2026-08-19.*

**Setup.** Plan any trip that returns more than one option.

**Pass.** The chooser is already sitting on **Fewest cameras**. On a trip long
enough to be split, the legs that land afterwards are camera-avoiding without
anybody having tapped anything.

**Fail (as reported).** It opened on Fastest, and — because the selection is what
later legs are planned to — every leg after the first was planned as the plain
fastest road. Reported twice as "the last leg still is taking the fastest route".

**Note.** This changes what Go does by default. Confirm on a real trip that the
route pushed to the car is the camera-free one and not the fastest, since nobody
has to touch the chooser for it to be chosen now.

---

### C7 · The pending line marches instead of twitching
*Fixed 2026-08-19; the cycle itself is held by `PendingDashesTest`.*

**Setup.** Plan a long trip and watch the dashed line running from the end of
what is planned to the destination pin.

**Pass.** The dashes crawl steadily toward the destination. Nothing resets when
a leg lands — the line gets shorter, the march does not restart — and tapping Go
keeps the line following the road onward rather than snapping to a ruled
diagonal.

**Fail (as reported).** "Jumpy and weird": the pattern snapped backwards several
times a second, and again whenever a leg landed.

---

### B12 · No leg is lost between the chooser and the drive
*Fixed 2026-08-20. The most consequential fix of that day and the only one
nobody had reported.*

**Setup.** Plan a trip long enough to take three or more legs. **Sit on the
chooser for a minute or two** — long enough for at least two later legs to land
— then tap Go.

**Pass.** The drawn route and the car's chain contain every leg, in order. The
camera count on the driving sheet accounts for all of them.

**Fail (as it was).** The channel carrying legs to the drive monitor held one
and dropped the older on each send, so the drive jumped from the end of leg 1 to
whichever leg happened to be last — skipping the road between it, its pins and
its cameras. It only bit if legs landed faster than the monitor existed, so it
depended on how long the driver read the screen.

**How to provoke it deliberately if it ever comes back:** the shorter you leave
the chooser open, the less likely it is. Wait, don't hurry.

---

### B13 · A later leg sets off the way the car arrived
*Fixed 2026-08-20.*

**Setup.** Plan a long trip through country with cameras near the direct road,
and look at each leg boundary on the map.

**Pass.** The route carries on through the boundary. No spur, no loop, no
out-and-back.

**Fail (as reported).** "A weird detour where the navigation goes away from the
route, towards a camera, circles around it out of range, and goes back to the
route." Later legs were planned with no heading, so nothing cost the router
anything for leaving the boundary back down the road just driven.

**What this does not fix.** The heading biases; it does not forbid. A boundary
that is genuinely in the wrong place still produces a bend, and the trim and the
seam re-plan are still what handle that. If a spur survives this, the next step
is the overlap handover in the research brief (F-42), not a wider repair window.

---

### C8 · The pending line follows a road, from where the route actually ends
*Fixed 2026-08-20.*

**Setup.** Plan a trip over 1,500 km — long enough that the spine is probed
rather than routed whole — and watch the dashed line as legs land.

**Pass.** It starts at the tip of the drawn route and follows roads onward.
Each leg that lands shortens it and the remainder still follows roads.

**Fail (as reported).** It ran straight across country at an angle to the route,
leaving the route's tip sideways. Both come from the same cause: only the first
leg's `directAhead` reached the map, and on a trip that long most of the first
leg's copy is a straight estimate past where the spine probe stopped.

**Note.** Before any later leg lands the line is still mostly that estimate, and
that is honest — nothing better is known yet. It is dashed and semitransparent
for exactly this reason.

---

### D12 · Fewest cameras is the first card on the sheet
*Fixed 2026-08-20.*

**Pass.** Fewest cameras sits at the top, above Fastest, and is the one already
selected.

**Fail (as reported).** Fastest was on top with the selected camera-free option
underneath it, which reads as a recommendation for the road that avoids nothing.

---

### B14 · No spur, no C, no loop at a later leg boundary
*Landed 2026-08-20. Never driven; measured only in tests.*

**Setup.** Plan a trip long enough for four or more legs and look at every
boundary *after the first* on the map.

**Pass.** The route runs through each of them with no out-and-back, no C around
a more direct line, and no loop past a camera and back.

**Fail (as reported).** A spur running out to a camera, circling it out of
range, and returning — screenshotted around 1,000 km into a Denver trip.

**What is exempt, and why.** The **first** boundary — between the leg the
chooser showed and the one after it — still meets at a point rather than handing
over, so it keeps the trim and the seam re-plan. Shortening the lead leg would
change a route the car may already be driving. If a spur appears, note *which*
boundary: at the first one it is expected and handled by the old machinery; at
any later one the handover has failed and that is new.

**Also worth checking:** the diagnostic log should say "handing 15 km of this leg
to the next one" once per later boundary, and should no longer say "re-planned
the leg boundary" for those.

---

### B15 · A charging stop does not cost the trip its split
*Fixed 2026-08-20.*

**Setup.** Plan a trip of 900 km or more and add a Supercharger about 100 km in
(short of `MIN_LEG_METERS`). Then repeat with one about 150 km in (inside the
window).

**Pass.** Both split. The near stop travels in the first leg — it appears on the
route the chooser shows. The stop inside the window *is* the boundary, so the
first leg ends there.

**Fail (as it was).** Either case planned the whole trip in one go, which on a
trip that long is the case §7.10 measures at the fastest road and 43 cameras
after 75 s.

---

### B16 · No stop is ever deleted from a trip
*Fixed 2026-08-20.*

**Setup.** A long trip whose route bends back on itself — a coastline, a
mountain pass, anything where the road returns toward the origin — with a stop on
the return limb.

**Pass.** The stop is on the route, in exactly one leg.

**Fail (as it was).** Stops were ordered by straight-line distance from the
origin, and one filed as "before the boundary" was dropped from the first leg
and from the remainder both. Silent: nothing said the stop had gone.

---

### C9 · The pending line marches smoothly, with no dots left behind
*Fixed 2026-08-20.*

**Pass.** The dashes crawl steadily. Nothing stationary sits between them.

**Fail (as reported).** "Still feeling like it has a low frame rate", and dots
that stayed put while the dashes moved past. Eleven frames a second, and round
line caps drawing every zero-length dash in the cycle as a circle.

---

### B17 · A leg's camera count describes the line it ends up with
*Fixed 2026-08-20.*

**Setup.** Plan a long trip and watch the whole-trip camera total on the chooser
as later legs land. Then look at the map for orange camera dots sitting *on* a
leg that reports none.

**Pass.** Every camera drawn on the route is counted. A leg that says
"camera-free" has no camera dot on its line.

**Fail (as reported).** "It labeled a route with a camera as camera free again
with an avoidable camera." A leg is labelled when it is planned, and the trim,
the handover and the seam re-plan all move the line afterwards — the seam by
*adding* road neither leg drove, which is the direction that hides a camera.

**In the log.** A leg whose recount goes up says so: "reshaping this leg put it
past N more camera(s)". If the camera lookup fails it says that instead, and the
count shown is the old one.

---

### D13 · A camera on the map is always accounted for on the sheet
*Fixed 2026-08-20.*

**Setup.** Plan a long trip whose first leg is camera-free and let a few later
legs land, so red camera dots appear further along the route.

**Pass.** The verdict under the option cards reads "This leg passes no cameras"
*and* "N cameras later in the trip". Nothing red on the map is unexplained.

**Fail (as reported).** A green "camera-free" verdict above a route with four
red dots drawn on it. Both statements were true — the verdict was about the leg,
the dots were the whole trip — and the pair read as the app lying.

---

### E4 · The diagnostic log saves to a file, and shares nothing
*Changed 2026-08-20, at the maintainer's request.*

**Setup.** Settings → Report a problem → Save log.

**Pass.** The system document picker opens with a dated filename. Choosing a
location writes the file there and a toast confirms it. Backing out of the picker
does nothing and says nothing — that is not a failure. No share sheet appears at
any point, and no app is offered the file.

**Also check:** the file's own first lines say whether coordinates are included.
That note used to live in the covering email and had to move into the file, or
it would be separated from the log the moment anyone forwarded it.

---

### B18 · A camera-free first leg does not plan the rest of the trip fastest
*Fixed 2026-08-20. The most consequential fix of that day.*

**Setup.** Find a trip whose first leg is clean enough that **only one option**
appears on the chooser — one card, labelled "Fastest", with a green
"leg is camera-free". Let every later leg land and read the whole-trip camera
count.

**Pass.** The later legs avoid cameras. The diagnostic log shows each one
planned with avoidance, and a leg reporting dozens of cameras should be rare and
explained by a "NO avoidance option" line.

**Fail (as it was).** The single card is labelled `FASTEST` because that is the
pass that ran first, and that label was passed on as the whole trip's trade-off —
so every later leg was planned as the plain fastest road. Measured from a real
log: legs of 0, 1 and **62** cameras, against the same phone's other trip that
same minute planning seven legs at 0.

**Why it hides.** The lead leg genuinely is camera-free, the sheet genuinely says
so, and nothing on screen names the trade-off the later legs are being planned
to. Only the log shows it.

---

### D14 · A leg that fails to plan says why
*Fixed 2026-08-20.*

**Pass.** The log line reads "next leg failed to plan: …" with the reason — a
routing error, N map tiles missing, no options came back, or the planner threw.

**Fail (as it was).** "next leg failed to plan", full stop. Three different
problems wanting three different responses, and no way to tell them apart.

---

### D15 · A leg that takes cameras says what it was offered
*Added 2026-08-20 — a diagnostic, not a fix.*

**Setup.** Reproduce a leg that passes a camera, then export the log **with
locations turned on**.

**What to read.** Each later leg logs `leg options: FASTEST 216km/1cam/1@ends,
…` before it chooses. That answers the question three rounds of screenshots
could not:

- More than one option listed → an avoidance route existed and something chose
  past it. That is a bug in the choosing.
- One option, `N@ends` equal to its camera count → the cameras watch the start or
  the destination. Arriving is what triggers them; no route avoids that.
- One option, `hard-block-failed`, `0@ends` → avoidance was attempted and found
  nothing. Either genuinely unavoidable, or the pass ran out of time.

**Also.** Every handover line now carries the boundary's coordinates, so an
artifact on the map can be checked against them rather than guessed at.

---

### A9 · Waypoints advance one at a time, near the pin
*Fixed 2026-08-20. Found on a real drive; needs another one to confirm.*

**Setup.** Drive a route through an area with cameras — where pins are placed
250 m apart — at 45 mph or more.

**Pass.** The "sending waypoint N of M" line steps one at a time, and each one
fires as the car approaches that pin rather than several pins back. A pin just
past a turn is still the target when the car reaches the turn.

**Fail (as reported).** "The waypoints are REALLY sensitive and going way too
early." Spacing tightens on camera density and the lead grows with speed, and
nothing makes a dense corridor a slow one — so a fast road through one got 250 m
pins and a 450 m lead, and the monitor re-aimed two and three ahead at once.

**Watch for the opposite too.** The lead is now capped at half the gap, which
means less warning before each pin. If the car starts *braking* for waypoints it
is not stopping at, that cap is too tight and the fraction is the dial.

---

### C10 · The pending line never draws a road that was not routed
*Fixed 2026-08-20.*

**Setup.** Plan a trip over 1,000 km and look at the dashed line past the end of
the planned route.

**Pass.** It follows roads and then stops. It does not reach the destination pin
on a long trip, and that is correct — the pin marks the destination.

**Fail (as reported).** A ruled diagonal from the end of the route to the
destination, across three states and over Lake Erie.

---

### B19 · Shunt refuses to plan against camera data with a hole in it
*Fixed 2026-08-20.*

**Setup.** Plan a trip into an area whose camera tiles cannot be loaded — turn
the network off with a cold cache, on a region the bundled snapshot does not
carry.

**Pass.** The plan fails and says "Couldn't load camera data for this area". The
log names how many tiles were unloadable.

**Fail (as it was).** A tile nothing could supply returned an empty list, which
is the same answer as a tile with genuinely no cameras. The route came back
camera-free having never been asked to avoid anything.

**Also worth reading in the log.** Whenever a camera answer is empty, or comes
from anywhere but the network, the log records the count, the size of the area
and where the data came from: `cameras over a 250x180 km area: 1 (bundled)`. On a
leg into a metro that reads as obviously wrong; before, nothing said it at all.

---

### A10 · The chain does not flush when the route comes back near itself
*Fixed 2026-08-20. Found on a real drive.*

**Setup.** Drive a route that loops — out and back on parallel roads, a
switchback, anything that brings the line within a few hundred metres of itself
a kilometre or two later. A mid-drive re-plan often produces one.

**Pass.** Waypoints advance one at a time as the car reaches each.

**Fail (as reported).** "The first waypoint triggered way too soon and the rest
of them all got sent to my car at once." The off-route check falls back to a
full scan of the line, and its answer was being used for progress as well — so
the car read as being on the *return* leg, every pin behind that point measured
as zero metres away, and the chain unravelled one pin per fix.

---

### D16 · The last leg into a city, planned on its own
*An experiment, not a fix — 2026-08-21. See F-50.*

**The question.** A trip to Washington ends with a 221 km leg that takes one
camera and comes back with no avoidance option, while every other leg of the
same trip avoids cleanly. Is that the leg boundary constraining the approach, or
is the camera genuinely unavoidable?

**Setup.** Plan the last leg *on its own* — origin at the handover point the log
names, destination the same. Short enough that it will not be split.

**Read it as:** a camera-free route means the boundary is the cause, and leg
splitting is costing the approach. The same camera means the leg's own search is
doing all it can, and the remaining question is whether the nogo set and the
labelling set agree.

**In the log either way:** `leg passes (N cameras to avoid): fastest 0.2s,
blocked 0.4s, balanced 0.5s` names what each pass did, and `N cameras to avoid`
is the count after the corridor filter — not the count fetched for the area.

---

### A11 · The driving card swipes down, and the map follows it

*Never seen on a drive — 2026-08-22. See F-52.*

**Provoke it.** Start a drive. Drag the handle at the top of the driving card
downward.

**Expect:** the card shrinks to a single row — destination, what Shunt is doing,
the camera count — and the map keeps the driver's dot and the next pin framed in
the strip *above* the card, not behind it. Dragging up restores the full card and
the frame moves back up with it. Tapping the handle does the same as dragging.

**The thing that will look right and be wrong:** the frame moving only at the
next follow tick. It is keyed on the card's height, so it should re-frame within
a moment of the swipe; up to three seconds of the old framing means the inset is
not reaching the map.

**Also check:** with the card fully open, the driver and the pin are still both
on screen somewhere. The inset is clamped for exactly this — a frame squeezed
into what an open card leaves is worse than one partly behind it.

---

### A12 · No waypoint sits on a junction or beside a parallel road

*Never seen on a drive — 2026-08-22. See F-53.*

**Provoke it.** Plan a route through a town with junctions close together —
anywhere the route turns twice within a couple of hundred metres — and look at
the pins drawn on the map before setting off.

**Expect:** every pin sits mid-block, clear of the junctions either side of it.
Where two turns are close together, expect **one** pin, past the second — not
one past each, and not one sitting in the second junction.

**On the drive:** the car should not slow or move toward the kerb as it passes a
pin. Pulling into a driveway or a side street at a waypoint is this bug.

**The parallel-road half.** Watch the car's own screen where the route runs
beside a frontage road, a service road, or the opposite carriageway. It should
name a point on our road. Navigating the parallel road is the bug, and it is the
half that is only *partly* fixed: Shunt can see the fastest route and its own
line, so it will refuse a pin beside those, but a frontage road neither route
uses is invisible to it. If this recurs, note whether the parallel road carries
either route — that distinguishes a gap in the check from a failure of it.

**What would look like a regression and is not:** fewer pins through a town.
Dropping the first of a close pair is deliberate, and the second holds both.

---

### A13 · A waypoint that fails to send is sent again, from where you are

*Never seen on a drive — 2026-08-22. See F-54.*

**Provoke it.** Drive a pinned route into a dead spot, or turn the phone's data
off for a minute while a pin is due to advance.

**Expect:** the urgent "Route update failed" alert **once**, not once every ten
seconds. The driving card shows "Can't reach the car — retrying waypoint N".
When signal returns, "Waypoint sent. Back in step." and the card goes back to
watching.

**The thing that matters most.** If you covered ground during the outage —
passed one or more pins — the waypoint the car ends up aiming at must be the one
**ahead** of you, not the one that failed. Check the car's own screen names a
point you have not yet reached. Aiming the car at a pin behind you is the
failure this was written to prevent, and under FSD it is a real one.

**Also check:** after standing down (§6.1), a pending retry stops. Nothing
should reach the car once Shunt has handed it back.

---

### B20 · Every pin the car is sent to is on the line you can see

*Never seen on a drive — 2026-08-22. See F-55.*

**Provoke it.** Drive a trip long enough to be split into legs, and watch as each
later leg lands. The lead leg's line can shorten when the next one is planned —
that is the double-back trim doing its job.

**Expect:** the point the car's own screen names is always one of the white pins
on Shunt's map. If the line visibly shortens, any pin that was on the removed
stretch disappears with it, and if the car had been aiming at one, it is re-aimed
within a fix or two.

**The failure to watch for** is a car navigating somewhere that is not on the
drawn route at all — especially out and back, which is what a spur looks like
from the driver's seat. Note whether the line on the phone had just changed.

**And the tell from the other side:** if you take over and drive the sensible
way, the car should not then navigate to a pin *behind* you before picking up the
right one. A pin off the driven road is one the monitor cannot advance past on
its own, so that symptom means a stale pin is still in the chain.

**Also check** an ordinary leg landing does *not* command the car. Appending is
supposed to be invisible to it; only a revision that removes the pin being aimed
at should produce a push.

---

### A14 · A passed waypoint is let go of without restarting the app

*Never seen on a drive — 2026-08-22. See F-56.*

**Provoke it.** Mid-drive, take over and deliberately drive away from the next
waypoint — off the planned route, in a direction that takes you further from it.

**Expect:** within about fifteen seconds the monitor gives up on that pin and
moves to the next. The driving card's "waypoint N of M" advances. You should
never have to cancel and restart navigation to unstick it.

**Must not happen:** the pin being abandoned while you are still approaching it,
including round a bend that swings away first. If waypoints start advancing early
on ordinary corners, this guard is firing when it should not.

### A15 · No charging probe near a junction

*Never seen on a drive — 2026-08-22. See F-56.*

**Provoke it.** Drive a long trip with the vehicle connected and watch the
driving card for "Asking your car about charging".

**Expect:** it appears only on open road — at least 2 km before the next turn on
the route and 400 m past the last one. Never in the approach to a junction.

**The failure it prevents:** the car briefly holds the final destination during a
re-assert, and if there is a junction in that window FSD may take it.

### D17 · The car goes exactly where it was sent

*Never seen on a drive — 2026-08-22. See F-56.*

**Provoke it.** Complete a drive to a destination with a precise location.

**Expect:** the car's own navigation ends at the point Shunt showed, not nearby,
and it never stops to ask which of several places was meant.

**If it still asks**, note whether the car is on the `share` fallback (the result
sheet says the car only accepted the destination). The read-back experiment in
F-56 is what distinguishes a geocoding car from a formatting fault.

### C11 · The waypoint triggers are drawn where they actually fire

*Never seen on a drive — 2026-08-22. See F-56.*

**Provoke it.** Drive a pinned route and watch the hollow rings on the line ahead.

**Expect:** each ring sits short of its waypoint, and slides *further* back as
speed rises and closer as it falls. When the car reaches a ring, the card should
say it is sending that waypoint within a second or two.

**Read it as:** a ring the car passes with no advance means the turn-commit gate
is holding it — which is correct behaviour, and the gate's own point is drawn at
the pin in that case. A ring far in front of its pin on a slow road means the
lead is too generous for that stretch, which is the calibration this was added to
support.

---

### B21 · A split trip's first leg stops at its boundary

*Never seen on a drive — 2026-08-24. See F-57.*

**Provoke it.** Plan a trip long enough to split, tap Go, and watch what the car
is aimed at as you approach the end of the first leg.

**Expect:** the car is walked from pin to pin and then to the **boundary** — the
point the drawn line ends at before the next leg lands. It must never be aimed
at the trip's final destination while there are legs still to come.

**The tell that this is wrong:** the car's own screen naming your final
destination early in the trip, or naming it and then switching back to somewhere
much closer. The second is the extension appending after a destination left at
the end of the chain.

### B22 · A charging detour keeps the stops you asked for

*Never seen on a drive — 2026-08-24. See F-57.*

**Provoke it.** Add a stop of your own to a long trip, then let the car insert a
Supercharger (or use a trip long enough that it will).

**Expect:** a stop **before** the charger is visited on the way to it. A stop
**beyond** the charger is still in the route after charging — check the map shows
the line going through it once "back on the way" is announced.

**The failure:** the stop simply not being in the route any more, with nothing
said about it.

### A16 · An unroutable charging leg is left to the car

*Never seen on a drive — 2026-08-24. See F-57.*

**Provoke it.** Hard to force deliberately; watch for the alert "no camera
avoidance to the charger".

**Expect:** after that alert, Shunt stops steering toward its own route and lets
the car drive to the charger. It must not push a waypoint that pulls the car off
the charging detour — the car inserted that stop because it needs the charge.

**Also expect:** the next probe tries the leg again, and usually succeeds; the
"charging first at X" banner appears when it does.

---

### B23 · A waypoint on a divided highway stays on your side of it

*Never seen on a drive — 2026-08-24. See F-58.*

**Provoke it.** Plan a route along a divided highway or past a frontage road and
drive it, watching the point the car's own navigation names.

**Expect:** the car aims at a point on the carriageway you are driving, and never
plans a route that crosses over and comes back. The reported failure is
unmistakable — the car wants to "go back around".

**Also expect** pins to still exist along the motorway. If a long divided stretch
comes back with no waypoints at all, the best-available rule has regressed into
pass-or-fail, and the car is free to take an exit.

**Worth timing.** This adds one tile-loading query per option. If planning a long
trip is noticeably slower than the build before it, say so — the cost has never
been measured on a real device.

---

### A17 · A motorway keeps its waypoints

*Never seen on a drive — 2026-08-24. See F-59.*

**Provoke it.** Plan a trip that runs a long way on a divided highway, and look
at the pins drawn along it before setting off.

**Expect:** pins along the whole motorway stretch, including through
interchanges. A long divided run with no pins at all is the regression this
entry exists for — it means the road-graph query has gone back to vetoing
placements instead of improving them.

**On the drive:** the car should stay on the highway between pins and not take
an exit nobody planned.

### C12 · The trigger mark is where it fires

*Never seen on a drive — 2026-08-24. See F-59.*

**Provoke it.** Drive a pinned route at varying speeds and watch a ring ahead.

**Expect:** the ring **does not move** as you speed up or slow down. Reaching it
is what fires the advance; the card should say it is sending that waypoint
within a second or two of crossing the ring.

**Must not happen:** the advance firing well before the ring. That was the
reported symptom and it means the lead has gone back to tracking the
speedometer.

**A ring you pass with nothing happening** is still legitimate — the turn-commit
gate holds the advance until the bend before the pin is behind you — but it
should be rare on a straight road.

### D18 · The car names the destination, not a coordinate

*Never verified on a vehicle — 2026-08-24. See F-59.*

**Provoke it.** Drive the last leg of a trip to a named place and watch the car's
own screen once the final pin is passed.

**Expect:** the car shows the destination's name. Not a bare coordinate, and not
a different place near it.

**Only tells you something on a car using the `share` fallback** — the result
sheet says so ("your car only accepted the destination"). A car taking GPS
waypoints was always getting the exact coordinate.

---

### D19 · A Supercharger destination preconditions

*Never verified on a vehicle — 2026-08-24. See F-60.*

**Provoke it.** Navigate to a Supercharger as the trip's destination, on a car
that only accepts one destination (the result sheet says so).

**Expect:** roughly twenty minutes out — about 30 km — the app announces
"charger sent, your car can precondition now", the car's own screen switches to
the Supercharger, and the shaping pins stop. The car should begin preconditioning
some minutes later.

**What to check if it still does not precondition:** whether the car's screen
names the Supercharger or shows a plain coordinate. If it names it and still does
not warm the pack, the coordinate is not being matched to Tesla's charger
database, and the answer is a different command rather than a different string —
see F-60.

**Also expect:** camera warnings carry on for that last stretch. Only the
steering stops.

### A18 · An ordinary destination is not given up early

*Never seen on a drive — 2026-08-24. See F-60.*

**Provoke it.** Drive to a normal destination and watch when the pins stop.

**Expect:** shaping pins right up to about 1.5 km from the end, then "destination
sent". A trip that stops steering tens of kilometres out has taken the charging
window by mistake — check whether the destination was matched to a charging site
it happens to sit near.

---

## Keeping this file honest

When something here is confirmed working in a car, say so in the entry with the
date, and move the corresponding item out of "unconfirmed" in CLAUDE.md §7 and
`docs/field-notes.md`. When something fails, add what actually happened to the
field notes — the observation is worth more than the fix, because the fix can be
re-derived from it and the reverse is not true.
