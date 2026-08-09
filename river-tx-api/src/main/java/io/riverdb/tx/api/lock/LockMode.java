package io.riverdb.tx.api.lock;

/** Logical protection strength ordered from read sharing to exclusive modification. */
public enum LockMode {
  SHARED,
  UPDATE,
  EXCLUSIVE;

  public boolean conflictsWith(LockMode other) {
    if (this == SHARED) {
      return other == EXCLUSIVE;
    }
    if (this == UPDATE) {
      return other != SHARED;
    }
    return true;
  }
}
