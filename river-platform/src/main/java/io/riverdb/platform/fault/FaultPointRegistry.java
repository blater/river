package io.riverdb.platform.fault;

import io.riverdb.base.error.StatusCode;

/**
 * Fixed-capacity registry for stable fault-point names. Registration is a bootstrap operation;
 * fault checks use the returned identity and do not perform a name lookup.
 */
public final class FaultPointRegistry {
  private final FaultPoint[] points;
  private int size;

  public FaultPointRegistry(int capacity) {
    points = new FaultPoint[capacity];
  }

  /** Validates a configuration/authored name once at the registration boundary. */
  public StatusCode register(String name, FaultPointSlot result) {
    if (name == null || name.isBlank() || name.length() > 128) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < size; index++) {
      FaultPoint point = points[index];
      if (point.name().equals(name)) {
        result.set(point);
        return StatusCode.OK;
      }
    }
    if (size == points.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    FaultPoint point = new FaultPoint(size, name);
    points[size++] = point;
    result.set(point);
    return StatusCode.OK;
  }

  public int size() {
    return size;
  }

  public int capacity() {
    return points.length;
  }
}
