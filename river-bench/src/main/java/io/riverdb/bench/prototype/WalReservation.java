package io.riverdb.bench.prototype;

/**
 * Caller-owned reservation carrier.
 *
 * <p>The generation identifies reuse for diagnostics. Sequence and ring slot
 * state, not generation alone, authorize encoding and publication.
 */
public final class WalReservation {
  long sequence = -1L;
  long generation;
  int offset;
  WalReservationState state = WalReservationState.AVAILABLE;

  public long sequence() {
    return sequence;
  }

  public long generation() {
    return generation;
  }

  public WalReservationState state() {
    return state;
  }
}
