package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** One-transaction construction and catalog publication of a secondary index. */
final class RelationalIndexBuilder {
  private final RelationalSchemaGate schemaGate;
  private final HeapRowResult catalogRow = new HeapRowResult();
  private final ByteBuffer catalogScratch =
      ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final ByteBuffer catalogOutput =
      ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final RelationalKey.KeyResult catalogKey = new RelationalKey.KeyResult();
  private final CatalogSequenceCodec.IntResult nextTableId =
      new CatalogSequenceCodec.IntResult();
  private final TableDefinition sourceTable = new TableDefinition();
  private final TableDefinition indexTable = new TableDefinition();
  private final RelationalScanCursor scanCursor = new RelationalScanCursor();
  private final RelationalScanResult scanRow = new RelationalScanResult();

  RelationalIndexBuilder(RelationalSchemaGate gate) {
    schemaGate = gate;
  }

  StatusCode build(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      boolean unique,
      boolean constraint) {
    StatusCode status = perform(
        session, indexName, tableName, columnName, unique, constraint);
    scanCursor.reset();
    return status;
  }

  private StatusCode perform(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      CharSequence columnName,
      boolean unique,
      boolean constraint) {
    StatusCode status = session.resolveTable(tableName, sourceTable);
    if (!status.isOk()) {
      return status;
    }
    int indexColumn = sourceTable.findColumn(columnName);
    status = validate(indexColumn);
    if (!status.isOk()) {
      return status;
    }
    status = loadNextTableId(session, indexName);
    if (!status.isOk()) {
      return status;
    }
    int indexTableId = nextTableId.value();
    if (indexTableId > RelationalKey.MAXIMUM_TABLE_ID) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = populate(session, indexTableId, indexColumn, unique, constraint);
    return status.isOk()
        ? publish(
            session,
            indexName,
            tableName,
            indexColumn,
            indexTableId,
            unique,
            constraint)
        : status;
  }

  private StatusCode validate(int indexColumn) {
    if (indexColumn <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!sourceTable.supportsSecondaryIndex(indexColumn)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (sourceTable.hasIndexOn(indexColumn)) {
      return StatusCode.CONFLICT;
    }
    if (sourceTable.hasBuildingUniqueValueIndex()) {
      return StatusCode.RETRY;
    }
    return sourceTable.uniqueIndexCount() >= TableDefinition.MAXIMUM_INDEXES
        ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }

  private StatusCode loadNextTableId(
      RelationalSession session, CharSequence indexName) {
    StatusCode status = RelationalKey.catalogTableKey(indexName, catalogKey);
    if (!status.isOk()) {
      return status;
    }
    status = session.indexedSession().fetchByKey(
        catalogKey.space(), catalogKey.key(), catalogRow);
    if (status.isOk()) {
      return StatusCode.CONFLICT;
    }
    if (status != StatusCode.CONFLICT) {
      return status;
    }
    status = session.indexedSession().fetchByKey(
        RelationalKey.CATALOG_SEQUENCE_SPACE, 0, catalogRow);
    return status.isOk()
        ? CatalogSequenceCodec.decodeAllocation(catalogRow, catalogScratch, nextTableId)
        : status;
  }

  private StatusCode populate(
      RelationalSession session,
      int indexTableId,
      int indexColumn,
      boolean unique,
      boolean constraint) {
    indexTable.set(schemaGate, indexTableId, 0, TableDefinition.INDEX_NONE);
    StatusCode status = session.beginScan(sourceTable, scanCursor);
    if (!status.isOk()) {
      return status;
    }
    status = scanRows(session, indexColumn, unique, constraint);
    StatusCode close = session.closeScan(scanCursor);
    return status.isOk() ? close : status;
  }

  private StatusCode scanRows(
      RelationalSession session,
      int indexColumn,
      boolean unique,
      boolean constraint) {
    while (true) {
      StatusCode status = session.nextScan(scanCursor, scanRow);
      if (status == StatusCode.CONFLICT) {
        return StatusCode.OK;
      }
      if (!status.isOk()) {
        return status;
      }
      status = copyAndValidateRow();
      if (!status.isOk()) {
        return status;
      }
      status = insertEntry(session, indexColumn, unique, constraint);
      if (!status.isOk()) {
        return status;
      }
    }
  }

  private StatusCode copyAndValidateRow() {
    int rowBytes = scanRow.row().length();
    if (rowBytes < sourceTable.fixedRowBytes()
        || rowBytes > sourceTable.maximumRowBytes()) {
      return StatusCode.CORRUPTION;
    }
    catalogScratch.clear();
    StatusCode status = scanRow.row().copyTo(catalogScratch);
    if (!status.isOk()) {
      return status;
    }
    catalogScratch.flip();
    return sourceTable.isValidRow(catalogScratch)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode insertEntry(
      RelationalSession session,
      int indexColumn,
      boolean unique,
      boolean constraint) {
    boolean nullValue = (catalogScratch.getLong(sourceTable.nullMaskOffset())
        & 1L << indexColumn) != 0;
    if (nullValue) {
      return StatusCode.OK;
    }
    if (sourceTable.isVarchar(indexColumn)) {
      StatusCode status = session.ensureTextIndexedValue(
          indexTable,
          sourceTable,
          indexColumn,
          catalogScratch,
          scanRow.key(),
          unique);
      return constraintStatus(status, constraint);
    }
    long value = catalogScratch.getLong((indexColumn - 1) * Long.BYTES);
    if (!unique) {
      return session.insertNonUniqueIndexedValue(indexTable, value, scanRow.key());
    }
    return constraintStatus(
        session.insertIndexedValue(indexTable, value, scanRow.key()), constraint);
  }

  private StatusCode publish(
      RelationalSession session,
      CharSequence indexName,
      CharSequence tableName,
      int indexColumn,
      int indexTableId,
      boolean unique,
      boolean constraint) {
    CatalogSequenceCodec.encodeAllocation(catalogOutput, indexTableId + 1);
    StatusCode status = session.indexedSession().update(
        RelationalKey.CATALOG_SEQUENCE_SPACE, 0, catalogOutput);
    if (!status.isOk()) {
      return status;
    }
    status = RelationalKey.catalogTableKey(tableName, catalogKey);
    if (!status.isOk()) {
      return status;
    }
    CatalogRecord.encodeTable(
        catalogOutput,
        sourceTable.tableId(),
        indexTableId,
        TableDefinition.INDEX_READY,
        indexColumn,
        tableName,
        sourceTable,
        unique,
        constraint);
    status = session.indexedSession().update(
        catalogKey.space(), catalogKey.key(), catalogOutput);
    if (!status.isOk()) {
      return status;
    }
    status = RelationalKey.catalogTableKey(indexName, catalogKey);
    if (!status.isOk()) {
      return status;
    }
    CatalogIndexCodec.encode(
        catalogOutput,
        sourceTable.tableId(),
        indexTableId,
        TableDefinition.INDEX_READY,
        indexName,
        unique,
        constraint);
    return session.indexedSession().insert(
        catalogKey.space(), catalogKey.key(), catalogOutput);
  }

  private static StatusCode constraintStatus(
      StatusCode status, boolean constraint) {
    return status == StatusCode.CONFLICT && constraint
        ? StatusCode.UNIQUE_VIOLATION : status;
  }
}
