package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;

/** Resolves prepared catalog state from the authoritative outer transaction outcome. */
final class RelationalPreparedDescriptorOutcome {
  private TransactionState terminal = TransactionState.ACTIVE;
  private boolean builds;
  private boolean publication;

  StatusCode finish(
      RelationalPreparedDescriptors prepared,
      IndexedTransactionSession session,
      TransactionOutcome outcome,
      StatusCode transactionStatus,
      boolean publishedDrop) {
    terminal = outcome.isAvailable()
        ? outcome.state() : TransactionState.INDETERMINATE;
    builds = prepared.hasActive();
    publication = prepared.hasVisible() || publishedDrop;
    if (session.transaction().isActiveHandle() || !builds) return transactionStatus;
    StatusCode finalized = prepared.finish(terminal);
    return transactionStatus.isOk() ? finalized : transactionStatus;
  }

  boolean committed() { return terminal == TransactionState.COMMITTED; }

  boolean determinate() { return terminal != TransactionState.INDETERMINATE; }

  boolean publishSchemaChange() {
    return committed() && (publication || !builds);
  }
}
