package io.riverdb.bench.prototype;

/** Explicit lifecycle for a caller-owned disposable WAL reservation. */
public enum WalReservationState {
  AVAILABLE,
  RESERVED,
  ENCODED,
  PUBLISHED
}
