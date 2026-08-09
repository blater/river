package io.riverdb.journal.api.durability;

/** Caller-owned allocation-bounded wait handle; no future or callback is retained. */
public final class DurabilityTicket {
  private long providerToken;
  private long journalGeneration;
  private long requiredSequence;
  private DurabilityRequirement requirement = DurabilityRequirement.LOCAL_DURABLE;
  private long deadlineNanos;
  private boolean active;

  public DurabilityTicket reset() {
    providerToken = 0;
    journalGeneration = 0;
    requiredSequence = 0;
    requirement = DurabilityRequirement.LOCAL_DURABLE;
    deadlineNanos = 0;
    active = false;
    return this;
  }

  /** Provider-only population hook. */
  public DurabilityTicket assign(
      long token,
      long generation,
      long sequence,
      DurabilityRequirement requested,
      long deadline) {
    providerToken = token;
    journalGeneration = generation;
    requiredSequence = sequence;
    requirement = requested;
    deadlineNanos = deadline;
    active = true;
    return this;
  }

  /** Provider-only lifecycle hook. */
  public void complete() {
    active = false;
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
