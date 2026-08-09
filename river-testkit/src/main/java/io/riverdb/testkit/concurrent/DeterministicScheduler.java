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
 * Fixed-capacity, single-event-loop scheduler. The construction thread owns every state-changing
 * operation; foreign-thread calls return {@code NOT_OWNER}. Task callbacks execute outside the
 * scheduler monitor, may enqueue more work, and cannot recursively drive the event loop. Ready
 * critical classes advance in deterministic round-robin order while tasks within a class use the
 * seed-derived rank.
 */
public final class DeterministicScheduler implements TaskScheduler {
  private static final SchedulingClass[] SCHEDULING_CLASSES = SchedulingClass.values();

  private final ManualMonotonicClock clock;
  private final ScheduleTrace trace;
  private final FaultInjector faultInjector;
  private final FaultPoint schedulePoint;
  private final FaultPoint runTaskPoint;
  private final Thread eventLoopThread;
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
  private int nextCriticalClassOrdinal;
  private boolean runningTask;

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
    eventLoopThread = Thread.currentThread();
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
    StatusCode ownershipStatus = checkEventLoopOwner();
    if (!ownershipStatus.isOk()) {
      return ownershipStatus;
    }
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
  public StatusCode runNext() {
    ScheduledTask selectedTask;
    SchedulingClass selectedClass;
    long selectedTaskId;
    long runAtNanos;
    synchronized (this) {
      StatusCode ownershipStatus = checkEventLoopOwner();
      if (!ownershipStatus.isOk()) {
        return ownershipStatus;
      }
      if (runningTask) {
        return StatusCode.CONFLICT;
      }
      int selected = selectNext();
      if (selected < 0) {
        return StatusCode.RETRY;
      }
      if (!trace.hasCapacity()) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      long deadline = deadlines[selected];
      runAtNanos = Math.max(deadline, clock.nanoTime());
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
      selectedTask = tasks[selected];
      selectedClass = classes[selected];
      selectedTaskId = taskIds[selected];
      advanceCriticalCursor(selectedClass);
      remove(selected);
      StatusCode injectedStatus = faultStatus(action);
      if (!injectedStatus.isOk()) {
        StatusCode traceStatus = trace.append(
            selectedTaskId, runAtNanos, selectedClass, injectedStatus);
        return traceStatus.isOk() ? injectedStatus : traceStatus;
      }
      runningTask = true;
    }

    StatusCode taskStatus;
    try {
      taskStatus = selectedTask.run();
    } finally {
      synchronized (this) {
        runningTask = false;
      }
    }
    synchronized (this) {
      StatusCode traceStatus = trace.append(
          selectedTaskId, runAtNanos, selectedClass, taskStatus);
      return traceStatus.isOk() ? taskStatus : traceStatus;
    }
  }

  /** Runs until no unpaused work remains or a task/trace returns a non-OK status. */
  public StatusCode runUntilIdle() {
    synchronized (this) {
      StatusCode ownershipStatus = checkEventLoopOwner();
      if (!ownershipStatus.isOk()) {
        return ownershipStatus;
      }
      if (runningTask) {
        return StatusCode.CONFLICT;
      }
    }
    while (hasRunnableTask()) {
      StatusCode status = runNext();
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  public synchronized StatusCode pause(SchedulingClass schedulingClass) {
    StatusCode ownershipStatus = checkEventLoopOwner();
    if (!ownershipStatus.isOk()) {
      return ownershipStatus;
    }
    pausedClasses[schedulingClass.ordinal()] = true;
    return StatusCode.OK;
  }

  public synchronized StatusCode resume(SchedulingClass schedulingClass) {
    StatusCode ownershipStatus = checkEventLoopOwner();
    if (!ownershipStatus.isOk()) {
      return ownershipStatus;
    }
    pausedClasses[schedulingClass.ordinal()] = false;
    return StatusCode.OK;
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
    long firstDeadline = Long.MAX_VALUE;
    boolean found = false;
    for (int index = 0; index < size; index++) {
      if (!pausedClasses[classes[index].ordinal()]) {
        firstDeadline = Math.min(firstDeadline, deadlines[index]);
        found = true;
      }
    }
    if (!found) {
      return -1;
    }
    long readyAtNanos = Math.max(clock.nanoTime(), firstDeadline);
    int critical = selectReadyCritical(readyAtNanos);
    if (critical >= 0) {
      return critical;
    }
    int selected = -1;
    for (int index = 0; index < size; index++) {
      if (pausedClasses[classes[index].ordinal()] || deadlines[index] > readyAtNanos) {
        continue;
      }
      if (selected < 0 || precedes(index, selected)) {
        selected = index;
      }
    }
    return selected;
  }

  private int selectReadyCritical(long readyAtNanos) {
    for (int step = 0; step < SCHEDULING_CLASSES.length; step++) {
      int classOrdinal = (nextCriticalClassOrdinal + step) % SCHEDULING_CLASSES.length;
      SchedulingClass schedulingClass = SCHEDULING_CLASSES[classOrdinal];
      if (!schedulingClass.isCritical() || pausedClasses[classOrdinal]) {
        continue;
      }
      int selected = -1;
      for (int index = 0; index < size; index++) {
        if (classes[index] != schedulingClass || deadlines[index] > readyAtNanos) {
          continue;
        }
        if (selected < 0 || precedes(index, selected)) {
          selected = index;
        }
      }
      if (selected >= 0) {
        return selected;
      }
    }
    return -1;
  }

  private synchronized boolean hasRunnableTask() {
    return selectNext() >= 0;
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

  private void advanceCriticalCursor(SchedulingClass selectedClass) {
    if (selectedClass.isCritical()) {
      nextCriticalClassOrdinal = (selectedClass.ordinal() + 1) % SCHEDULING_CLASSES.length;
    }
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
      case CANCEL -> StatusCode.CANCELLED;
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

  private StatusCode checkEventLoopOwner() {
    return Thread.currentThread() == eventLoopThread
        ? StatusCode.OK
        : StatusCode.NOT_OWNER;
  }

  private static long mix(long value) {
    value ^= value >>> 30;
    value *= 0xbf58476d1ce4e5b9L;
    value ^= value >>> 27;
    value *= 0x94d049bb133111ebL;
    return value ^ (value >>> 31);
  }
}
