package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogObjectHeadCodec;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionState;

/** Owns direct-create publication state that cannot be returned to an API caller. */
final class CatalogDirectTableCreation {
  private final CatalogTransactions transactions;
  private final CatalogPreparedTableCompletion completion;
  private final CatalogSessionResult opened = new CatalogSessionResult();
  private final CatalogDirectCreationState state = new CatalogDirectCreationState();
  private final int[] publicationRows = {CatalogObjectHeadCodec.BYTES};

  CatalogDirectTableCreation(
      CatalogTransactions flow, CatalogPreparedTableCompletion tableCompletion) {
    transactions = flow;
    completion = tableCompletion;
  }

  StatusCode create(
      CatalogTableCreator builder,
      TableDescriptor provisional,
      SchemaPin pin,
      StatusDetail detail) {
    if (detail != null) detail.reset();
    if (provisional == null || pin == null || pin.isActive()) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT);
    }
    StatusCode status = state.retry(completion);
    if (!status.isOk()) return fail(detail, status);
    status = transactions.open(opened);
    if (!status.isOk()) return fail(detail, status);
    IndexedTransactionSession session = opened.session();
    status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) status = session.preflightPendingMutations(
        publicationRows, 0, publicationRows.length);
    if (status.isOk()) status = builder.prepare(
        provisional, session, state.prepared(), detail);
    StatusCode terminal = transactions.finish(session, status, true);
    TransactionState outcome = transactions.lastState();
    state.resolvedBy(outcome);
    if (!state.prepared().isActive()) return fail(detail, terminal);
    StatusCode completed = state.finish(completion, pin);
    if (completed.isOk() && !pin.isActive() && outcome == TransactionState.COMMITTED) {
      completed = StatusCode.INVARIANT_BROKEN;
    }
    return terminal.isOk() ? completed : fail(detail, terminal);
  }

  private static StatusCode fail(StatusDetail detail, StatusCode status) {
    if (detail != null && detail.code() == StatusCode.OK) detail.set(status);
    return status;
  }
}
