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
 * Single-threaded, fixed-capacity scheduler. Equal deadline/class work is selected by a stable
 * seed-derived rank, allowing exact replay while different seeds explore different interleavings.
 */
public final class DeterministicScheduler implements TaskScheduler {
  private final ManualMonotonicClock clock;
  private final ScheduleTrace trace;
  private final FaultInjector faultInjector;
  private final FaultPoint runTaskPoint;
  private final long seed;
  private final int criticalReserve;
  private final ScheduledTask[] tasks;
  private final SchedulingClass[] classes;
  private final long[] taskIds;
  private final long[] deadlines;
  private final long[] ranks;
  private final long[] attempts;
  private final boolean[] pausedClasses;
  private final FaultDecision faultDecision = new FaultDecision();
  private long sequence;
  private int size;
  private int nonCriticalSize;

  public DeterministicScheduler(
      int capacity,
      int criticalReserve,
      long seed,
      ManualMonotonicClock clock,
      ScheduleTrace trace,
      FaultInjector faultInjector,
      FaultPoint runTaskPoint) {
    this.clock = clock;
    this.trace = trace;
    this.faultInjector = faultInjector;
    this.runTaskPoint = runTaskPoint;
    this.seed = seed;
    this.criticalReserve = criticalReserve;
    tasks = new ScheduledTask[capacity];
    classes = new SchedulingClass[capacity];
    taskIds = new long[capacity];
    deadlines = new long[capacity];
    ranks = new long[capacity];
    attempts = new long[capacity];
    pausedClasses = new boolean[SchedulingClass.values().length];
  }

  @Override
  public StatusCode schedule(
      SchedulingClass schedulingClass,
      long delayNanos,
      long taskId,
      ScheduledTask task) {
    if (delayNanos < 0 || Long.MAX_VALUE - clock.nanoTime() < delayNanos) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (size == tasks.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (!schedulingClass.isCritical()
        && nonCriticalSize == tasks.length - criticalReserve) {
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
    if (!schedulingClass.isCritical()) {
      nonCriticalSize++;
    }
    return StatusCode.OK;
  }

  /** Executes one selected task, advancing the manual clock to its deadline. */
  public StatusCode runNext() {
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
    StatusCode taskStatus;
    if (action == FaultAction.CANCEL || action == FaultAction.RESTART) {
      taskStatus = StatusCode.CANCELLED;
    } else if (action == FaultAction.CRASH) {
      taskStatus = StatusCode.IO_FAILURE;
    } else {
      taskStatus = tasks[selected].run();
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
  public StatusCode runUntilIdle() {
    while (selectNext() >= 0) {
      StatusCode status = runNext();
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  public void pause(SchedulingClass schedulingClass) {
    pausedClasses[schedulingClass.ordinal()] = true;
  }

  public void resume(SchedulingClass schedulingClass) {
    pausedClasses[schedulingClass.ordinal()] = false;
  }

  @Override
  public int pendingTasks() {
    return size;
  }

  @Override
  public int capacity() {
    return tasks.length;
  }

  public int criticalReserve() {
    return criticalReserve;
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
    if (!classes[selected].isCritical()) {
      nonCriticalSize--;
    }
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

  private static long mix(long value) {
    value ^= value >>> 30;
    value *= 0xbf58476d1ce4e5b9L;
    value ^= value >>> 27;
    value *= 0x94d049bb133111ebL;
    return value ^ (value >>> 31);
  }
}
