package io.riverdb.testkit.io;

import io.riverdb.platform.fault.FaultBoundary;
import io.riverdb.platform.fault.FaultPoint;

/** Named before/after points for every general directory operation. */
public final class DirectoryFaultPoints {
  private final FaultPoint[][] points = new FaultPoint[DirectoryOperation.values().length][2];

  public void set(DirectoryOperation operation, FaultBoundary boundary, FaultPoint point) {
    points[operation.ordinal()][boundary.ordinal()] = point;
  }

  public FaultPoint point(DirectoryOperation operation, FaultBoundary boundary) {
    return points[operation.ordinal()][boundary.ordinal()];
  }
}
