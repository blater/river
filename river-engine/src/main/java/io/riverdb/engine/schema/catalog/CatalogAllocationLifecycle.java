package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.IsolationLevel;

/** Initializes and validates the durable catalog allocation authority. */
final class CatalogAllocationLifecycle {
  private final CatalogTransactions transactions;
  private final CatalogIdAllocator allocator;
  private final CatalogSessionResult opened = new CatalogSessionResult();

  CatalogAllocationLifecycle(CatalogTransactions flow, CatalogIdAllocator idAllocator) {
    transactions = flow;
    allocator = idAllocator;
  }

  StatusCode initialize() {
    return run(IsolationLevel.SERIALIZABLE, true);
  }

  StatusCode validate() {
    return run(IsolationLevel.REPEATABLE_READ, false);
  }

  private StatusCode run(IsolationLevel isolation, boolean initialize) {
    StatusCode status = transactions.open(opened);
    if (!status.isOk()) return status;
    IndexedTransactionSession session = opened.session();
    status = session.begin(isolation);
    if (status.isOk()) {
      status = initialize ? allocator.initialize(session) : allocator.validate(session);
    }
    return transactions.finish(session, status, initialize);
  }
}
