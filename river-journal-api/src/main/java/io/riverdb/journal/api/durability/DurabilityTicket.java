package io.riverdb.journal.api.durability;

/** Caller-owned allocation-bounded wait handle; no future or callback is retained. */
public final class DurabilityTicket {
  private long ownerHigh;
  private long ownerLow;
  private long providerToken;
  private long journalGeneration;
  private long requiredSequence;
  private DurabilityRequirement requirement = DurabilityRequirement.LOCAL_DURABLE;
  private long deadlineNanos;
  private boolean active;

  public boolean isOwnedBy(long providerHigh, long providerLow) {
    return ownerHigh == providerHigh && ownerLow == providerLow;
  }

  public io.riverdb.base.error.StatusCode reset() {
    if (active) {
      return io.riverdb.base.error.StatusCode.CONFLICT;
    }
    ownerHigh = 0;
    ownerLow = 0;
    providerToken = 0;
    journalGeneration = 0;
    requiredSequence = 0;
    requirement = DurabilityRequirement.LOCAL_DURABLE;
    deadlineNanos = 0;
    active = false;
    return io.riverdb.base.error.StatusCode.OK;
  }

  /** Provider-only population hook. */
  public io.riverdb.base.error.StatusCode claim(
      long providerHigh,
      long providerLow,
      long token,
      long generation,
      long sequence,
      DurabilityRequirement requested,
      long deadline) {
    if (active || (providerHigh == 0 && providerLow == 0)) {
      return io.riverdb.base.error.StatusCode.CONFLICT;
    }
    ownerHigh = providerHigh;
    ownerLow = providerLow;
    providerToken = token;
    journalGeneration = generation;
    requiredSequence = sequence;
    requirement = requested;
    deadlineNanos = deadline;
    active = true;
    return io.riverdb.base.error.StatusCode.OK;
  }

  /** Provider-only lifecycle hook. */
  public io.riverdb.base.error.StatusCode complete(long providerHigh, long providerLow) {
    if (!active || !isOwnedBy(providerHigh, providerLow)) {
      return io.riverdb.base.error.StatusCode.CONFLICT;
    }
    active = false;
    return io.riverdb.base.error.StatusCode.OK;
  }

  public long providerToken() {
    return providerToken;
  }

  public long journalGeneration() {
    return journalGeneration;
  }

  public long requiredSequence() {
    return requiredSequence;
  }

  public DurabilityRequirement requirement() {
    return requirement;
  }

  public long deadlineNanos() {
    return deadlineNanos;
  }

  public boolean isActive() {
    return active;
  }
}
