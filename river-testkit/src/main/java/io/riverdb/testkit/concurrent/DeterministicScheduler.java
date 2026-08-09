package io.riverdb.testkit.concurrent;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultDecision;
import io.riverdb.platform.fault.FaultInjector;
import io.riverdb.platform.fault.FaultOperation;
import io.riverdb.platform.fault.FaultPoint;
import io.riverdb.platform.schedule.ScheduledTask;
import io.riverdb.platform.schedule.SchedulingClass;
import io.riverdb.platform.schedule.TaskScheduler;
import io.riverdb.testkit.time.ManualMonotonicClock;

/**
 * Fixed-capacity scheduler. Equal deadline/class work is selected by a stable seed-derived rank,
 * allowing exact replay while different seeds explore different interleavings. Public operations
 * serialize access; tasks run while the scheduler lock is held so concurrent callers cannot alter
 * a decision between selection and trace publication.
 */
public final class DeterministicScheduler implements TaskScheduler {
  private static final SchedulingClass[] SCHEDULING_CLASSES = SchedulingClass.values();

  private final ManualMonotonicClock clock;
  private final ScheduleTrace trace;
  private final FaultInjector faultInjector;
  private final FaultPoint schedulePoint;
  private final FaultPoint runTaskPoint;
  private final long seed;
  private final int criticalReservePerClass;
  private final StatusCode configurationStatus;
  private final ScheduledTask[] tasks;
  private final SchedulingClass[] classes;
  private final long[] taskIds;
  private final long[] deadlines;
  private final long[] ranks;
  private final long[] attempts;
  private final int[] pendingByClass;
  private final boolean[] pausedClasses;
  private final FaultDecision faultDecision = new FaultDecision();
  private long sequence;
  private long scheduleAttempts;
  private int size;

  /**
   * Creates a scheduler with a protected queue reservation for every critical scheduling class.
   * If total reservations exceed capacity, scheduling fails with {@code INVALID_EXTERNAL_INPUT}
   * without accepting task ownership.
   */
  public DeterministicScheduler(
      int capacity,
      int criticalReservePerClass,
      long seed,
      ManualMonotonicClock clock,
      ScheduleTrace trace,
      FaultInjector faultInjector,
      FaultPoint schedulePoint,
      FaultPoint runTaskPoint) {
    this.clock = clock;
    this.trace = trace;
    this.faultInjector = faultInjector;
    this.schedulePoint = schedulePoint;
    this.runTaskPoint = runTaskPoint;
    this.seed = seed;
    this.criticalReservePerClass = criticalReservePerClass;
    long totalCriticalReserve = (long) criticalClassCount() * criticalReservePerClass;
    configurationStatus = capacity < 0
            || criticalReservePerClass < 0
            || totalCriticalReserve > capacity
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : StatusCode.OK;
    int boundedCapacity = Math.max(0, capacity);
    tasks = new ScheduledTask[boundedCapacity];
    classes = new SchedulingClass[boundedCapacity];
    taskIds = new long[boundedCapacity];
    deadlines = new long[boundedCapacity];
    ranks = new long[boundedCapacity];
    attempts = new long[boundedCapacity];
    pendingByClass = new int[SCHEDULING_CLASSES.length];
    pausedClasses = new boolean[SCHEDULING_CLASSES.length];
  }

  @Override
  public synchronized StatusCode schedule(
      SchedulingClass schedulingClass,
      long delayNanos,
      long taskId,
      ScheduledTask task) {
    if (!configurationStatus.isOk()) {
      return configurationStatus;
    }
    if (delayNanos < 0 || Long.MAX_VALUE - clock.nanoTime() < delayNanos) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    faultInjector.evaluate(
        schedulePoint,
        FaultOperation.SCHEDULE,
        ++scheduleAttempts,
        clock.nanoTime(),
        0,
        faultDecision);
    FaultAction scheduleAction = faultDecision.action();
    if (!scheduleAction.isCompatibleWith(FaultOperation.SCHEDULE)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    StatusCode scheduleFaultStatus = faultStatus(scheduleAction);
    if (!scheduleFaultStatus.isOk()) {
      return scheduleFaultStatus;
    }
    if (size == tasks.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (!preservesCriticalReservations(schedulingClass)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long insertionSequence = sequence++;
    tasks[size] = task;
    classes[size] = schedulingClass;
    taskIds[size] = taskId;
    deadlines[size] = clock.nanoTime() + delayNanos;
    ranks[size] = mix(seed ^ taskId ^ insertionSequence);
    attempts[size] = 0;
    size++;
    pendingByClass[schedulingClass.ordinal()]++;
    return StatusCode.OK;
  }

  /** Executes one selected task, advancing the manual clock to its deadline. */
  public synchronized StatusCode runNext() {
    int selected = selectNext();
    if (selected < 0) {
      return StatusCode.RETRY;
    }
    if (!trace.hasCapacity()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long deadline = deadlines[selected];
    long runAtNanos = Math.max(deadline, clock.nanoTime());
    StatusCode clockStatus = clock.advanceTo(runAtNanos);
    if (!clockStatus.isOk()) {
      return clockStatus;
    }
    faultInjector.evaluate(
        runTaskPoint,
        FaultOperation.RUN_TASK,
        ++attempts[selected],
        runAtNanos,
        0,
        faultDecision);
    FaultAction action = faultDecision.action();
    if (!action.isCompatibleWith(FaultOperation.RUN_TASK)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    StatusCode taskStatus;
    StatusCode faultStatus = faultStatus(action);
    if (faultStatus.isOk()) {
      taskStatus = tasks[selected].run();
    } else {
      taskStatus = faultStatus;
    }
    StatusCode traceStatus = trace.append(
        taskIds[selected], runAtNanos, classes[selected], taskStatus);
    if (!traceStatus.isOk()) {
      return traceStatus;
    }
    remove(selected);
    return taskStatus;
  }

  /** Runs until no unpaused work remains or a task/trace returns a non-OK status. */
  public synchronized StatusCode runUntilIdle() {
    while (selectNext() >= 0) {
      StatusCode status = runNext();
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  public synchronized void pause(SchedulingClass schedulingClass) {
    pausedClasses[schedulingClass.ordinal()] = true;
  }

  public synchronized void resume(SchedulingClass schedulingClass) {
    pausedClasses[schedulingClass.ordinal()] = false;
  }

  @Override
  public synchronized int pendingTasks() {
    return size;
  }

  @Override
  public synchronized int capacity() {
    return tasks.length;
  }

  public int criticalReservePerClass() {
    return criticalReservePerClass;
  }

  private int selectNext() {
    int selected = -1;
    for (int index = 0; index < size; index++) {
      if (pausedClasses[classes[index].ordinal()]) {
        continue;
      }
      if (selected < 0 || precedes(index, selected)) {
        selected = index;
      }
    }
    return selected;
  }

  private boolean precedes(int left, int right) {
    if (deadlines[left] != deadlines[right]) {
      return deadlines[left] < deadlines[right];
    }
    if (classes[left].priority() != classes[right].priority()) {
      return classes[left].priority() < classes[right].priority();
    }
    int rankComparison = Long.compareUnsigned(ranks[left], ranks[right]);
    if (rankComparison != 0) {
      return rankComparison < 0;
    }
    return Long.compareUnsigned(taskIds[left], taskIds[right]) < 0;
  }

  private void remove(int selected) {
    pendingByClass[classes[selected].ordinal()]--;
    int last = --size;
    tasks[selected] = tasks[last];
    classes[selected] = classes[last];
    taskIds[selected] = taskIds[last];
    deadlines[selected] = deadlines[last];
    ranks[selected] = ranks[last];
    attempts[selected] = attempts[last];
    tasks[last] = null;
    classes[last] = null;
  }

  private boolean preservesCriticalReservations(SchedulingClass admittedClass) {
    int missingAfterAdmission = 0;
    for (SchedulingClass schedulingClass : SCHEDULING_CLASSES) {
      if (!schedulingClass.isCritical()) {
        continue;
      }
      int pending = pendingByClass[schedulingClass.ordinal()];
      if (schedulingClass == admittedClass) {
        pending++;
      }
      missingAfterAdmission += Math.max(0, criticalReservePerClass - pending);
    }
    return tasks.length - (size + 1) >= missingAfterAdmission;
  }

  private static StatusCode faultStatus(FaultAction action) {
    return switch (action) {
      case NONE -> StatusCode.OK;
      case CANCEL, RESTART -> StatusCode.CANCELLED;
      case CRASH -> StatusCode.IO_FAILURE;
      default -> StatusCode.INVARIANT_BROKEN;
    };
  }

  private static int criticalClassCount() {
    int count = 0;
    for (SchedulingClass schedulingClass : SCHEDULING_CLASSES) {
      if (schedulingClass.isCritical()) {
        count++;
      }
    }
    return count;
  }

  private static long mix(long value) {
    value ^= value >>> 30;
    value *= 0xbf58476d1ce4e5b9L;
    value ^= value >>> 27;
    value *= 0x94d049bb133111ebL;
    return value ^ (value >>> 31);
  }
}
