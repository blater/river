package io.riverdb.bench.prototype;

import java.util.concurrent.atomic.AtomicLong;

/** Primitive counters; the benchmark owner snapshots them off the hot path. */
public final class WalPrototypeCounters {
  private final AtomicLong reservations = new AtomicLong();
  private final AtomicLong backpressureEvents = new AtomicLong();
  private final AtomicLong encodedBytes = new AtomicLong();
  private final AtomicLong publications = new AtomicLong();
  private final AtomicLong consumed = new AtomicLong();
  private final AtomicLong checksumFailures = new AtomicLong();
  private final AtomicLong maximumOccupancy = new AtomicLong();

  public long reservations() {
    return reservations.get();
  }

  public long backpressureEvents() {
    return backpressureEvents.get();
  }

  public long encodedBytes() {
    return encodedBytes.get();
  }

  public long copiedBytes() {
    return 0L;
  }

  public long publications() {
    return publications.get();
  }

  public long consumed() {
    return consumed.get();
  }

  public long checksumFailures() {
    return checksumFailures.get();
  }

  public long maximumOccupancy() {
    return maximumOccupancy.get();
  }

  void recordReservation(long occupancy) {
    reservations.incrementAndGet();
    long current = maximumOccupancy.get();
    while (occupancy > current
        && !maximumOccupancy.compareAndSet(current, occupancy)) {
      current = maximumOccupancy.get();
    }
  }

  void recordBackpressure() {
    backpressureEvents.incrementAndGet();
  }

  void recordEncodedBytes(long bytes) {
    encodedBytes.addAndGet(bytes);
  }

  void recordPublication() {
    publications.incrementAndGet();
  }

  void recordConsumed() {
    consumed.incrementAndGet();
  }

  void recordChecksumFailure() {
    checksumFailures.incrementAndGet();
  }
}
