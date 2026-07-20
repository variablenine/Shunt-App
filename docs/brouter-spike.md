# Spike: native on-device camera-aware routing with BRouter

**Question:** can we replace the HERE "greedy exclusion" router with an
on-device engine that plans camera-aware routes offline — and does it produce
good routes? **Answer: yes, decisively.** This documents the prototype and the
proposed integration so the real work starts from evidence, not a hunch.

## What was tested

A pure-JVM harness (no Android, no network at route time) using:

- **Engine:** BRouter's routing core (`btools.router.*`), compiled from
  source at tag `v1.7.10`. The five routing modules (`util`, `codec`,
  `expressions`, `mapaccess`, `core`) compile with a plain JDK — **zero
  external dependencies, 115 classes, ~260 KB** as a jar.
- **Map:** one real offline BRouter segment tile (a 5°×5° cell, **10.8 MB**,
  covering a mid-size town's road network and well beyond).
- **Cameras:** the 9 real DeFlock ALPRs in that town, each modelled as a
  BRouter **weighted nogo** circle (radius 40 m).
- **Profile:** stock `car-vario.brf`.

Each camera becomes `lon,lat,40,<weight>`. A **finite** weight is a penalty
per metre driven inside the circle — not a hard block — so the router trades
detour distance against exposure in a single shortest-path pass.

## Results (real routes, this tile)

| Trip | Baseline (fastest) | Camera-aware (weight sweep) |
|------|--------------------|------------------------------|
| **A** — cross-town, a camera ~200 m from the start | 8.2 km, ~13 min, **1 camera** | w=500/1500 → 13.7 km, **1 camera** (a cheaper-to-pass one); w=4000+ → 20.4 km, **0 cameras** |
| **B** — through a two-camera highway cluster | 2.2 km, ~4 min, **2 cameras** | any weight → 5.9 km, **0 cameras** |
| **C** — long E-W haul across town | 15.4 km, ~15 min, **1 camera** | w=500 → 20.8 km, **1 camera**; w=1500+ → 23.8 km, **0 cameras** |

Route time per query: **17–63 ms** after warm-up (first call ~130–200 ms
including tile load + JIT) on a laptop JVM. On-device will be slower but
city-scale trips route well under a second.

## Findings

1. **It works fully offline.** Given the rd5 tile + profile, no network is
   touched to compute a route. This is the piece HERE could never do.
2. **The nogo weight is a real, smooth policy dial** that maps directly onto
   Shunt's stated policy: high weight ⇒ "camera-free strongly preferred";
   finite weight ⇒ the minimum-exposure fallback picks up an unavoidable
   camera instead of an absurd detour (Trip A, w≤1500). No cliff, no on/off.
3. **The greedy failure mode is gone.** Our current README documents that
   greedy exclusion "can report infeasibility when a clean route exists,
   because excluding camera A may push the route onto camera B, and the search
   never backtracks." BRouter does one shortest-path pass over the whole road
   graph with the penalties baked into edge cost — every query above returned
   a real route. There is nothing to backtrack.
4. **Tiny footprint.** 260 KB of dependency-free Java + a 10.8 MB tile per
   5°×5° region. Trivial to embed; the tile is the only real storage cost.

### One semantic nuance to decide

BRouter minimises *weighted metres inside nogo areas*, i.e. total exposure
distance — not *distinct camera count* the way our current scorer does.
Arguably exposure-distance is the better metric (clipping the edge of one
camera's range is less exposure than driving 100 m under another), but it's a
policy choice. We can keep the distinct-camera count for the result card and
drive-monitor either way; it's computed from the returned polyline.

## Proposed integration

Keep the existing seam. `AppContainer` already resolves routing behind a
`RoutePlanner` port; today it's `RouteSolver` (HERE + greedy). Add a
`BrouterRoutePlanner` in `:solver` (or a new `:routing` module) implementing
the same port, so swapping it is one line — mirroring the vehicle-client seam.

- **HERE stays for search/geocoding/autosuggest** (BRouter doesn't do that).
  Only the *routing* path changes.
- **Camera set → weighted nogos.** Reuse `DeFlockCameraSource` for the route's
  bbox; convert each camera to a nogo circle. Start weight ≈ 1500 (tunable),
  expose radius as we do today (default 40 m).
- **Tiles** like camera tiles already work: lazy per-region download from
  `brouter.de/brouter/segments4/`, disk-cached, with an offline fallback.
  (Open decision below: bundle the user's home region vs. download-on-demand.)
- **Profile** `car-vario.brf` + `lookups.dat` bundled as app assets (~45 KB).
- The returned polyline feeds the existing waypoint extraction, result card,
  and drive monitor unchanged.

### Open decisions (need a call before building)

1. **Tiles:** download-on-demand + cache (like the camera tiles), bundle the
   home region in the APK for guaranteed offline, or hybrid.
2. **Routing engine:** fully replace the HERE routing path with BRouter, or
   keep HERE as a fallback when a tile is missing.
3. **Exposure metric:** BRouter's exposure-distance (default) vs. reproducing
   the distinct-camera-count objective.

## Reproducing the spike

Not committed (it pulls GPL BRouter source and a 10.8 MB tile). To rebuild:

```
git clone --depth 1 -b v1.7.10 https://github.com/abrensch/brouter
javac -d out $(find brouter/brouter-{util,codec,expressions,mapaccess,core}/src/main/java -name '*.java')
# put the region's <tile>.rd5 in ./segments, car-vario.brf + lookups.dat in ./profiles
java -cp out Proto ./segments ./profiles <fromLat> <fromLon> <toLat> <toLon>
```

The harness (`Proto.java`) sets each camera as `lon,lat,40,weight`, calls
`RoutingEngine.doRun`, and counts cameras within 40 m of the returned track.
