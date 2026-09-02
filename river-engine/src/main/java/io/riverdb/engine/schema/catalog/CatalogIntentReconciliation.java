package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.IsolationLevel;

/** Authoritatively resolves an unavailable private-build intent commit outcome. */
final class CatalogIntentReconciliation {
  private final CatalogTransactions transactions;
  private final CatalogIntentStore intents;
  private final CatalogSessionResult opened = new CatalogSessionResult();
  private boolean found;

  CatalogIntentReconciliation(
      CatalogTransactions transactionFlow, CatalogIntentStore intentStore) {
    transactions = transactionFlow;
    intents = intentStore;
  }

  StatusCode reconcile(CatalogPreparedTable prepared) {
    found = false;
    StatusCode status = transactions.open(opened);
    if (!status.isOk()) return status;
    IndexedTransactionSession session = opened.session();
    status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) status = intents.read(session, prepared.objectId());
    if (status == StatusCode.CONFLICT) {
      status = StatusCode.OK;
    } else if (status.isOk()) {
      if (!prepared.matchesUnknownIntent(intents.value())) {
        status = StatusCode.CORRUPTION;
      } else {
        found = true;
      }
    }
    StatusCode terminal = transactions.finish(session, status, false);
    if (!terminal.isOk()) found = false;
    return terminal;
  }

  boolean found() { return found; }
}
