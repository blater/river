package io.riverdb.observability.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.sun.management.ThreadMXBean;
import io.riverdb.observability.api.event.DiagnosticContext;
import io.riverdb.observability.api.event.DiagnosticEvent;
import io.riverdb.observability.api.event.EventPublishResult;
import io.riverdb.observability.api.event.EventTypeId;
import io.riverdb.observability.api.event.Severity;
import io.riverdb.observability.api.metric.MetricName;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class NoOpObservabilityTest {
  private static volatile long allocationGuard;

  @Test
  void allDisabledViewsReuseOneSingleton() {
    assertSame(NoOpObservability.instance(), NoOpObservability.diagnosticSink());
    assertSame(NoOpObservability.instance(), NoOpObservability.kernelEventSink());
    assertSame(NoOpObservability.instance(), NoOpObservability.metricsSink());
    assertFalse(NoOpObservability.instance().isEnabled(Severity.FATAL));
    assertFalse(NoOpObservability.instance().isEnabled(MetricName.DIAGNOSTIC_QUEUE_DEPTH));
  }

  @Test
  void warmedDisabledPathDoesNotAllocatePerCall() {
    java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standardBean instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standardBean;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);

    NoOpObservability sink = NoOpObservability.instance();
    DiagnosticContext context = new DiagnosticContext().databaseId(7);
    DiagnosticEvent event = new DiagnosticEvent().set(
        EventTypeId.DATABASE_STARTED, Severity.INFO, 1, 2, context, 3, 4, 5, 6);
    for (int index = 0; index < 100_000; index++) {
      allocationGuard += sink.publish(event).ordinal();
      sink.addCounter(MetricName.DIAGNOSTIC_EVENTS_DROPPED_TOTAL, 1);
    }

    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 1_000_000; index++) {
      allocationGuard += sink.publish(event).ordinal();
      sink.addCounter(MetricName.DIAGNOSTIC_EVENTS_DROPPED_TOTAL, 1);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertEquals(EventPublishResult.DISABLED, sink.publish(event));
    assertEquals(0, allocated, "warmed no-op calls allocated bytes");
  }
}
