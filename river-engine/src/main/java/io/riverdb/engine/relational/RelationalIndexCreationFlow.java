package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.TransactionOutcome;

/** Orchestrates the reserve, build, and publish phases of index creation. */
final class RelationalIndexCreationFlow {
  private final RelationalSchemaLifecycle owner;
  private final RelationalIndexRemoval indexRemoval;
  private final TableDefinition indexStorageTable;

  RelationalIndexCreationFlow(
      RelationalSchemaLifecycle owner,
      RelationalIndexRemoval indexRemoval,
      TableDefinition indexStorageTable) {
    this.owner = owner;
    this.indexRemoval = indexRemoval;
    this.indexStorageTable = indexStorageTable;
  }

  StatusCode create(
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      int maximumBuildBatches,
      boolean unique) {
    if (!RelationalKey.validName(indexName)
        || !RelationalKey.validName(tableName)
        || !RelationalKey.validName(columnName)
        || maximumBuildBatches <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    RelationalSession session = owner.newSession();
    if (session == null) return StatusCode.RESOURCE_EXHAUSTED;
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = owner.reserveIndexBuild(
        session, indexName, tableName, columnName, unique, outcome);
    boolean buildReserved = status.isOk();
    boolean complete = false;
    if (status.isOk()) {
      complete = owner.runIndexBuildBatches(
          session, tableName, outcome, maximumBuildBatches);
      status = owner.buildBatchStatus();
    }
    if (status.isOk() && !complete) {
      session.releasePersistentSchemaChange();
      return StatusCode.RETRY;
    }
    if (status.isOk()) {
      return owner.publishUniqueValueIndex(session, indexName, tableName, outcome);
    }
    if (buildReserved) {
      StatusCode cleanup = indexRemoval.cleanupFailedBuild(
          session,
          indexName,
          tableName,
          outcome,
          Integer.MAX_VALUE,
          indexStorageTable.tableId());
      return cleanup.isOk() ? status : cleanup;
    }
    session.releasePersistentSchemaChange();
    return status;
  }
}
