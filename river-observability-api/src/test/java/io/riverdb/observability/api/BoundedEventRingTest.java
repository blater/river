package io.riverdb.observability.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.observability.api.event.BoundedEventRing;
import io.riverdb.observability.api.event.BoundedEventRingFactory;
import io.riverdb.observability.api.event.DiagnosticContext;
import io.riverdb.observability.api.event.DiagnosticEvent;
import io.riverdb.observability.api.event.EventPollResult;
import io.riverdb.observability.api.event.EventPublishResult;
import io.riverdb.observability.api.event.EventTypeId;
import io.riverdb.observability.api.event.LevelGatedDiagnosticSink;
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

class BoundedEventRingTest {
  @Test
  void rejectsInvalidColdConfiguration() {
    assertThrows(IllegalArgumentException.class,
        () -> ring(1, Severity.INFO, SaturationPolicy.DROP_AND_COUNT));
    assertThrows(IllegalArgumentException.class,
        () -> ring(3, Severity.INFO, SaturationPolicy.DROP_AND_COUNT));
    assertThrows(IllegalArgumentException.class,
        () -> ring(
            BoundedEventRing.MAX_CAPACITY + 1,
            Severity.INFO,
            SaturationPolicy.DROP_AND_COUNT));
  }

  @Test
  void preservesOrderAndCopiesBeforeProducerReuse() {
    BoundedEventRing ring = ring(
        4, Severity.DEBUG, SaturationPolicy.DROP_AND_COUNT);
    DiagnosticContext context = new DiagnosticContext().databaseId(9);
    DiagnosticEvent event = new DiagnosticEvent();
    for (int sequence = 0; sequence < 4; sequence++) {
      event.set(EventTypeId.WAL_STALL, Severity.WARN, sequence, sequence,
          context, sequence, 0, 0, 0);
      assertEquals(EventPublishResult.PUBLISHED, ring.publish(event));
    }
    event.reset();

    DiagnosticEvent target = new DiagnosticEvent();
    for (int sequence = 0; sequence < 4; sequence++) {
      assertEquals(EventPollResult.POLLED, ring.poll(target));
      assertEquals(sequence, target.sequence());
      assertEquals(sequence, target.field0());
    }
    assertEquals(EventPollResult.EMPTY, ring.poll(target));
    assertEquals(0, ring.approximateSize());
    assertEquals(4, ring.publishedCount());
  }

  @Test
  void everySaturationModeIsExplicitAndCountedWithoutWaiting() {
    assertSaturation(SaturationPolicy.DROP_AND_COUNT, EventPublishResult.DROPPED, 1, 0);
    assertSaturation(
        SaturationPolicy.REPORT_BACKPRESSURE, EventPublishResult.BACKPRESSURE, 0, 1);
  }

  @Test
  void levelGateAvoidsPublicationAndCanChangeAtRuntime() {
    BoundedEventRing ring = ring(
        2, Severity.DEBUG, SaturationPolicy.DROP_AND_COUNT);
    LevelGatedDiagnosticSink gate = new LevelGatedDiagnosticSink(ring, Severity.WARN);
    DiagnosticContext context = new DiagnosticContext();
    DiagnosticEvent event = new DiagnosticEvent().set(
        EventTypeId.DATABASE_STARTED, Severity.INFO, 1, 1, context, 0, 0, 0, 0);

    assertFalse(gate.isEnabled(Severity.INFO));
    assertEquals(EventPublishResult.DISABLED, gate.publish(event));
    gate.threshold(Severity.INFO);
    assertTrue(gate.isEnabled(Severity.INFO));
    assertEquals(EventPublishResult.PUBLISHED, gate.publish(event));

    ring.enabled(false);
    assertEquals(EventPublishResult.DISABLED, gate.publish(event));
  }

  @Test
  void concurrentProducersPublishUniqueEventsWithinBound() throws Exception {
    int producerCount = 4;
    int eventsPerProducer = 4_000;
    int eventCount = producerCount * eventsPerProducer;
    BoundedEventRing ring = ring(
        32_768, Severity.DEBUG, SaturationPolicy.REPORT_BACKPRESSURE);
    AtomicIntegerArray accepted = new AtomicIntegerArray(eventCount);
    AtomicIntegerArray seen = new AtomicIntegerArray(eventCount);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(producerCount)) {
      List<Future<Void>> futures = new ArrayList<>(producerCount);
      for (int producer = 0; producer < producerCount; producer++) {
        int producerId = producer;
        futures.add(executor.submit(() -> {
          DiagnosticContext context = new DiagnosticContext().sessionId(producerId);
          DiagnosticEvent event = new DiagnosticEvent();
          start.await();
          for (int offset = 0; offset < eventsPerProducer; offset++) {
            int sequence = producerId * eventsPerProducer + offset;
            event.set(EventTypeId.WAL_STALL, Severity.WARN, sequence, sequence,
                context, sequence, producerId, 0, 0);
            EventPublishResult result = ring.publish(event);
            if (result == EventPublishResult.PUBLISHED) {
              accepted.set(sequence, 1);
            } else {
              assertEquals(EventPublishResult.BACKPRESSURE, result);
            }
          }
          return null;
        }));
      }
      start.countDown();
      for (Future<Void> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }

    DiagnosticEvent target = new DiagnosticEvent();
    int consumed = 0;
    while (ring.poll(target) == EventPollResult.POLLED) {
      int sequence = (int) target.sequence();
      assertEquals(1, accepted.get(sequence));
      assertEquals(0, seen.getAndIncrement(sequence));
      consumed++;
    }

    assertEquals(consumed, ring.publishedCount());
    assertEquals(eventCount, consumed + ring.backpressureCount());
    assertEquals(0, ring.approximateSize());
  }

  @Test
  void guardedConsumerRejectsSecondThreadWithoutThrowing() throws Exception {
    BoundedEventRing ring = BoundedEventRingFactory.create(
        2,
        Severity.DEBUG,
        SaturationPolicy.DROP_AND_COUNT,
        ObservabilityBuildMode.TEST);
    DiagnosticEvent target = new DiagnosticEvent();
    assertEquals(EventPollResult.EMPTY, ring.poll(target));

    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<EventPollResult> misuse = executor.submit(() -> ring.poll(target));
      assertEquals(EventPollResult.NOT_CONSUMER, misuse.get(5, TimeUnit.SECONDS));
    }
    assertEquals(1, ring.consumerMisuseCount());
    assertEquals(EventPollResult.EMPTY, ring.poll(target));
  }

  private static void assertSaturation(
      SaturationPolicy policy,
      EventPublishResult expected,
      long drops,
      long backpressure) {
    BoundedEventRing ring = ring(2, Severity.DEBUG, policy);
    DiagnosticEvent event = new DiagnosticEvent().set(
        EventTypeId.DIAGNOSTIC_QUEUE_SATURATED,
        Severity.WARN,
        0,
        0,
        new DiagnosticContext(),
        0,
        0,
        0,
        0);
    assertEquals(EventPublishResult.PUBLISHED, ring.publish(event));
    assertEquals(EventPublishResult.PUBLISHED, ring.publish(event));
    assertEquals(expected, ring.publish(event));
    assertEquals(drops, ring.droppedCount());
    assertEquals(backpressure, ring.backpressureCount());
    assertEquals(2, ring.approximateSize());
  }

  private static BoundedEventRing ring(
      int capacity,
      Severity threshold,
      SaturationPolicy policy) {
    return BoundedEventRingFactory.create(
        capacity,
        threshold,
        policy,
        ObservabilityBuildMode.PRODUCTION);
  }
}
