package io.riverdb.bench.prototype;

import io.riverdb.base.error.StatusCode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Measures the bounded multi-producer WAL path and its saturation recovery. */
final class PrototypeWalMpsc {
  private PrototypeWalMpsc() { }

  static PrototypeSmoke.Measurement measure(
      PrototypeSmoke.AllocationMeter allocation,
      int hotIterations) {
    int producerIterations = hotIterations / 2;
    var ring = new PreallocatedWalRing(128);
    var start = new CountDownLatch(1);
    var holeReserved = new CountDownLatch(1);
    var secondPublished = new CountDownLatch(1);
    var releaseHole = new CountDownLatch(1);
    long[][] samples = new long[][] {
      new long[producerIterations], new long[producerIterations]
    };
    long[] allocated = new long[2];
    StatusCode[] producerStatuses = new StatusCode[] {StatusCode.OK, StatusCode.OK};
    Thread first = Thread.ofPlatform().unstarted(() -> runProducer(
        0, producerIterations, ring, start, holeReserved, secondPublished,
        releaseHole, samples[0], allocated, producerStatuses, allocation));
    Thread second = Thread.ofPlatform().unstarted(() -> runProducer(
        1, producerIterations, ring, start, holeReserved, secondPublished,
        releaseHole, samples[1], allocated, producerStatuses, allocation));
    first.start();
    second.start();
    long started = System.nanoTime();
    start.countDown();
    StatusCode status = StatusCode.OK;
    long delayedPublicationObserved = 0L;
    long consumed = 0L;
    var record = new WalRecord();
    try {
      if (!holeReserved.await(5L, TimeUnit.SECONDS)
          || !secondPublished.await(5L, TimeUnit.SECONDS)) {
        status = StatusCode.TIMEOUT;
      } else if (ring.publishedSequence() == -1L) {
        delayedPublicationObserved = 1L;
      } else {
        status = StatusCode.INVARIANT_BROKEN;
      }
      releaseHole.countDown();
      long expected = (long) producerIterations * 2L;
      while (status.isOk() && consumed < expected) {
        StatusCode pollStatus = ring.poll(record);
        if (pollStatus.isOk()) consumed++;
        else if (pollStatus == StatusCode.RETRY) Thread.onSpinWait();
        else status = pollStatus;
      }
      first.join(TimeUnit.SECONDS.toMillis(5L));
      second.join(TimeUnit.SECONDS.toMillis(5L));
      if (first.isAlive() || second.isAlive()) status = StatusCode.TIMEOUT;
      else if (!producerStatuses[0].isOk()) status = producerStatuses[0];
      else if (!producerStatuses[1].isOk()) status = producerStatuses[1];
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      status = StatusCode.CANCELLED;
    } finally {
      releaseHole.countDown();
    }
    long elapsed = System.nanoTime() - started;
    long[] mergedSamples = new long[producerIterations * 2];
    System.arraycopy(samples[0], 0, mergedSamples, 0, producerIterations);
    System.arraycopy(samples[1], 0, mergedSamples, producerIterations, producerIterations);
    PrototypeSmoke.Measurement measurement = PrototypeSmoke.summarize(
        "wal_ring_mpsc_delayed_hole",
        status,
        mergedSamples,
        elapsed,
        allocated[0] < 0L || allocated[1] < 0L ? -1L : allocated[0] + allocated[1]);
    measurement.operations = consumed;
    measurement.bytes = ring.counters().encodedBytes();
    measurement.copiedBytes = ring.counters().copiedBytes();
    measurement.maximumOccupancy = ring.counters().maximumOccupancy();
    measurement.backpressureEvents = ring.counters().backpressureEvents();
    measurement.delayedPublicationObserved = delayedPublicationObserved;
    measurement.saturationRecovered = verifySaturationRecovery();
    if (measurement.saturationRecovered == 0L && status.isOk()) {
      measurement.status = StatusCode.INVARIANT_BROKEN;
    }
    return measurement;
  }

  private static void runProducer(
      int producer,
      int iterations,
      PreallocatedWalRing ring,
      CountDownLatch start,
      CountDownLatch holeReserved,
      CountDownLatch secondPublished,
      CountDownLatch releaseHole,
      long[] samples,
      long[] allocated,
      StatusCode[] producerStatuses,
      PrototypeSmoke.AllocationMeter allocation) {
    var reservation = new WalReservation();
    try {
      start.await();
      if (producer == 1) holeReserved.await();
      long allocationBefore = allocation.currentThreadBytes();
      for (int iteration = 0; iteration < iterations; iteration++) {
        long operationStarted = System.nanoTime();
        StatusCode status;
        do {
          status = ring.tryReserve(reservation);
          if (status == StatusCode.RESOURCE_EXHAUSTED) Thread.onSpinWait();
        } while (status == StatusCode.RESOURCE_EXHAUSTED);
        if (status.isOk()) {
          long transaction = ((long) producer << 32) | iteration;
          status = ring.encode(reservation, transaction, transaction * 17L);
        }
        if (status.isOk() && producer == 0 && iteration == 0) {
          holeReserved.countDown();
          releaseHole.await();
        }
        if (status.isOk()) status = ring.publish(reservation);
        if (status.isOk() && producer == 1 && iteration == 0) secondPublished.countDown();
        samples[iteration] = System.nanoTime() - operationStarted;
        if (!status.isOk()) {
          producerStatuses[producer] = status;
          break;
        }
      }
      allocated[producer] = allocation.delta(allocationBefore);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      producerStatuses[producer] = StatusCode.CANCELLED;
    }
  }

  private static long verifySaturationRecovery() {
    var ring = new PreallocatedWalRing(2);
    var first = new WalReservation();
    var second = new WalReservation();
    var third = new WalReservation();
    var record = new WalRecord();
    if (!reserveEncodePublish(ring, first, 1L).isOk()
        || !reserveEncodePublish(ring, second, 2L).isOk()
        || ring.tryReserve(third) != StatusCode.RESOURCE_EXHAUSTED
        || !ring.poll(record).isOk()
        || record.transactionId() != 1L
        || !ring.tryReserve(third).isOk()
        || !ring.encode(third, 3L, 3L).isOk()
        || !ring.publish(third).isOk()
        || !ring.poll(record).isOk()
        || record.transactionId() != 2L
        || !ring.poll(record).isOk()
        || record.transactionId() != 3L
        || ring.occupancy() != 0L) return 0L;
    return 1L;
  }

  private static StatusCode reserveEncodePublish(
      PreallocatedWalRing ring, WalReservation reservation, long value) {
    StatusCode status = ring.tryReserve(reservation);
    if (status.isOk()) status = ring.encode(reservation, value, value);
    if (status.isOk()) status = ring.publish(reservation);
    return status;
  }
}
