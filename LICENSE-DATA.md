# Data License

The **code** in this repository is licensed under the GNU Affero General
Public License v3.0 — see [LICENSE](LICENSE).

The **camera location data** this application fetches, caches, and bundles is
licensed separately, under the **Open Database License (ODbL) v1.0**.

## Provenance

ALPR (automated license plate reader) locations are sourced from the
[DeFlock](https://deflock.me) community project, which derives its dataset
from [OpenStreetMap](https://www.openstreetmap.org). OpenStreetMap data is
© OpenStreetMap contributors and is made available under the
[ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/).

## What the ODbL requires

- **Attribution.** Any public use of the data, or of a database derived from
  it, must credit © OpenStreetMap contributors (and this project credits
  DeFlock as the intermediate source).
- **Share-alike.** If you publicly use an adapted version of this database
  (including the bundled camera snapshot shipped in the APK, or any database
  you build from it), you must offer that adapted database under the ODbL 1.0
  as well.
- **Keep open.** You may not use technological measures that restrict reuse
  of the data unless you also provide an unrestricted copy.

## Practical notes for this repo

- The bundled offline camera snapshot (added in M1 under `:solver` resources)
  is a *derivative database* of OSM data and is covered by the ODbL, **not**
  by the AGPL.
- If you redistribute a modified snapshot, publish it under ODbL 1.0 with
  attribution as above.
- The AGPL continues to govern all source code, including the code that
  parses and indexes the data.
