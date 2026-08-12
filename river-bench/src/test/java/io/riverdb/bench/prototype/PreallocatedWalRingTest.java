package io.riverdb.bench.prototype;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
    assertEquals(WalReservationState.PUBLISHED, reservation.state());
    assertEquals(StatusCode.INVARIANT_BROKEN, ring.encode(reservation, 8L, 10L));
    assertEquals(StatusCode.INVARIANT_BROKEN, ring.publish(reservation));
    assertEquals(StatusCode.OK, ring.poll(record));
    assertEquals(7L, record.transactionId());
    assertEquals(9L, record.value());
    assertEquals(1L, ring.counters().publications());

    long firstGeneration = reservation.generation();
    assertEquals(StatusCode.OK, ring.tryReserve(reservation));
    assertEquals(firstGeneration + 1L, reservation.generation());
    assertEquals(WalReservationState.RESERVED, reservation.state());
  }

  @Test
  void reservationCarrierCannotBeReusedBeforeItsClaimIsTerminal() {
    var ring = new PreallocatedWalRing(4);
    var reservation = new WalReservation();

    assertEquals(StatusCode.OK, ring.tryReserve(reservation));
    assertEquals(1L, ring.claimedSequence());
    assertEquals(StatusCode.INVARIANT_BROKEN, ring.tryReserve(reservation));
    assertEquals(1L, ring.claimedSequence());
    assertEquals(StatusCode.OK, ring.encode(reservation, 1L, 2L));
    assertEquals(StatusCode.INVARIANT_BROKEN, ring.tryReserve(reservation));
    assertEquals(1L, ring.claimedSequence());
    assertEquals(StatusCode.OK, ring.publish(reservation));
  }

  @Test
  void delayedProducerHoleAndSaturationRecoverWithoutSkipping() throws Exception {
    var ring = new PreallocatedWalRing(2);
    var first = new WalReservation();
    var second = new WalReservation();
    var third = new WalReservation();
    var record = new WalRecord();
    var firstReserved = new CountDownLatch(1);
    var allowFirstPublish = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();

    Thread producer = Thread.ofPlatform().start(() -> {
      try {
        assertEquals(StatusCode.OK, ring.tryReserve(first));
        assertEquals(StatusCode.OK, ring.encode(first, 10L, 100L));
        firstReserved.countDown();
        if (!allowFirstPublish.await(5L, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting to publish delayed claim");
        }
        assertEquals(StatusCode.OK, ring.publish(first));
      } catch (Throwable caught) {
        failure.set(caught);
      }
    });

    assertTrue(firstReserved.await(5L, TimeUnit.SECONDS));
    assertEquals(StatusCode.OK, ring.tryReserve(second));
    assertEquals(StatusCode.OK, ring.encode(second, 20L, 200L));
    assertEquals(StatusCode.OK, ring.publish(second));
    assertEquals(-1L, ring.publishedSequence());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, ring.tryReserve(third));

    allowFirstPublish.countDown();
    producer.join(TimeUnit.SECONDS.toMillis(5L));
    assertFalse(producer.isAlive());
    if (failure.get() != null) {
      throw new AssertionError("producer failed", failure.get());
    }
    assertEquals(1L, ring.publishedSequence());
    assertEquals(StatusCode.OK, ring.poll(record));
    assertEquals(10L, record.transactionId());
    assertEquals(StatusCode.OK, ring.poll(record));
    assertEquals(20L, record.transactionId());
    assertEquals(StatusCode.OK, ring.tryReserve(third));
    assertEquals(StatusCode.OK, ring.encode(third, 30L, 300L));
    assertEquals(StatusCode.OK, ring.publish(third));
    assertEquals(StatusCode.OK, ring.poll(record));
    assertEquals(30L, record.transactionId());
    assertEquals(0L, ring.occupancy());
    assertEquals(1L, ring.counters().backpressureEvents());
  }
}
