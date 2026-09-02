package io.riverdb.tx.api.lock;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.TransactionContext;

/** Bounded logical lock contract used by row, index-range, and schema access paths. */
public interface LockService {
  /**
   * Attempts acquisition without blocking the calling thread. Contention returns {@code RETRY};
   * exhausted provider capacity returns {@code RESOURCE_EXHAUSTED}.
   */
  StatusCode tryAcquire(
      TransactionContext context,
      long expectedTransactionGeneration,
      LockRequest request,
      long nowNanos,
      LockToken token,
      StatusDetail detail);

  StatusCode release(
      TransactionContext context,
      long expectedTransactionGeneration,
      LockToken token,
      StatusDetail detail);

  /**
   * Converts one acquired token into transaction-lifetime ownership. Repeated retention of the
   * same canonical holding coalesces rather than accumulating references.
   */
  StatusCode retain(
      TransactionContext context,
      long expectedTransactionGeneration,
      LockToken token,
      StatusDetail detail);

  /** Tests an exact canonical resource identity without allocating or scanning transaction state. */
  StatusCode holds(
      TransactionContext context,
      long expectedTransactionGeneration,
      LockRequest request,
      StatusDetail detail);

  /** Completes a stale terminal token carrier without releasing a live holding. */
  StatusCode acknowledge(LockToken token, StatusDetail detail);

  StatusCode enqueue(
      TransactionContext context,
      long expectedTransactionGeneration,
      long laneId,
      long laneGeneration,
      LockRequest request,
      long nowNanos,
      LockExecutionLane lane,
      LockWaitHandle handle,
      StatusDetail detail);

  StatusCode await(
      LockExecutionLane lane, LockWaitHandle handle, StatusDetail detail);

  StatusCode consume(
      TransactionContext context,
      long expectedTransactionGeneration,
      LockExecutionLane lane,
      LockWaitHandle handle,
      LockToken token,
      StatusDetail detail);

  StatusCode cancel(LockExecutionLane lane, LockWaitHandle handle, StatusDetail detail);

}
