package io.riverdb.platform.fault;

/** A registry-owned, bounded crash/failure boundary identity. */
public final class FaultPoint {
  private final int id;
  private final String name;

  FaultPoint(int id, String name) {
    this.id = id;
    this.name = name;
  }

  public int id() {
    return id;
  }

  public String name() {
    return name;
  }

  @Override
  public String toString() {
    return name;
  }
}
