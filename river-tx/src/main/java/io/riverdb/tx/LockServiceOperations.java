package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.TransactionContext;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;

/** Authenticated non-waiting LockService operation boundary. */
final class LockServiceOperations {
  private final LockManager manager;

  LockServiceOperations(LockManager owner) { manager = owner; }

  StatusCode tryAcquire(
      TransactionContext context, long generation, LockRequest request,
      long nowNanos, LockToken token, StatusDetail detail) {
    synchronized (manager) {
      if (context == null || !context.isAuthorizedBy(manager.authority, generation)
          || request == null || token == null || detail == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      detail.reset();
      if (context.cancellation().isCancellationRequested()) {
        detail.set(StatusCode.CANCELLED);
        return StatusCode.CANCELLED;
      }
      StatusCode status = manager.exact.tryAcquire(
          context.transactionId(), generation, context.transactionStartOrder(), request, token);
      detail.set(status);
      return status;
    }
  }

  StatusCode release(
      TransactionContext context, long generation, LockToken token, StatusDetail detail) {
    synchronized (manager) {
      if (detail == null || !validMutation(context, generation, token)) {
        if (detail != null) detail.set(StatusCode.INVALID_EXTERNAL_INPUT);
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      StatusCode status = manager.release(token);
      detail.set(status);
      return status;
    }
  }

  StatusCode retain(
      TransactionContext context, long generation, LockToken token, StatusDetail detail) {
    synchronized (manager) {
      if (detail == null || !validMutation(context, generation, token)) {
        if (detail != null) detail.set(StatusCode.INVALID_EXTERNAL_INPUT);
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      StatusCode status = manager.exact.retain(token);
      detail.set(status);
      return status;
    }
  }

  StatusCode holds(
      TransactionContext context, long generation, LockRequest request, StatusDetail detail) {
    synchronized (manager) {
      if (context == null || !context.isAuthorizedBy(manager.authority, generation)
          || request == null || detail == null) {
        if (detail != null) detail.set(StatusCode.INVALID_EXTERNAL_INPUT);
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      StatusCode status = manager.exact.holds(context.transactionId(), generation, request);
      detail.set(status);
      return status;
    }
  }

  StatusCode acknowledge(LockToken token, StatusDetail detail) {
    synchronized (manager) {
      if (token == null || detail == null || !token.isActive()
          || !token.isOwnedBy(manager.authority)) {
        if (detail != null) detail.set(StatusCode.NOT_OWNER);
        return StatusCode.NOT_OWNER;
      }
      StatusCode status = manager.exact.acknowledge(token);
      detail.set(status);
      return status;
    }
  }

  StatusCode consume(
      TransactionContext context, long generation, LockExecutionLane lane,
      LockWaitHandle handle, LockToken token, StatusDetail detail) {
    synchronized (manager) {
      if (detail == null || context == null
          || !context.isAuthorizedBy(manager.authority, generation)
          || token == null || token.isActive() || lane == null || handle == null
          || lane.transactionId() != context.transactionId()
          || lane.transactionGeneration() != generation
          || handle.transactionId() != context.transactionId()
          || handle.transactionGeneration() != generation) {
        if (detail != null) detail.set(StatusCode.INVALID_EXTERNAL_INPUT);
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      StatusCode status = manager.exact.consume(lane, handle, token);
      detail.set(status);
      return status;
    }
  }

  private boolean validMutation(
      TransactionContext context, long generation, LockToken token) {
    return context != null && token != null
        && context.isAuthorizedBy(manager.authority, generation)
        && token.transactionId() == context.transactionId()
        && token.transactionGeneration() == generation;
  }
}
