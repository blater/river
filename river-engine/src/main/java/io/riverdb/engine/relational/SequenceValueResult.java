package io.riverdb.engine.relational;

/** Caller-owned result for one durably allocated sequence value. */
public final class SequenceValueResult {
  private long value;
  private long commitSequence;
  private boolean available;

  public void reset() {
    value = 0;
    commitSequence = 0;
    available = false;
  }

  void set(long allocatedValue, long committedAt) {
    value = allocatedValue;
    commitSequence = committedAt;
    available = true;
  }

  public long value() {
    return value;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public boolean isAvailable() {
    return available;
  }
}
