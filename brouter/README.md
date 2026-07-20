# Vendored BRouter routing core

This module is a **verbatim copy of [BRouter](https://github.com/abrensch/brouter)'s
routing modules** (`brouter-util`, `brouter-codec`, `brouter-expressions`,
`brouter-mapaccess`, `brouter-core`) at tag **v1.7.10**, under the package
`btools.*`. BRouter is MIT-licensed (see [LICENSE](LICENSE)); copyright the
BRouter contributors.

## Why vendored

BRouter isn't published to Maven Central and its JitPack build doesn't produce
usable artifacts, so the source is copied in. It's pure Java with **zero
external dependencies** (101 files, ~260 KB compiled), and the official BRouter
Android app drives the exact same `btools.router.RoutingEngine` API, so it runs
unchanged on Android/ART.

Shunt uses it for on-device, offline, camera-aware routing: each ALPR camera
becomes a weighted "nogo" so the router minimises exposure in a single
shortest-path pass. Shunt's wrapper lives in `:solver`
(`app.shunt.solver.brouter`); this module is only the untouched engine.

`src/main/resources/brouter-data/` bundles the stock `car-vario.brf` profile
and BRouter's `lookups.dat` tag dictionary.

## Updating

Re-copy the five modules' `src/main/java/btools` trees from a checked-out
BRouter tag; do not edit the sources here so the diff to upstream stays empty.
