package io.riverdb.bench.prototype;

/** Caller-owned carrier for a fixed version-store record. */
public final class VersionRecord {
  long rowId;
  long beginSequence;
  long endSequence;
  long value;
  long flags;

  public long rowId() {
    return rowId;
  }

  public long beginSequence() {
    return beginSequence;
  }

  public long endSequence() {
    return endSequence;
  }

  public long value() {
    return value;
  }

  public long flags() {
    return flags;
  }
}
