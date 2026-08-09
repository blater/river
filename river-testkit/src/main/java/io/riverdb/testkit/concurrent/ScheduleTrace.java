package io.riverdb.testkit.concurrent;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.schedule.SchedulingClass;

/** Fixed-capacity primitive trace of deterministic scheduler decisions. */
public final class ScheduleTrace {
  private static final Object COMPARISON_TIE_LOCK = new Object();

  private final long[] taskIds;
  private final long[] runAtNanos;
  private final SchedulingClass[] classes;
  private final StatusCode[] results;
  private int size;

  public ScheduleTrace(int capacity) {
    taskIds = new long[capacity];
    runAtNanos = new long[capacity];
    classes = new SchedulingClass[capacity];
    results = new StatusCode[capacity];
  }

  synchronized StatusCode append(
      long taskId,
      long runAtNanos,
      SchedulingClass schedulingClass,
      StatusCode result) {
    if (size == taskIds.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    taskIds[size] = taskId;
    this.runAtNanos[size] = runAtNanos;
    classes[size] = schedulingClass;
    results[size] = result;
    size++;
    return StatusCode.OK;
  }

  public synchronized int size() {
    return size;
  }

  public synchronized int capacity() {
    return taskIds.length;
  }

  synchronized boolean hasCapacity() {
    return size < taskIds.length;
  }

  public synchronized long taskId(int index) {
    return taskIds[index];
  }

  public synchronized long runAtNanos(int index) {
    return runAtNanos[index];
  }

  public synchronized SchedulingClass schedulingClass(int index) {
    return classes[index];
  }

  public synchronized StatusCode result(int index) {
    return results[index];
  }

  public synchronized void reset() {
    size = 0;
  }

  public boolean sameDecisions(ScheduleTrace other) {
    if (this == other) {
      return true;
    }
    int thisIdentity = System.identityHashCode(this);
    int otherIdentity = System.identityHashCode(other);
    if (thisIdentity < otherIdentity) {
      return compareLocked(this, other);
    }
    if (thisIdentity > otherIdentity) {
      return compareLocked(other, this);
    }
    synchronized (COMPARISON_TIE_LOCK) {
      return compareLocked(this, other);
    }
  }

  private static boolean compareLocked(ScheduleTrace first, ScheduleTrace second) {
    synchronized (first) {
      synchronized (second) {
        return first.sameDecisionsUnlocked(second);
      }
    }
  }

  private boolean sameDecisionsUnlocked(ScheduleTrace other) {
    if (size != other.size) {
      return false;
    }
    for (int index = 0; index < size; index++) {
      if (taskIds[index] != other.taskIds[index]
          || runAtNanos[index] != other.runAtNanos[index]
          || classes[index] != other.classes[index]
          || results[index] != other.results[index]) {
        return false;
      }
    }
    return true;
  }
}
