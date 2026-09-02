package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import java.nio.ByteBuffer;

/** Reserves, resumes, and publishes a value-index schema transition. */
final class RelationalIndexSchemaLifecycle {
  private final RelationalSchemaGate schemaGate;
  private final HeapRowResult catalogRow;
  private final ByteBuffer catalogScratch;
  private final ByteBuffer catalogOutput;
  private final RelationalKey.KeyResult catalogKey;
  private final CatalogIndexCodec.Result indexRecord;
  private final TableDefinition indexedTable;
  private final TableDefinition indexStorageTable;
  private final CatalogSequenceCodec.IntResult nextTableId;

  RelationalIndexSchemaLifecycle(
      RelationalSchemaGate schemaGate,
      HeapRowResult catalogRow,
      ByteBuffer catalogScratch,
      ByteBuffer catalogOutput,
      RelationalKey.KeyResult catalogKey,
      CatalogIndexCodec.Result indexRecord,
      TableDefinition indexedTable,
      TableDefinition indexStorageTable,
      CatalogSequenceCodec.IntResult nextTableId) {
    this.schemaGate = schemaGate;
    this.catalogRow = catalogRow;
    this.catalogScratch = catalogScratch;
    this.catalogOutput = catalogOutput;
    this.catalogKey = catalogKey;
    this.indexRecord = indexRecord;
    this.indexedTable = indexedTable;
    this.indexStorageTable = indexStorageTable;
    this.nextTableId = nextTableId;
  }

  StatusCode reserveOrResume(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      boolean unique) {
    StatusCode status = session.resolveTable(tableName, indexedTable);
    int indexColumn = status.isOk() ? indexedTable.findColumn(columnName) : -1;
    if (status.isOk() && indexColumn <= 0) status = StatusCode.INVALID_EXTERNAL_INPUT;
    if (status.isOk() && !indexedTable.supportsSecondaryIndex(indexColumn)) {
      status = StatusCode.DATATYPE_MISMATCH;
    }
    if (!status.isOk()) return status;
    if (indexedTable.hasIndexOn(indexColumn)) return StatusCode.CONFLICT;
    if (indexedTable.uniqueIndexCount() >= SqlShapeLimits.MAX_SECONDARY_INDEXES
        && !indexedTable.hasBuildingUniqueValueIndex()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = RelationalKey.catalogTableKey(indexName, catalogKey);
    if (!status.isOk()) return status;
    status = session.indexedSession().fetchByKey(
        catalogKey.space(), catalogKey.key(), catalogRow);
    if (status.isOk()) {
      status = resume(indexName, indexColumn, unique);
    } else if (status == StatusCode.CONFLICT) {
      status = reserve(session, indexName, tableName, indexColumn, unique);
    } else {
      return status;
    }
    int indexTableId = status.isOk() ? indexedTable.uniqueValueIndexTableId() : 0;
    if (status.isOk()) {
      indexStorageTable.set(
          schemaGate, indexTableId, 0, TableDefinition.INDEX_NONE);
    }
    return status;
  }

  StatusCode publish(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      TransactionOutcome outcome) {
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) status = session.resolveTable(tableName, indexedTable);
    if (status.isOk()
        && (!indexedTable.hasBuildingUniqueValueIndex()
            || indexedTable.uniqueValueIndexTableId() != indexStorageTable.tableId())) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) status = RelationalKey.catalogTableKey(tableName, catalogKey);
    if (status.isOk()) {
      int buildingSlot = indexedTable.buildingIndexSlot();
      CatalogRecord.encodeTable(
          catalogOutput,
          indexedTable.tableId(),
          indexStorageTable.tableId(),
          TableDefinition.INDEX_READY,
          indexedTable.uniqueValueIndexColumn(),
          tableName,
          indexedTable,
          indexedTable.indexIsUnique(buildingSlot));
      status = session.indexedSession().update(
          catalogKey.space(), catalogKey.key(), catalogOutput);
    }
    if (status.isOk()) status = RelationalKey.catalogTableKey(indexName, catalogKey);
    if (status.isOk()) {
      int buildingSlot = indexedTable.buildingIndexSlot();
      CatalogIndexCodec.encode(
          catalogOutput,
          indexedTable.tableId(),
          indexStorageTable.tableId(),
          TableDefinition.INDEX_READY,
          indexName,
          indexedTable.indexIsUnique(buildingSlot));
      status = session.indexedSession().update(
          catalogKey.space(), catalogKey.key(), catalogOutput);
    }
    if (status.isOk()) return session.commit(outcome);
    if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abort(outcome);
      if (!abort.isOk()) return abort;
    } else {
      session.releasePersistentSchemaChange();
    }
    return status;
  }

  int indexStorageTableId() {
    return indexStorageTable.tableId();
  }

  private StatusCode resume(CharSequence indexName, int indexColumn, boolean unique) {
    StatusCode status = CatalogIndexCodec.decode(
        catalogRow, catalogScratch, indexName, indexRecord);
    return status.isOk()
            && indexRecord.state() == TableDefinition.INDEX_BUILDING
            && indexRecord.tableId() == indexedTable.tableId()
            && indexedTable.hasBuildingUniqueValueIndex()
            && indexedTable.uniqueValueIndexColumn() == indexColumn
            && indexedTable.uniqueValueIndexTableId() == indexRecord.indexTableId()
            && indexRecord.isUnique() == unique
        ? StatusCode.OK : status.isOk() ? StatusCode.CONFLICT : status;
  }

  private StatusCode reserve(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      int indexColumn,
      boolean unique) {
    if (indexedTable.hasBuildingUniqueValueIndex()) return StatusCode.CORRUPTION;
    StatusCode status = session.indexedSession().fetchByKey(
        RelationalKey.CATALOG_SEQUENCE_SPACE, 0, catalogRow);
    if (status.isOk()) {
      status = CatalogSequenceCodec.decodeAllocation(
          catalogRow, catalogScratch, nextTableId);
    }
    int indexTableId = nextTableId.value();
    if (status.isOk() && indexTableId > RelationalKey.MAXIMUM_TABLE_ID) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (status.isOk()) {
      CatalogSequenceCodec.encodeAllocation(catalogOutput, indexTableId + 1);
      status = session.indexedSession().update(
          RelationalKey.CATALOG_SEQUENCE_SPACE, 0, catalogOutput);
    }
    if (status.isOk()) {
      status = insertBuildingCatalogs(
          session, indexName, tableName, indexTableId, indexColumn, unique);
    }
    if (status.isOk()) {
      status = indexedTable.upsertIndex(
          indexTableId,
          TableDefinition.INDEX_BUILDING,
          indexColumn,
          unique);
    }
    return status;
  }

  private StatusCode insertBuildingCatalogs(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      int indexTableId,
      int indexColumn,
      boolean unique) {
    StatusCode status = RelationalKey.catalogTableKey(tableName, catalogKey);
    if (status.isOk()) {
      CatalogRecord.encodeTable(
          catalogOutput,
          indexedTable.tableId(),
          indexTableId,
          TableDefinition.INDEX_BUILDING,
          indexColumn,
          tableName,
          indexedTable,
          unique);
      status = session.indexedSession().update(
          catalogKey.space(), catalogKey.key(), catalogOutput);
    }
    if (status.isOk()) status = RelationalKey.catalogTableKey(indexName, catalogKey);
    if (status.isOk()) {
      CatalogIndexCodec.encode(
          catalogOutput,
          indexedTable.tableId(),
          indexTableId,
          TableDefinition.INDEX_BUILDING,
          indexName,
          unique);
      status = session.indexedSession().insert(
          catalogKey.space(), catalogKey.key(), catalogOutput);
    }
    return status;
  }
}
