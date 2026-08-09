package io.riverdb.journal.api.durability;

/** Caller-owned durability result and proof coordinates. */
public final class DurabilityResult {
  private DurabilityOutcome outcome = DurabilityOutcome.PENDING;
  private DurabilityRequirement satisfiedRequirement = DurabilityRequirement.LOCAL_DURABLE;
  private long databaseIncarnationHigh;
  private long databaseIncarnationLow;
  private long journalGeneration;
  private long coveredSequence;
  private long walGeneration;
  private long durableEndLsnExclusive;

  public DurabilityResult reset() {
    outcome = DurabilityOutcome.PENDING;
    satisfiedRequirement = DurabilityRequirement.LOCAL_DURABLE;
    databaseIncarnationHigh = 0;
    databaseIncarnationLow = 0;
    journalGeneration = 0;
    coveredSequence = 0;
    walGeneration = 0;
    durableEndLsnExclusive = 0;
    return this;
  }

  /** Provider-only population hook. */
  public DurabilityResult set(
      DurabilityOutcome durabilityOutcome,
      DurabilityRequirement satisfied,
      long databaseHigh,
      long databaseLow,
      long logicalGeneration,
      long sequence,
      long localWalGeneration,
      long localDurableEndLsnExclusive) {
    outcome = durabilityOutcome;
    satisfiedRequirement = satisfied;
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

  public DurabilityRequirement satisfiedRequirement() {
    return satisfiedRequirement;
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

  public long walGeneration() {
    return walGeneration;
  }

  public long durableEndLsnExclusive() {
    return durableEndLsnExclusive;
  }
}
