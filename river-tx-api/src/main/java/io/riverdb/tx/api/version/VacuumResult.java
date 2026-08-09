package io.riverdb.tx.api.version;

/** Caller-owned bounded vacuum progress output. */
public final class VacuumResult {
  private int inspected;
  private int reclaimed;
  private boolean moreWork;

  public VacuumResult set(int recordsInspected, int recordsReclaimed, boolean hasMoreWork) {
    inspected = recordsInspected;
    reclaimed = recordsReclaimed;
    moreWork = hasMoreWork;
    return this;
  }

  public int inspected() {
    return inspected;
  }

  public int reclaimed() {
    return reclaimed;
  }

  public boolean hasMoreWork() {
    return moreWork;
  }
}
