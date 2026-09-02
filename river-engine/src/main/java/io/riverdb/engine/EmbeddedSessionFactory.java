package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedGroupCommitCoordinator;
import io.riverdb.engine.table.IndexedTable;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedVacuum;
import io.riverdb.engine.table.IndexedSessionRegistry;
import io.riverdb.tx.TransactionManager;
import io.riverdb.engine.runtime.DatabaseResourceGovernor;

/** Constructs one unpublished session and translates bounded construction pressure. */
final class EmbeddedSessionFactory {
  private EmbeddedSessionFactory() {}

  static StatusCode create(
      TransactionManager transactions,
      IndexedTable table,
      int maximumRowBytes,
      IndexedGroupCommitCoordinator groupCommit,
      IndexedVacuum vacuum,
      DatabaseResourceGovernor resourceGovernor,
      IndexedSessionRegistry sessions,
      EmbeddedSessionOpenResult result) {
    IndexedTransactionSession session;
    try {
      session = new IndexedTransactionSession(
          transactions, table, maximumRowBytes, groupCommit, vacuum,
          resourceGovernor, sessions);
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = sessions.register(session);
    if (!status.isOk()) return status;
    result.set(session);
    return StatusCode.OK;
  }
}
