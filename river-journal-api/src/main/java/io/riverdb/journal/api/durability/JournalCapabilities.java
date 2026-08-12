package io.riverdb.journal.api.durability;

/** Immutable provider feature advertisement represented as a compact durability bit mask. */
public final class JournalCapabilities {
  public static final JournalCapabilities LOCAL_ONLY = new JournalCapabilities(
      1L << DurabilityRequirement.LOCAL_DURABLE.ordinal(), false, false, false);

  private final long durabilityMask;
  private final boolean consensus;
  private final boolean stateSync;
  private final boolean followerServing;

  public JournalCapabilities(
      long durabilityMask,
      boolean consensus,
      boolean stateSync,
      boolean followerServing) {
    this.durabilityMask = durabilityMask;
    this.consensus = consensus;
    this.stateSync = stateSync;
    this.followerServing = followerServing;
  }

  public boolean supports(DurabilityRequirement requirement) {
    return (durabilityMask & (1L << requirement.ordinal())) != 0;
  }

  public long durabilityMask() {
    return durabilityMask;
  }

  public boolean hasConsensus() {
    return consensus;
  }

  public boolean hasStateSync() {
    return stateSync;
  }

  public boolean canServeFollowers() {
    return followerServing;
  }
}
