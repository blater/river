package io.riverdb.observability.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedEventRingPublicationHoleTest {
  @Test
  void stalledClaimIsVisibleAndRingRecoversInClaimOrder() throws Exception {
    CountDownLatch firstClaimed = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    PublicationClaimObserver observer = position -> {
      if (position == 0) {
        firstClaimed.countDown();
        awaitUninterruptibly(releaseFirst);
      }
    };
    BoundedEventRing ring = new BoundedEventRing(
        4,
        Severity.DEBUG,
        SaturationPolicy.DROP_AND_COUNT,
        ConsumerAccess.GUARDED,
        observer);
    DiagnosticContext context = new DiagnosticContext();

    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<EventPublishResult> first = executor.submit(() -> ring.publish(event(0, context)));
      firstClaimed.await(5, TimeUnit.SECONDS);

      assertEquals(EventPublishResult.PUBLISHED, ring.publish(event(1, context)));
      assertEquals(EventPublishResult.PUBLISHED, ring.publish(event(2, context)));
      assertEquals(EventPublishResult.PUBLISHED, ring.publish(event(3, context)));
      assertEquals(EventPublishResult.DROPPED, ring.publish(event(4, context)));
      assertEquals(4, ring.approximateSize());

      DiagnosticEvent target = new DiagnosticEvent();
      assertEquals(EventPollResult.PUBLICATION_HOLE, ring.poll(target));
      assertEquals(1, ring.publicationHoleObservationCount());
      assertEquals(1, ring.droppedCount());

      releaseFirst.countDown();
      assertEquals(EventPublishResult.PUBLISHED, first.get(5, TimeUnit.SECONDS));
      for (int sequence = 0; sequence < 4; sequence++) {
        assertEquals(EventPollResult.POLLED, ring.poll(target));
        assertEquals(sequence, target.sequence());
      }
      assertEquals(EventPollResult.EMPTY, ring.poll(target));
      assertEquals(0, ring.approximateSize());
    }
  }

  private static DiagnosticEvent event(long sequence, DiagnosticContext context) {
    return new DiagnosticEvent().set(
        EventTypeId.WAL_STALL,
        Severity.WARN,
        sequence,
        sequence,
        context,
        sequence,
        0,
        0,
        0);
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
