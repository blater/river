package io.riverdb.testkit.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultPoint;
import io.riverdb.platform.fault.FaultPointRegistry;
import io.riverdb.platform.fault.FaultPointSlot;
import io.riverdb.platform.fault.NoOpFaultInjector;
import io.riverdb.platform.schedule.SchedulingClass;
import io.riverdb.testkit.time.ManualMonotonicClock;
import org.junit.jupiter.api.Test;

final class DeterministicSchedulerTest {
  @Test
  void sameSeedProducesSameTrace() {
    ScheduleTrace first = runWorkload(0x5eedL);
    ScheduleTrace second = runWorkload(0x5eedL);

    assertTrue(first.sameDecisions(second));
    assertEquals(6, first.size());
  }

  @Test
  void reservesCapacityForCriticalWork() {
    ManualMonotonicClock clock = new ManualMonotonicClock(0);
    ScheduleTrace trace = new ScheduleTrace(4);
    DeterministicScheduler scheduler = scheduler(3, 1, 7, clock, trace);

    assertEquals(
        StatusCode.OK,
        scheduler.schedule(SchedulingClass.BEST_EFFORT, 0, 1, () -> StatusCode.OK));
    assertEquals(
        StatusCode.OK,
        scheduler.schedule(SchedulingClass.MAINTENANCE, 0, 2, () -> StatusCode.OK));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        scheduler.schedule(SchedulingClass.FOREGROUND_SQL, 0, 3, () -> StatusCode.OK));
    assertEquals(
        StatusCode.OK,
        scheduler.schedule(SchedulingClass.JOURNAL, 0, 4, () -> StatusCode.OK));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        scheduler.schedule(SchedulingClass.RECOVERY, 0, 5, () -> StatusCode.OK));
  }

  @Test
  void pausedClassRetainsOwnedTaskUntilResumed() {
    ManualMonotonicClock clock = new ManualMonotonicClock(10);
    ScheduleTrace trace = new ScheduleTrace(2);
    DeterministicScheduler scheduler = scheduler(2, 0, 19, clock, trace);
    scheduler.pause(SchedulingClass.MAINTENANCE);

    assertEquals(
        StatusCode.OK,
        scheduler.schedule(SchedulingClass.MAINTENANCE, 5, 9, () -> StatusCode.OK));
    assertEquals(StatusCode.RETRY, scheduler.runNext());
    assertEquals(1, scheduler.pendingTasks());

    scheduler.resume(SchedulingClass.MAINTENANCE);
    assertEquals(StatusCode.OK, scheduler.runNext());
    assertEquals(15, clock.nanoTime());
  }

  @Test
  void fullTraceBackpressuresWithoutExecutingOrDroppingTask() {
    ManualMonotonicClock clock = new ManualMonotonicClock(0);
    ScheduleTrace trace = new ScheduleTrace(0);
    DeterministicScheduler scheduler = scheduler(1, 0, 2, clock, trace);
    int[] executions = new int[1];
    assertEquals(
        StatusCode.OK,
        scheduler.schedule(
            SchedulingClass.FOREGROUND_SQL,
            0,
            1,
            () -> {
              executions[0]++;
              return StatusCode.OK;
            }));

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, scheduler.runNext());
    assertEquals(0, executions[0]);
    assertEquals(1, scheduler.pendingTasks());
  }

  private static ScheduleTrace runWorkload(long seed) {
    ManualMonotonicClock clock = new ManualMonotonicClock(1_000);
    ScheduleTrace trace = new ScheduleTrace(6);
    DeterministicScheduler scheduler = scheduler(6, 0, seed, clock, trace);
    for (int task = 0; task < 6; task++) {
      long taskId = task + 10L;
      assertEquals(
          StatusCode.OK,
          scheduler.schedule(
              SchedulingClass.FOREGROUND_SQL,
              25,
              taskId,
              () -> StatusCode.OK));
    }
    assertEquals(StatusCode.OK, scheduler.runUntilIdle());
    return trace;
  }

  private static DeterministicScheduler scheduler(
      int capacity,
      int reserve,
      long seed,
      ManualMonotonicClock clock,
      ScheduleTrace trace) {
    FaultPointRegistry registry = new FaultPointRegistry(1);
    FaultPointSlot slot = new FaultPointSlot();
    assertEquals(StatusCode.OK, registry.register("scheduler.before-run", slot));
    FaultPoint point = slot.value();
    return new DeterministicScheduler(
        capacity,
        reserve,
        seed,
        clock,
        trace,
        NoOpFaultInjector.INSTANCE,
        point);
  }
}
