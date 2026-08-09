package io.riverdb.journal.api;

/** Caller-owned reservation slot filled by one provider and valid until publish or cancellation. */
public final class JournalReservation {
  private long ownerHigh;
  private long ownerLow;
  private long databaseIncarnationHigh;
  private long databaseIncarnationLow;
  private long journalGeneration;
  private long sequence;
  private long providerToken;
  private int slot = -1;
  private int payloadBytes;
  private boolean active;

  public boolean isOwnedBy(long providerHigh, long providerLow) {
    return ownerHigh == providerHigh && ownerLow == providerLow;
  }

  /** Clears an inactive handle for caller reuse; active capabilities cannot be discarded. */
  public io.riverdb.base.error.StatusCode reset() {
    if (active) {
      return io.riverdb.base.error.StatusCode.CONFLICT;
    }
    ownerHigh = 0;
    ownerLow = 0;
    databaseIncarnationHigh = 0;
    databaseIncarnationLow = 0;
    journalGeneration = 0;
    sequence = 0;
    providerToken = 0;
    slot = -1;
    payloadBytes = 0;
    active = false;
    return io.riverdb.base.error.StatusCode.OK;
  }

  /** Authenticated provider population hook; unknown callers cannot forge the owner secret. */
  public io.riverdb.base.error.StatusCode claim(
      long providerHigh,
      long providerLow,
      long databaseHigh,
      long databaseLow,
      long generation,
      long assignedSequence,
      long token,
      int assignedSlot,
      int bytes) {
    if (active || (providerHigh == 0 && providerLow == 0)) {
      return io.riverdb.base.error.StatusCode.CONFLICT;
    }
    ownerHigh = providerHigh;
    ownerLow = providerLow;
    databaseIncarnationHigh = databaseHigh;
    databaseIncarnationLow = databaseLow;
    journalGeneration = generation;
    sequence = assignedSequence;
    providerToken = token;
    slot = assignedSlot;
    payloadBytes = bytes;
    active = true;
    return io.riverdb.base.error.StatusCode.OK;
  }

  /** Authenticated provider lifecycle hook. */
  public io.riverdb.base.error.StatusCode complete(long providerHigh, long providerLow) {
    if (!active || !isOwnedBy(providerHigh, providerLow)) {
      return io.riverdb.base.error.StatusCode.CONFLICT;
    }
    active = false;
    return io.riverdb.base.error.StatusCode.OK;
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
