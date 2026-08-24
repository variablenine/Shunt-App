/**
 * Information on matched way point
 *
 * @author ab
 */
package btools.mapaccess;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

public final class MatchedWaypoint {

  public static final byte WAYPOINT_TYPE_SHAPING = 1;  // route next to this point
  public static final byte WAYPOINT_TYPE_MEETING = 2;  // visit this point
  public static final byte WAYPOINT_TYPE_DIRECT  = 3;  // from this point go direct to next = beeline routing

  public OsmNode node1;
  public OsmNode node2;
  public OsmNode crosspoint;
  public OsmNode waypoint;
  public OsmNode correctedpoint;
  public String name;  // waypoint name used in error messages
  public double radius;  // distance in meter between waypoint and crosspoint
  public byte wpttype = WAYPOINT_TYPE_SHAPING;
  public int indexInTrack = 0;
  public double directionToNext = -1;
  public double directionDiff = 361;

  public List<MatchedWaypoint> wayNearest = new ArrayList<>();
  public boolean hasUpdate;

  // SHUNT CHANGE begin -- see CLAUDE.md "Ask the road graph what is near a pin".
  //
  // Upstream records a way only when it beats the best match so far, which is
  // exactly right for "snap this point to a road" and useless for "what else is
  // near this point": once the nearest way is found at distance zero, nothing
  // else is ever recorded. Shunt needs the second question — a waypoint handed
  // to a car is snapped by the *car's* map, and a second road a few tens of
  // metres away is one it may pick instead, which is how a pin on a divided
  // highway ends up on the far carriageway.
  //
  // Off unless a caller sets a radius, so ordinary routing is untouched.

  /** Collect the distance to every way within this many metres. 0 disables. */
  public double nearbyCollectRadius = 0.;

  /** Distances in metres to the ways found within that radius, nearest first. */
  public final List<Double> nearbyRadii = new ArrayList<>();

  /** How many distinct distances are worth keeping; a pin needs a handful. */
  private static final int MAX_NEARBY = 32;

  /** Distances closer together than this are the same road seen twice. */
  private static final double NEARBY_SAME_METERS = 0.5;

  public void recordNearby(double radius) {
    if (nearbyRadii.size() >= MAX_NEARBY) return;
    for (Double seen : nearbyRadii) {
      if (Math.abs(seen - radius) < NEARBY_SAME_METERS) return;
    }
    nearbyRadii.add(radius);
  }
  // SHUNT CHANGE end

  public void writeToStream(DataOutput dos) throws IOException {
    dos.writeInt(node1.ilat);
    dos.writeInt(node1.ilon);
    dos.writeInt(node2.ilat);
    dos.writeInt(node2.ilon);
    dos.writeInt(crosspoint.ilat);
    dos.writeInt(crosspoint.ilon);
    dos.writeInt(waypoint.ilat);
    dos.writeInt(waypoint.ilon);
    dos.writeDouble(radius);
    dos.writeByte(wpttype);
    dos.writeShort(name.length());
    dos.writeBytes(name);
  }

  public static MatchedWaypoint readFromStream(DataInput dis) throws IOException {
    MatchedWaypoint mwp = new MatchedWaypoint();
    mwp.node1 = new OsmNode();
    mwp.node2 = new OsmNode();
    mwp.crosspoint = new OsmNode();
    mwp.waypoint = new OsmNode();

    mwp.node1.ilat = dis.readInt();
    mwp.node1.ilon = dis.readInt();
    mwp.node2.ilat = dis.readInt();
    mwp.node2.ilon = dis.readInt();
    mwp.crosspoint.ilat = dis.readInt();
    mwp.crosspoint.ilon = dis.readInt();
    mwp.waypoint.ilat = dis.readInt();
    mwp.waypoint.ilon = dis.readInt();
    mwp.radius = dis.readDouble();
    mwp.wpttype = dis.readByte();
    int len = dis.readShort();
    byte[] bytes = new byte[len];
    dis.readFully(bytes);
    mwp.name = new String(bytes);
    return mwp;
  }

}
