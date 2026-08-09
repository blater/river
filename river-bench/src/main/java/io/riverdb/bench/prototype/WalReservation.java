package io.riverdb.bench.prototype;

/** Caller-owned reservation carrier. */
public final class WalReservation {
  long sequence = -1L;
  int offset;
  boolean encoded;

  public long sequence() {
    return sequence;
  }
}
