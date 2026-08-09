package io.riverdb.observability.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.observability.api.event.BoundedEventRing;
import io.riverdb.observability.api.event.DiagnosticContext;
import io.riverdb.observability.api.event.DiagnosticEvent;
import io.riverdb.observability.api.event.EventPollResult;
import io.riverdb.observability.api.event.EventTypeId;
import io.riverdb.observability.api.event.SaturationPolicy;
import io.riverdb.observability.api.event.Severity;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ActiveObservabilityAllocationTest {
  private static volatile long allocationGuard;

  @Test
  void warmedPublishPollAndSlotReuseDoNotAllocate() {
    ThreadMXBean bean = allocationBean();
    BoundedEventRing ring = new BoundedEventRing(
        2, Severity.DEBUG, SaturationPolicy.DROP_AND_COUNT);
    DiagnosticContext context = new DiagnosticContext().databaseId(1);
    DiagnosticEvent event = new DiagnosticEvent().set(
        EventTypeId.WAL_STALL, Severity.WARN, 2, 3, context, 4, 5, 6, 7);
    DiagnosticEvent target = new DiagnosticEvent();

    for (int index = 0; index < 100_000; index++) {
      allocationGuard += ring.publish(event).ordinal();
      allocationGuard += ring.poll(target).ordinal();
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 1_000_000; index++) {
      allocationGuard += ring.publish(event).ordinal();
      allocationGuard += ring.poll(target).ordinal();
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertEquals(EventPollResult.EMPTY, ring.poll(target));
    assertTrue(
        allocated <= 256,
        "warmed active publish/poll calls allocated more than measurement noise: " + allocated);
  }

  @Test
  void warmedSaturationAccountingDoesNotAllocate() {
    ThreadMXBean bean = allocationBean();
    BoundedEventRing ring = new BoundedEventRing(
        2, Severity.DEBUG, SaturationPolicy.DROP_AND_COUNT);
    DiagnosticEvent event = new DiagnosticEvent().set(
        EventTypeId.WAL_STALL,
        Severity.WARN,
        1,
        1,
        new DiagnosticContext(),
        0,
        0,
        0,
        0);
    ring.publish(event);
    ring.publish(event);

    for (int index = 0; index < 100_000; index++) {
      allocationGuard += ring.publish(event).ordinal();
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 1_000_000; index++) {
      allocationGuard += ring.publish(event).ordinal();
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertEquals(1_100_000, ring.droppedCount());
    assertTrue(
        allocated <= 256,
        "warmed saturation accounting allocated more than measurement noise: " + allocated);
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standardBean instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standardBean;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    return bean;
  }
}
