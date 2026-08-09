package io.riverdb.testkit.concurrent;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.schedule.SchedulingClass;

/** Fixed-capacity primitive trace of deterministic scheduler decisions. */
public final class ScheduleTrace {
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

  StatusCode append(
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

  public int size() {
    return size;
  }

  public int capacity() {
    return taskIds.length;
  }

  boolean hasCapacity() {
    return size < taskIds.length;
  }

  public long taskId(int index) {
    return taskIds[index];
  }

  public long runAtNanos(int index) {
    return runAtNanos[index];
  }

  public SchedulingClass schedulingClass(int index) {
    return classes[index];
  }

  public StatusCode result(int index) {
    return results[index];
  }

  public void reset() {
    size = 0;
  }

  public boolean sameDecisions(ScheduleTrace other) {
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
