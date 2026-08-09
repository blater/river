package io.riverdb.journal.api;

/** Caller-owned reservation slot filled by one provider and valid until publish or cancellation. */
public final class JournalReservation {
  private long databaseIncarnationHigh;
  private long databaseIncarnationLow;
  private long journalGeneration;
  private long sequence;
  private long providerToken;
  private int slot = -1;
  private int payloadBytes;
  private boolean active;

  public JournalReservation reset() {
    databaseIncarnationHigh = 0;
    databaseIncarnationLow = 0;
    journalGeneration = 0;
    sequence = 0;
    providerToken = 0;
    slot = -1;
    payloadBytes = 0;
    active = false;
    return this;
  }

  /** Provider-only population hook; callers should treat this object as a capability token. */
  public JournalReservation assign(
      long databaseHigh,
      long databaseLow,
      long generation,
      long assignedSequence,
      long token,
      int assignedSlot,
      int bytes) {
    databaseIncarnationHigh = databaseHigh;
    databaseIncarnationLow = databaseLow;
    journalGeneration = generation;
    sequence = assignedSequence;
    providerToken = token;
    slot = assignedSlot;
    payloadBytes = bytes;
    active = true;
    return this;
  }

  /** Provider-only lifecycle hook. */
  public void complete() {
    active = false;
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

  public long sequence() {
    return sequence;
  }

  public long providerToken() {
    return providerToken;
  }

  public int slot() {
    return slot;
  }

  public int payloadBytes() {
    return payloadBytes;
  }

  public boolean isActive() {
    return active;
  }
}
