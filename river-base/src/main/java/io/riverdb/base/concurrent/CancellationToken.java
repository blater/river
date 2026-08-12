package io.riverdb.base.concurrent;

import io.riverdb.base.error.StatusCode;

/** Cooperative cancellation checked at bounded execution and wait points. */
@FunctionalInterface
public interface CancellationToken {
  CancellationToken NONE = () -> false;

  boolean isCancellationRequested();

  default StatusCode status() {
    return isCancellationRequested() ? StatusCode.CANCELLED : StatusCode.OK;
  }
}
