package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.tx.api.TransactionState;

/** Retains an inaccessible direct-create carrier until its terminal cleanup completes. */
final class CatalogDirectCreationState {
  private final CatalogPreparedTable prepared = new CatalogPreparedTable();
  private TransactionState resolution = TransactionState.ACTIVE;

  CatalogPreparedTable prepared() { return prepared; }

  void resolvedBy(TransactionState outcome) { resolution = outcome; }

  StatusCode retry(CatalogPreparedTableCompletion completion) {
    return finish(completion, null);
  }

  StatusCode finish(CatalogPreparedTableCompletion completion, SchemaPin pin) {
    if (!prepared.isActive()) return StatusCode.OK;
    StatusCode status = completion.finish(prepared, resolution, pin);
    if (!prepared.isActive()) resolution = TransactionState.ACTIVE;
    return status;
  }
}
