package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.tx.api.TransactionState;

/** Resolves cache admission and private-build cleanup after the outer decision. */
final class CatalogPreparedTableCompletion {
  private final CatalogBuildCleaner cleaner;
  private final CatalogIntentReconciliation reconciliation;

  CatalogPreparedTableCompletion(
      CatalogTransactions transactions, CatalogIntentStore intents,
      CatalogBuildCleaner buildCleaner) {
    cleaner = buildCleaner;
    reconciliation = new CatalogIntentReconciliation(transactions, intents);
  }

  StatusCode finish(
      CatalogPreparedTable prepared, TransactionState outcome, SchemaPin pin) {
    if (prepared == null || !prepared.isActive()
        || (pin != null && pin.isActive())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (outcome == TransactionState.INDETERMINATE) return StatusCode.RETRY;
    if (outcome != TransactionState.COMMITTED && outcome != TransactionState.ABORTED) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean committed = outcome == TransactionState.COMMITTED;
    if (!prepared.durableBuildKnown()) {
      StatusCode status = reconciliation.reconcile(prepared);
      if (!status.isOk()) return status;
      if (!reconciliation.found()) return cancelUnknown(prepared);
      prepared.markDurableBuildKnown();
    }
    StatusCode cacheStatus = resolveCache(prepared, committed, pin);
    if (prepared.admission().isActive()) return cacheStatus;
    StatusCode cleanup = cleaner.cleanup(prepared.objectId());
    if (cleanup.isOk()) prepared.clear();
    return cacheStatus.isOk() ? cleanup : cacheStatus;
  }

  private static StatusCode cancelUnknown(CatalogPreparedTable prepared) {
    StatusCode status = prepared.admission().isActive()
        ? prepared.admission().cancel() : StatusCode.OK;
    if (status.isOk()) prepared.clear();
    return status;
  }

  private static StatusCode resolveCache(
      CatalogPreparedTable prepared, boolean committed, SchemaPin pin) {
    if (prepared.cacheResolved()) return StatusCode.OK;
    StatusCode status = committed
        ? prepared.admission().publish(prepared.descriptor(), pin)
        : prepared.admission().cancel();
    if (committed && !status.isOk() && prepared.admission().isActive()) {
      StatusCode cancelled = prepared.admission().cancel();
      if (!cancelled.isOk()) return status;
    } else if (!status.isOk()) {
      return status;
    }
    prepared.markCacheResolved();
    return status;
  }
}
