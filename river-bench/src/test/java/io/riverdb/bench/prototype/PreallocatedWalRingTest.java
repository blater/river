package io.riverdb.bench.prototype;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class PreallocatedWalRingTest {
  @Test
  void publicationCannotCrossAReservedGap() {
    var ring = new PreallocatedWalRing(4);
    var first = new WalReservation();
    var second = new WalReservation();
    var record = new WalRecord();

    assertEquals(StatusCode.OK, ring.tryReserve(first));
    assertEquals(StatusCode.OK, ring.tryReserve(second));
    assertEquals(StatusCode.OK, ring.encode(second, 22L, 220L));
    assertEquals(StatusCode.OK, ring.publish(second));

    assertEquals(-1L, ring.publishedSequence());
    assertEquals(StatusCode.RETRY, ring.poll(record));

    assertEquals(StatusCode.OK, ring.encode(first, 11L, 110L));
    assertEquals(StatusCode.OK, ring.publish(first));
    assertEquals(1L, ring.publishedSequence());
    assertEquals(StatusCode.OK, ring.poll(record));
    assertEquals(0L, record.sequence());
    assertEquals(11L, record.transactionId());
    assertEquals(StatusCode.OK, ring.poll(record));
    assertEquals(1L, record.sequence());
    assertEquals(22L, record.transactionId());
  }

  @Test
  void boundedRingReportsBackpressureAndCanReuseSlots() {
    var ring = new PreallocatedWalRing(2);
    var reservations = new WalReservation[] {
      new WalReservation(), new WalReservation(), new WalReservation()
    };
    var record = new WalRecord();

    for (int index = 0; index < 2; index++) {
      assertEquals(StatusCode.OK, ring.tryReserve(reservations[index]));
      assertEquals(
        StatusCode.OK,
        ring.encode(reservations[index], index, index * 10L)
      );
      assertEquals(StatusCode.OK, ring.publish(reservations[index]));
    }
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, ring.tryReserve(reservations[2]));
    assertEquals(2L, ring.counters().maximumOccupancy());
    assertEquals(StatusCode.OK, ring.poll(record));
    assertEquals(StatusCode.OK, ring.tryReserve(reservations[2]));
    assertEquals(StatusCode.OK, ring.encode(reservations[2], 2L, 20L));
    assertEquals(StatusCode.OK, ring.publish(reservations[2]));
    assertEquals(0L, ring.counters().copiedBytes());
  }

  @Test
  void rejectsDoublePublishAndConsumedReservationReuse() {
    var ring = new PreallocatedWalRing(2);
    var reservation = new WalReservation();
    var record = new WalRecord();

    assertEquals(StatusCode.OK, ring.tryReserve(reservation));
    assertEquals(StatusCode.OK, ring.encode(reservation, 7L, 9L));
    assertEquals(StatusCode.OK, ring.publish(reservation));
    assertEquals(StatusCode.INVARIANT_BROKEN, ring.publish(reservation));
    assertEquals(StatusCode.OK, ring.poll(record));
    assertEquals(StatusCode.INVARIANT_BROKEN, ring.encode(reservation, 8L, 10L));
    assertEquals(1L, ring.counters().publications());
  }
}
