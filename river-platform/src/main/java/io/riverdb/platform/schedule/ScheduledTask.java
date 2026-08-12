package io.riverdb.platform.schedule;

import io.riverdb.base.error.StatusCode;

/** A scheduler-owned task. Implementations return expected failures as statuses. */
@FunctionalInterface
public interface ScheduledTask {
  StatusCode run();
}
