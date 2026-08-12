package io.riverdb.journal.api.durability;

import io.riverdb.base.id.WalGeneration;

/** Caller-owned durability result and proof coordinates. */
public final class DurabilityResult {
  private DurabilityOutcome outcome = DurabilityOutcome.PENDING;
  private DurabilityRequirement requestedRequirement = DurabilityRequirement.LOCAL_DURABLE;
  private long satisfiedDurabilityMask;
  private long databaseIncarnationHigh;
  private long databaseIncarnationLow;
  private long journalGeneration;
  private long coveredSequence;
  private WalGeneration walGeneration = WalGeneration.NONE;
  private long durableEndLsnExclusive;

  public DurabilityResult reset() {
    outcome = DurabilityOutcome.PENDING;
    requestedRequirement = DurabilityRequirement.LOCAL_DURABLE;
    satisfiedDurabilityMask = 0;
    databaseIncarnationHigh = 0;
    databaseIncarnationLow = 0;
    journalGeneration = 0;
    coveredSequence = 0;
    walGeneration = WalGeneration.NONE;
    durableEndLsnExclusive = 0;
    return this;
  }

  /** Provider-only population hook. */
  public DurabilityResult set(
      DurabilityOutcome durabilityOutcome,
      DurabilityRequirement requested,
      long satisfiedMask,
      long databaseHigh,
      long databaseLow,
      long logicalGeneration,
      long sequence,
      WalGeneration localWalGeneration,
      long localDurableEndLsnExclusive) {
    outcome = durabilityOutcome;
    requestedRequirement = requested;
    satisfiedDurabilityMask = satisfiedMask;
    databaseIncarnationHigh = databaseHigh;
    databaseIncarnationLow = databaseLow;
    journalGeneration = logicalGeneration;
    coveredSequence = sequence;
    walGeneration = localWalGeneration;
    durableEndLsnExclusive = localDurableEndLsnExclusive;
    return this;
  }

  public DurabilityOutcome outcome() {
    return outcome;
  }

  public DurabilityRequirement requestedRequirement() {
    return requestedRequirement;
  }

  public long satisfiedDurabilityMask() {
    return satisfiedDurabilityMask;
  }

  public boolean satisfies(DurabilityRequirement requirement) {
    return (satisfiedDurabilityMask & (1L << requirement.ordinal())) != 0;
  }

  public long databaseIncarnationHigh() {
    return databaseIncarnationHigh;
  }

  public long databaseIncarnationLow() {
    return databaseIncarnationLow;
  }

  public long journalGeneration() {
    return journalGeneration;
  }

  public long coveredSequence() {
    return coveredSequence;
  }

  public WalGeneration walGeneration() {
    return walGeneration;
  }

  public long durableEndLsnExclusive() {
    return durableEndLsnExclusive;
  }
}
