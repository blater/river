package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.TransactionContext;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockDeadline;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockWaitHandle;
import io.riverdb.tx.api.lock.LockWaitState;
import java.util.concurrent.locks.LockSupport;

/** Authenticated enqueue, park, timeout, and cancellation boundary. */
final class LockServiceWaits {
  private final LockManager manager;

  LockServiceWaits(LockManager owner) { manager = owner; }

  StatusCode enqueue(
      TransactionContext context, long generation, long laneId, long laneGeneration,
      LockRequest request, long nowNanos, LockExecutionLane lane,
      LockWaitHandle handle, StatusDetail detail) {
    synchronized (manager) {
      if (context == null || !context.isAuthorizedBy(manager.authority, generation)
          || request == null || lane == null || handle == null || detail == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      detail.reset();
      StatusCode status = context.cancellation().isCancellationRequested()
          ? StatusCode.CANCELLED
          : request.hasDeadline() && LockDeadline.expired(request.deadlineNanos(), nowNanos)
              ? StatusCode.TIMEOUT
          : manager.exact.enqueue(context.transactionId(), generation,
              context.transactionStartOrder(), laneId, laneGeneration,
              request, lane, handle);
      detail.set(status);
      return status;
    }
  }

  StatusCode await(
      LockExecutionLane lane, LockWaitHandle handle, StatusDetail detail) {
    if (lane == null || handle == null || detail == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean restoreInterrupt = false;
    try {
      while (true) {
        long remaining;
        synchronized (manager) {
          LockWaitState state = handle.state();
          if (state == LockWaitState.GRANTED) {
            return detail(manager.exact.validateGranted(lane, handle), detail);
          }
          if (state != LockWaitState.QUEUED) {
            return detail(manager.exact.acknowledge(lane, handle), detail);
          }
          StatusCode armed = manager.exact.arm(lane, handle, Thread.currentThread());
          if (!armed.isOk()) return detail(armed, detail);
          remaining = manager.exact.remainingNanos(lane, handle, System.nanoTime());
          if (remaining == 0) {
            StatusCode status = manager.exact.cancel(lane, handle, StatusCode.TIMEOUT);
            if (status != StatusCode.RETRY) {
              return detail(manager.exact.acknowledge(lane, handle), detail);
            }
            remaining = -1;
          }
        }
        if (Thread.interrupted()) {
          restoreInterrupt = true;
          StatusCode interrupted = interrupt(lane, handle, detail);
          if (interrupted != StatusCode.RETRY) return interrupted;
          continue;
        }
        if (remaining < 0) LockSupport.park(handle);
        else LockSupport.parkNanos(handle, remaining);
      }
    } finally {
      if (restoreInterrupt) Thread.currentThread().interrupt();
    }
  }

  private StatusCode interrupt(
      LockExecutionLane lane, LockWaitHandle handle, StatusDetail detail) {
    synchronized (manager) {
      LockWaitState state = handle.state();
      if (state != LockWaitState.QUEUED && state != LockWaitState.GRANTED) {
        return detail(manager.exact.acknowledge(lane, handle), detail);
      }
      StatusCode status = manager.exact.cancel(lane, handle, StatusCode.CANCELLED);
      if (status == StatusCode.RETRY) {
        state = handle.state();
        if (state != LockWaitState.QUEUED && state != LockWaitState.GRANTED) {
          return detail(manager.exact.acknowledge(lane, handle), detail);
        }
        return StatusCode.RETRY;
      }
      return detail(manager.exact.acknowledge(lane, handle), detail);
    }
  }

  StatusCode cancel(
      LockExecutionLane lane, LockWaitHandle handle, StatusDetail detail) {
    synchronized (manager) {
      if (detail == null) return StatusCode.INVALID_EXTERNAL_INPUT;
      return detail(manager.exact.cancel(lane, handle, StatusCode.CANCELLED), detail);
    }
  }

  private static StatusCode detail(StatusCode status, StatusDetail detail) {
    detail.set(status);
    return status;
  }
}
