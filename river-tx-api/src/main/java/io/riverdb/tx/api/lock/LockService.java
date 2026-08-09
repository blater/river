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
      LockRequest request,
      long nowNanos,
      LockToken token,
      StatusDetail detail);

  StatusCode release(LockToken token, StatusDetail detail);
}
