package io.riverdb.platform.riverd;

/** Caller-owned transfer slot for an acquired exclusive instance lock. */
public final class RiverLockResult {
  private RiverLock lock;

  public RiverLock lock() {
    return lock;
  }

  public void set(RiverLock value) {
    lock = value;
  }

  public void reset() {
    lock = null;
  }
}
