package io.riverdb.platform.schedule;

import io.riverdb.base.error.StatusCode;

/** Bounded scheduling boundary shared by production and deterministic test implementations. */
public interface TaskScheduler {
  /**
   * Transfers task ownership to the scheduler on {@link StatusCode#OK}. The caller retains
   * ownership on any other result.
   */
  StatusCode schedule(
      SchedulingClass schedulingClass,
      long delayNanos,
      long taskId,
      ScheduledTask task);

  int pendingTasks();

  int capacity();
}
