package btools.router;

import btools.util.CheapRuler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A uniform grid over the nogo list, so a link only tests the nogos near it.
 *
 * <p><b>This file is a Shunt addition to vendored BRouter.</b> See CLAUDE.md §7
 * — the rest of {@code btools.*} is upstream and untouched, and this one is not.
 * It exists because {@link RoutingContext#calcDistance} is called for every link
 * the search expands and scanned the whole nogo list each time, which is
 * O(links × nogos). Shunt turns each ALPR camera into a nogo, so a trip into a
 * dense metro carries thousands of them: measured on a real 490 km route, the
 * plain search took 3.5 s and the same search carrying 1,181 nogos took 202 s.
 *
 * <p>It is written to be <b>answer-preserving, not merely close</b>. A nogo can
 * only affect a segment if its circle comes within {@code radius} of it, so the
 * grid is queried over the segment's bounding box grown by the largest radius
 * present; anything excluded would have failed the caller's own radius test and
 * returned without side effects. Candidates are handed back in ascending list
 * order, so the caller visits them in exactly the order it used to.
 */
final class NogoIndex {

  /** The list this index was built for, compared by identity. */
  final List<OsmNodeNamed> source;

  private final int cellSize;
  private final int lonMargin;
  private final int latMargin;
  private final Map<Long, int[]> buckets = new HashMap<>();

  /** Scratch reused between queries; the caller never keeps the array. */
  private int[] hits = new int[64];
  private int hitCount;

  NogoIndex(List<OsmNodeNamed> nogos) {
    source = nogos;

    // Longitude degrees shrink with latitude, so the margin in microdegrees
    // needed to cover a radius in metres is largest at the highest latitude
    // present. Taking that worst case keeps the query conservative everywhere.
    double maxRadius = 1.0;
    int maxAbsLat = 0;
    for (OsmNodeNamed nogo : nogos) {
      if (nogo.radius > maxRadius) maxRadius = nogo.radius;
      int absLat = Math.abs(nogo.ilat - 90000000);
      if (absLat > maxAbsLat) maxAbsLat = absLat;
    }
    double[] scales = CheapRuler.getLonLatToMeterScales(maxAbsLat + 90000000);
    double lonToMeter = Math.max(scales[0], 1e-9);
    double latToMeter = Math.max(scales[1], 1e-9);

    latMargin = (int) Math.ceil(maxRadius / latToMeter) + 1;
    lonMargin = (int) Math.ceil(maxRadius / lonToMeter) + 1;
    // Cells about one margin across: big enough that a query touches only a
    // handful, small enough that each holds few nogos.
    cellSize = Math.max(Math.max(lonMargin, latMargin), 1);

    Map<Long, List<Integer>> building = new HashMap<>();
    for (int i = 0; i < nogos.size(); i++) {
      OsmNodeNamed nogo = nogos.get(i);
      building.computeIfAbsent(key(nogo.ilon, nogo.ilat), k -> new ArrayList<>()).add(i);
    }
    for (Map.Entry<Long, List<Integer>> entry : building.entrySet()) {
      List<Integer> list = entry.getValue();
      int[] packed = new int[list.size()];
      for (int i = 0; i < packed.length; i++) packed[i] = list.get(i);
      Arrays.sort(packed);
      buckets.put(entry.getKey(), packed);
    }
  }

  private long key(int lon, int lat) {
    long cx = Math.floorDiv(lon, cellSize);
    long cy = Math.floorDiv(lat, cellSize);
    return (cx << 32) ^ (cy & 0xffffffffL);
  }

  /**
   * Indices of every nogo that could possibly affect this segment, ascending.
   * The returned array is scratch and is valid only until the next query; read
   * {@link #size()} for how much of it is filled.
   */
  int[] candidates(int lon1, int lat1, int lon2, int lat2) {
    int minLon = Math.min(lon1, lon2) - lonMargin;
    int maxLon = Math.max(lon1, lon2) + lonMargin;
    int minLat = Math.min(lat1, lat2) - latMargin;
    int maxLat = Math.max(lat1, lat2) + latMargin;

    hitCount = 0;
    long cxFrom = Math.floorDiv(minLon, cellSize);
    long cxTo = Math.floorDiv(maxLon, cellSize);
    long cyFrom = Math.floorDiv(minLat, cellSize);
    long cyTo = Math.floorDiv(maxLat, cellSize);
    for (long cx = cxFrom; cx <= cxTo; cx++) {
      for (long cy = cyFrom; cy <= cyTo; cy++) {
        int[] bucket = buckets.get((cx << 32) ^ (cy & 0xffffffffL));
        if (bucket == null) continue;
        if (hitCount + bucket.length > hits.length) {
          hits = Arrays.copyOf(hits, Math.max(hits.length * 2, hitCount + bucket.length));
        }
        System.arraycopy(bucket, 0, hits, hitCount, bucket.length);
        hitCount += bucket.length;
      }
    }
    // Ascending, so the caller sees them in the same order as a full scan.
    Arrays.sort(hits, 0, hitCount);
    return hits;
  }

  int size() {
    return hitCount;
  }
}
