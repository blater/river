package io.riverdb.journal.api.retention;

import io.riverdb.base.id.JournalPosition;

/** Observable lease summary; it is not a mutable safe-truncate frontier. */
public final class RetentionSnapshot {
  private int activeLeases;
  private JournalPosition oldestRequired = JournalPosition.NONE;
  private long earliestExpiryNanos;

  public RetentionSnapshot set(
      int leaseCount,
      JournalPosition minimumRequired,
      long earliestExpiry) {
    activeLeases = leaseCount;
    oldestRequired = minimumRequired;
    earliestExpiryNanos = earliestExpiry;
    return this;
  }

  public int activeLeases() {
    return activeLeases;
  }

  public JournalPosition oldestRequired() {
    return oldestRequired;
  }

  public long earliestExpiryNanos() {
    return earliestExpiryNanos;
  }
}
