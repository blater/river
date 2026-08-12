package io.riverdb.journal.api.outcome;

/** Caller-owned observation of the independent bounded request-outcome store. */
public final class OutcomeRetentionSnapshot {
  private int retainedOutcomes;
  private int capacity;
  private long earliestForgetAtNanos;

  public OutcomeRetentionSnapshot set(int retained, int maximum, long earliestForgetAt) {
    retainedOutcomes = retained;
    capacity = maximum;
    earliestForgetAtNanos = earliestForgetAt;
    return this;
  }

  public int retainedOutcomes() {
    return retainedOutcomes;
  }

  public int capacity() {
    return capacity;
  }

  public long earliestForgetAtNanos() {
    return earliestForgetAtNanos;
  }
}
