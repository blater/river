package io.riverdb.bench.prototype;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import io.riverdb.base.error.StatusCode;

/**
 * Disposable bounded MPSC WAL mechanism: claim, direct encode, publish, consume.
 *
 * <p>A publication frontier advances only across consecutive completed claims.
 * The prototype deliberately has a fixed record layout and is not a format/API.
 */
public final class PreallocatedWalRing {
  static final int RECORD_BYTES = 32;
  private static final long UNPUBLISHED = Long.MIN_VALUE;
  private static final int SEQUENCE_OFFSET = 0;
  private static final int TRANSACTION_OFFSET = 8;
  private static final int VALUE_OFFSET = 16;
  private static final int CHECKSUM_OFFSET = 24;

  private final int capacity;
  private final int mask;
  private final ByteBuffer records;
  private final AtomicLongArray completedSequences;
  private final AtomicLong claimed = new AtomicLong();
  private final AtomicLong published = new AtomicLong(-1L);
  private final AtomicLong consumed = new AtomicLong();
  private final WalPrototypeCounters counters = new WalPrototypeCounters();

  public PreallocatedWalRing(int capacity) {
    if (capacity < 2 || Integer.bitCount(capacity) != 1) {
      throw new IllegalArgumentException("capacity must be a power of two >= 2");
    }
    this.capacity = capacity;
    mask = capacity - 1;
    records = ByteBuffer.allocateDirect(Math.multiplyExact(capacity, RECORD_BYTES))
      .order(ByteOrder.LITTLE_ENDIAN);
    completedSequences = new AtomicLongArray(capacity);
    for (int slot = 0; slot < capacity; slot++) {
      completedSequences.set(slot, UNPUBLISHED);
    }
  }

  public StatusCode tryReserve(WalReservation target) {
    long sequence;
    long head;
    do {
      sequence = claimed.get();
      head = consumed.get();
      if (sequence - head >= capacity) {
        counters.recordBackpressure();
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    } while (!claimed.compareAndSet(sequence, sequence + 1L));

    target.sequence = sequence;
    target.offset = slotOffset(sequence);
    target.encoded = false;
    counters.recordReservation(sequence + 1L - head);
    return StatusCode.OK;
  }

  public StatusCode encode(
      WalReservation reservation,
      long transactionId,
      long value
  ) {
    if (!isCurrentReservation(reservation)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    long sequence = reservation.sequence;
    long checksum = checksum(sequence, transactionId, value);
    int offset = reservation.offset;
    records.putLong(offset + SEQUENCE_OFFSET, sequence);
    records.putLong(offset + TRANSACTION_OFFSET, transactionId);
    records.putLong(offset + VALUE_OFFSET, value);
    records.putLong(offset + CHECKSUM_OFFSET, checksum);
    reservation.encoded = true;
    counters.recordEncodedBytes(RECORD_BYTES);
    return StatusCode.OK;
  }

  public StatusCode publish(WalReservation reservation) {
    if (!reservation.encoded || !isCurrentReservation(reservation)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    long sequence = reservation.sequence;
    completedSequences.set(slot(sequence), sequence);
    counters.recordPublication();
    reservation.encoded = false;
    advancePublicationFrontier();
    return StatusCode.OK;
  }

  public StatusCode poll(WalRecord target) {
    long sequence = consumed.get();
    if (sequence > published.get()) {
      return StatusCode.RETRY;
    }
    int offset = slotOffset(sequence);
    long storedSequence = records.getLong(offset + SEQUENCE_OFFSET);
    long transactionId = records.getLong(offset + TRANSACTION_OFFSET);
    long value = records.getLong(offset + VALUE_OFFSET);
    long storedChecksum = records.getLong(offset + CHECKSUM_OFFSET);
    if (storedSequence != sequence
        || storedChecksum != checksum(sequence, transactionId, value)) {
      counters.recordChecksumFailure();
      return StatusCode.CORRUPTION;
    }
    target.sequence = sequence;
    target.transactionId = transactionId;
    target.value = value;
    completedSequences.set(slot(sequence), UNPUBLISHED);
    consumed.lazySet(sequence + 1L);
    counters.recordConsumed();
    return StatusCode.OK;
  }

  public int capacity() {
    return capacity;
  }

  public long claimedSequence() {
    return claimed.get();
  }

  public long publishedSequence() {
    return published.get();
  }

  public long consumedSequence() {
    return consumed.get();
  }

  public long occupancy() {
    return claimed.get() - consumed.get();
  }

  public WalPrototypeCounters counters() {
    return counters;
  }

  private boolean isCurrentReservation(WalReservation reservation) {
    long sequence = reservation.sequence;
    return sequence >= consumed.get()
      && sequence < claimed.get()
      && reservation.offset == slotOffset(sequence);
  }

  private void advancePublicationFrontier() {
    long frontier = published.get();
    while (true) {
      long next = frontier + 1L;
      if (completedSequences.get(slot(next)) != next) {
        return;
      }
      if (published.compareAndSet(frontier, next)) {
        frontier = next;
      } else {
        frontier = published.get();
      }
    }
  }

  private int slot(long sequence) {
    return (int) sequence & mask;
  }

  private int slotOffset(long sequence) {
    return slot(sequence) * RECORD_BYTES;
  }

  private static long checksum(long sequence, long transactionId, long value) {
    long hash = sequence ^ Long.rotateLeft(transactionId, 17);
    hash ^= Long.rotateLeft(value, 41);
    hash *= 0x9E3779B97F4A7C15L;
    return hash ^ hash >>> 29;
  }
}
