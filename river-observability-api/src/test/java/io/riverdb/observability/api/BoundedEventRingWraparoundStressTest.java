package io.riverdb.observability.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.observability.api.event.BoundedEventRing;
import io.riverdb.observability.api.event.BoundedEventRingFactory;
import io.riverdb.observability.api.event.DiagnosticContext;
import io.riverdb.observability.api.event.DiagnosticEvent;
import io.riverdb.observability.api.event.EventPollResult;
import io.riverdb.observability.api.event.EventPublishResult;
import io.riverdb.observability.api.event.EventTypeId;
import io.riverdb.observability.api.event.ObservabilityBuildMode;
import io.riverdb.observability.api.event.SaturationPolicy;
import io.riverdb.observability.api.event.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;

class BoundedEventRingWraparoundStressTest {
  @Test
  void liveConsumerReusesSlotsAcrossManyConcurrentWraps() throws Exception {
    int producerCount = 4;
    int eventsPerProducer = 5_000;
    int eventCount = producerCount * eventsPerProducer;
    BoundedEventRing ring = BoundedEventRingFactory.create(
        64,
        Severity.DEBUG,
        SaturationPolicy.REPORT_BACKPRESSURE,
        ObservabilityBuildMode.TEST);
    AtomicIntegerArray seen = new AtomicIntegerArray(eventCount);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(producerCount + 1)) {
      Future<Integer> consumer = executor.submit(() -> consume(ring, seen, eventCount, start));
      List<Future<Void>> producers = new ArrayList<>(producerCount);
      for (int producer = 0; producer < producerCount; producer++) {
        int producerId = producer;
        producers.add(executor.submit(() -> {
          DiagnosticContext context = new DiagnosticContext().sessionId(producerId);
          DiagnosticEvent event = new DiagnosticEvent();
          start.await();
          for (int offset = 0; offset < eventsPerProducer; offset++) {
            int sequence = producerId * eventsPerProducer + offset;
            event.set(EventTypeId.WAL_STALL, Severity.WARN, sequence, sequence,
                context, sequence, producerId, 0, 0);
            while (ring.publish(event) != EventPublishResult.PUBLISHED) {
              Thread.onSpinWait();
            }
          }
          return null;
        }));
      }

      start.countDown();
      for (Future<Void> producer : producers) {
        producer.get(15, TimeUnit.SECONDS);
      }
      assertEquals(eventCount, consumer.get(15, TimeUnit.SECONDS));
    }

    assertEquals(eventCount, ring.publishedCount());
    assertEquals(0, ring.approximateSize());
    assertEquals(0, ring.consumerMisuseCount());
    assertTrue(ring.backpressureCount() >= 0);
  }

  private static int consume(
      BoundedEventRing ring,
      AtomicIntegerArray seen,
      int eventCount,
      CountDownLatch start) throws InterruptedException {
    DiagnosticEvent target = new DiagnosticEvent();
    int consumed = 0;
    start.await();
    while (consumed < eventCount) {
      EventPollResult result = ring.poll(target);
      if (result == EventPollResult.POLLED) {
        int sequence = (int) target.sequence();
        assertEquals(0, seen.getAndIncrement(sequence));
        consumed++;
      } else {
        assertTrue(result == EventPollResult.EMPTY
            || result == EventPollResult.PUBLICATION_HOLE);
        Thread.onSpinWait();
      }
    }
    return consumed;
  }
}
