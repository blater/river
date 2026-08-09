package io.riverdb.testkit.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultOperation;
import io.riverdb.platform.fault.FaultPoint;
import io.riverdb.platform.fault.FaultPointRegistry;
import io.riverdb.platform.fault.FaultPointSlot;
import io.riverdb.platform.fault.NoOpFaultInjector;
import io.riverdb.platform.schedule.SchedulingClass;
import io.riverdb.testkit.crash.CrashPointController;
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
    ScheduleTrace trace = new ScheduleTrace(8);
    DeterministicScheduler scheduler = scheduler(8, 1, 7, clock, trace);

    for (int task = 0; task < 4; task++) {
      assertEquals(
          StatusCode.OK,
          scheduler.schedule(
              SchedulingClass.BEST_EFFORT,
              0,
              task,
              () -> StatusCode.OK));
    }
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        scheduler.schedule(SchedulingClass.FOREGROUND_SQL, 0, 10, () -> StatusCode.OK));
    assertEquals(
        StatusCode.OK,
        scheduler.schedule(SchedulingClass.JOURNAL, 0, 11, () -> StatusCode.OK));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        scheduler.schedule(SchedulingClass.JOURNAL, 0, 12, () -> StatusCode.OK));
    assertEquals(
        StatusCode.OK,
        scheduler.schedule(SchedulingClass.RECOVERY, 0, 13, () -> StatusCode.OK));
    assertEquals(
        StatusCode.OK,
        scheduler.schedule(SchedulingClass.CONSENSUS, 0, 14, () -> StatusCode.OK));
    assertEquals(
        StatusCode.OK,
        scheduler.schedule(SchedulingClass.CHECKPOINT, 0, 15, () -> StatusCode.OK));
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

  @Test
  void scheduleCancellationHappensBeforeOwnershipTransfer() {
    FaultPointRegistry registry = new FaultPointRegistry(2);
    FaultPoint schedulePoint = point(registry, "scheduler.before-enqueue");
    FaultPoint runPoint = point(registry, "scheduler.before-run");
    CrashPointController controller = new CrashPointController(1);
    assertEquals(
        StatusCode.OK,
        controller.addRule(
            schedulePoint,
            FaultOperation.SCHEDULE,
            1,
            1,
            FaultAction.CANCEL,
            0));
    ManualMonotonicClock clock = new ManualMonotonicClock(0);
    ScheduleTrace trace = new ScheduleTrace(1);
    DeterministicScheduler scheduler = new DeterministicScheduler(
        1, 0, 4, clock, trace, controller, schedulePoint, runPoint);
    int[] executions = new int[1];

    assertEquals(
        StatusCode.CANCELLED,
        scheduler.schedule(
            SchedulingClass.FOREGROUND_SQL,
            0,
            1,
            () -> {
              executions[0]++;
              return StatusCode.OK;
            }));
    assertEquals(0, scheduler.pendingTasks());
    assertEquals(0, executions[0]);
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
    assertEquals(StatusCode.OK, scheduler.runNext());
    assertEquals(1, executions[0]);
  }

  @Test
  void executionCancellationConsumesTaskWithoutRunningIt() {
    FaultPointRegistry registry = new FaultPointRegistry(2);
    FaultPoint schedulePoint = point(registry, "scheduler.before-enqueue");
    FaultPoint runPoint = point(registry, "scheduler.before-run");
    CrashPointController controller = new CrashPointController(1);
    assertEquals(
        StatusCode.OK,
        controller.addRule(
            runPoint,
            FaultOperation.RUN_TASK,
            1,
            1,
            FaultAction.CANCEL,
            0));
    ScheduleTrace trace = new ScheduleTrace(1);
    DeterministicScheduler scheduler = new DeterministicScheduler(
        1,
        0,
        5,
        new ManualMonotonicClock(0),
        trace,
        controller,
        schedulePoint,
        runPoint);
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

    assertEquals(StatusCode.CANCELLED, scheduler.runNext());
    assertEquals(0, executions[0]);
    assertEquals(0, scheduler.pendingTasks());
    assertEquals(StatusCode.CANCELLED, trace.result(0));
  }

  @Test
  void sameSeedAndFaultScriptProduceSameExecutionTrace() {
    FaultPointRegistry registry = new FaultPointRegistry(2);
    FaultPoint schedulePoint = point(registry, "scheduler.before-enqueue");
    FaultPoint runPoint = point(registry, "scheduler.before-run");
    CrashPointController controller = new CrashPointController(1);
    assertEquals(
        StatusCode.OK,
        controller.addRule(
            runPoint,
            FaultOperation.RUN_TASK,
            2,
            1,
            FaultAction.CANCEL,
            0));

    ScheduleTrace first = runFaultedWorkload(
        77, controller, schedulePoint, runPoint);
    controller.reset();
    ScheduleTrace second = runFaultedWorkload(
        77, controller, schedulePoint, runPoint);

    assertTrue(first.sameDecisions(second));
    int cancellations = 0;
    for (int index = 0; index < first.size(); index++) {
      if (first.result(index) == StatusCode.CANCELLED) {
        cancellations++;
      }
    }
    assertEquals(1, cancellations);
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

  private static ScheduleTrace runFaultedWorkload(
      long seed,
      CrashPointController controller,
      FaultPoint schedulePoint,
      FaultPoint runPoint) {
    ManualMonotonicClock clock = new ManualMonotonicClock(100);
    ScheduleTrace trace = new ScheduleTrace(4);
    DeterministicScheduler scheduler = new DeterministicScheduler(
        4, 0, seed, clock, trace, controller, schedulePoint, runPoint);
    for (int task = 0; task < 4; task++) {
      assertEquals(
          StatusCode.OK,
          scheduler.schedule(
              SchedulingClass.FOREGROUND_SQL,
              10,
              task,
              () -> StatusCode.OK));
    }
    for (int task = 0; task < 4; task++) {
      StatusCode status = scheduler.runNext();
      assertTrue(status.isOk() || status == StatusCode.CANCELLED);
    }
    return trace;
  }

  private static DeterministicScheduler scheduler(
      int capacity,
      int reserve,
      long seed,
      ManualMonotonicClock clock,
      ScheduleTrace trace) {
    FaultPointRegistry registry = new FaultPointRegistry(2);
    FaultPoint schedulePoint = point(registry, "scheduler.before-enqueue");
    FaultPoint runPoint = point(registry, "scheduler.before-run");
    return new DeterministicScheduler(
        capacity,
        reserve,
        seed,
        clock,
        trace,
        NoOpFaultInjector.INSTANCE,
        schedulePoint,
        runPoint);
  }

  private static FaultPoint point(FaultPointRegistry registry, String name) {
    FaultPointSlot slot = new FaultPointSlot();
    assertEquals(StatusCode.OK, registry.register(name, slot));
    return slot.value();
  }
}
