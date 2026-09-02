package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class IndexedLogicalRowIdRegistryTest {
  @Test
  void reservesIndependentExactContiguousTableRanges() {
    IndexedLogicalRowIdRegistry registry = new IndexedLogicalRowIdRegistry();
    IndexedLogicalRowIdReservation reservation = new IndexedLogicalRowIdReservation();

    assertEquals(StatusCode.OK, registry.admit(7, 1));
    assertEquals(StatusCode.OK, registry.admit(9, 1));
    assertEquals(StatusCode.OK, registry.reserve(7, 3, reservation));
    assertReservation(reservation, 7, 1, 3, 4);
    assertEquals(StatusCode.OK, registry.reserve(9, 2, reservation));
    assertReservation(reservation, 9, 1, 2, 3);
    assertEquals(StatusCode.OK, registry.reserve(7, 4, reservation));
    assertReservation(reservation, 7, 4, 4, 8);
  }

  @Test
  void concurrentReservationsNeverOverlap() throws Exception {
    int workers = 8;
    int reservationsPerWorker = 1_000;
    IndexedLogicalRowIdRegistry registry = new IndexedLogicalRowIdRegistry();
    assertEquals(StatusCode.OK, registry.admit(37, 1));
    long[] firstIds = new long[workers * reservationsPerWorker];
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    Future<?>[] completions = new Future<?>[workers];
    try {
      for (int worker = 0; worker < workers; worker++) {
        int offset = worker * reservationsPerWorker;
        completions[worker] = executor.submit(() -> {
          IndexedLogicalRowIdReservation reservation =
              new IndexedLogicalRowIdReservation();
          ready.countDown();
          start.await();
          for (int index = 0; index < reservationsPerWorker; index++) {
            assertEquals(StatusCode.OK, registry.reserve(37, 1, reservation));
            firstIds[offset + index] = reservation.firstLogicalRowId();
          }
          return null;
        });
      }
      ready.await();
      start.countDown();
      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      for (Future<?> completion : completions) completion.get();
    } finally {
      executor.shutdownNow();
    }
    Arrays.sort(firstIds);
    for (int index = 0; index < firstIds.length; index++) {
      assertEquals(index + 1L, firstIds[index]);
    }
    assertEquals(firstIds.length + 1L, registry.reservedNext(37));
    assertEquals(1, registry.publishedFloor(37));
  }

  @Test
  void reservationAndOutOfOrderPublicationAdvanceSeparateFloors() {
    IndexedLogicalRowIdRegistry registry = new IndexedLogicalRowIdRegistry();
    IndexedLogicalRowIdReservation reservation = new IndexedLogicalRowIdReservation();

    assertEquals(StatusCode.OK, registry.load(11, 30));
    assertEquals(StatusCode.OK, registry.reserve(11, 20, reservation));
    assertReservation(reservation, 11, 30, 20, 50);
    assertEquals(StatusCode.OK, registry.publishMax(11, 24));
    assertEquals(30, registry.publishedFloor(11));
    assertEquals(StatusCode.OK, registry.publishMax(11, 50));
    assertEquals(StatusCode.OK, registry.publishMax(11, 45));
    assertEquals(50, registry.publishedFloor(11));
    assertEquals(50, registry.reservedNext(11));
    assertEquals(11, registry.maximumObjectId());

    registry.reset();
    assertEquals(0, registry.maximumObjectId());
    assertEquals(0, registry.reservedNext(11));
    assertEquals(0, registry.publishedFloor(11));
    assertEquals(StatusCode.INVARIANT_BROKEN, registry.reserve(11, 1, reservation));
    assertEquals(StatusCode.OK, registry.load(11, 1));
    assertEquals(StatusCode.OK, registry.reserve(11, 1, reservation));
    assertReservation(reservation, 11, 1, 1, 2);
  }

  @Test
  void commitPublicationRequiresAdmissionAndCannotExceedReservation() {
    IndexedLogicalRowIdRegistry registry = new IndexedLogicalRowIdRegistry();
    IndexedLogicalRowIdReservation reservation = new IndexedLogicalRowIdReservation();

    assertEquals(StatusCode.INVARIANT_BROKEN, registry.publishMax(19, 2));
    assertEquals(StatusCode.INVARIANT_BROKEN, registry.reserve(19, 1, reservation));
    assertEquals(StatusCode.OK, registry.admit(19, 5));
    assertEquals(StatusCode.INVARIANT_BROKEN, registry.publishMax(19, 6));
    assertEquals(StatusCode.OK, registry.reserve(19, 1, reservation));
    assertEquals(StatusCode.OK, registry.publishMax(19, 6));
    assertEquals(6, registry.publishedFloor(19));
  }

  @Test
  void exhaustedSentinelAndRangeOverflowDoNotMutateAuthority() {
    IndexedLogicalRowIdRegistry registry = new IndexedLogicalRowIdRegistry();
    IndexedLogicalRowIdReservation reservation = new IndexedLogicalRowIdReservation();

    assertEquals(StatusCode.OK, registry.load(5, Long.MAX_VALUE - 2));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, registry.reserve(5, 3, reservation));
    assertReservation(reservation, 0, 0, 0, 0);
    assertEquals(Long.MAX_VALUE - 2, registry.reservedNext(5));
    assertEquals(Long.MAX_VALUE - 2, registry.publishedFloor(5));
    assertEquals(StatusCode.OK, registry.reserve(5, 2, reservation));
    assertReservation(reservation, 5, Long.MAX_VALUE - 2, 2, Long.MAX_VALUE);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, registry.reserve(5, 1, reservation));
  }

  private static void assertReservation(
      IndexedLogicalRowIdReservation reservation,
      long objectId, long first, int count, long next) {
    assertEquals(objectId, reservation.objectId());
    assertEquals(first, reservation.firstLogicalRowId());
    assertEquals(count, reservation.logicalRowCount());
    assertEquals(next, reservation.nextLogicalRowId());
  }
}
