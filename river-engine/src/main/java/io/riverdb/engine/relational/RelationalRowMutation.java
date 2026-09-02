package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Owns row writes and their synchronous secondary-index maintenance. */
final class RelationalRowMutation {
  private final RelationalSchemaGate schemaGate;
  private final IndexedTransactionSession session;
  private final RelationalSecondaryIndexStore secondaryIndexes;
  private final RelationalKey.KeyResult physicalKey = new RelationalKey.KeyResult();
  private final TableDefinition valueIndexTable = new TableDefinition();
  private final TableDefinition referenceTable = new TableDefinition();
  private final HeapRowResult indexedKeyRow = new HeapRowResult();
  private final HeapRowResult referencedRow = new HeapRowResult();
  private final ByteBuffer rowScratch = ByteBuffer.allocateDirect(TableSchema.MAXIMUM_ROW_BYTES);
  private final RelationalPreviousIndexValues previousIndexes =
      new RelationalPreviousIndexValues();
  private final TableCheckEvaluator checks = new TableCheckEvaluator();

  RelationalRowMutation(
      RelationalSchemaGate gate,
      IndexedTransactionSession indexedSession,
      RelationalSecondaryIndexStore indexes) {
    schemaGate = gate;
    session = indexedSession;
    secondaryIndexes = indexes;
  }

  StatusCode insert(TableDefinition table, long key, ByteBuffer row) {
    StatusCode status = resolveWriteKey(table, key);
    return status.isOk()
        ? session.insert(physicalKey.space(), physicalKey.key(), row) : status;
  }

  StatusCode update(TableDefinition table, long key, ByteBuffer row) {
    StatusCode status = resolveWriteKey(table, key);
    return status.isOk()
        ? session.update(physicalKey.space(), physicalKey.key(), row) : status;
  }

  StatusCode delete(TableDefinition table, long key) {
    StatusCode status = resolveWriteKey(table, key);
    return status.isOk()
        ? session.delete(physicalKey.space(), physicalKey.key()) : status;
  }

  StatusCode fetch(TableDefinition table, long key, HeapRowResult result) {
    StatusCode status = resolveKey(table, key);
    return status.isOk()
        ? session.fetchByKey(physicalKey.space(), physicalKey.key(), result) : status;
  }

  StatusCode insertRow(TableDefinition table, long key, ByteBuffer row) {
    StatusCode status = validateCandidate(table, key, row);
    if (status.isOk()) {
      status = insert(table, key, row);
    }
    for (int slot = 0; status.isOk() && slot < table.uniqueIndexCount(); slot++) {
      status = insertReadyIndex(table, key, row, slot);
    }
    return status;
  }

  StatusCode updateRow(TableDefinition table, long key, ByteBuffer row) {
    StatusCode status = validateCandidate(table, key, row);
    if (status.isOk() && table.hasUniqueValueIndex()) {
      status = capturePreviousIndexedValues(table, key);
    }
    if (status.isOk()) {
      status = update(table, key, row);
    }
    for (int slot = 0; status.isOk() && slot < table.uniqueIndexCount(); slot++) {
      status = updateIndexedValue(table, key, row, slot);
    }
    return status;
  }

  private StatusCode validateCandidate(
      TableDefinition table, long key, ByteBuffer row) {
    if (!validRow(table, row)) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = checks.evaluate(table, key, row);
    return status.isOk() ? validateReferences(table, row) : status;
  }

  StatusCode deleteRow(TableDefinition table, long key) {
    StatusCode status = table != null && table.hasUniqueValueIndex()
        ? capturePreviousIndexedValues(table, key) : StatusCode.OK;
    if (status.isOk()) {
      status = delete(table, key);
    }
    for (int slot = 0; status.isOk() && slot < table.uniqueIndexCount(); slot++) {
      status = deleteReadyIndex(table, key, slot);
    }
    return status;
  }

  private StatusCode insertReadyIndex(
      TableDefinition table, long key, ByteBuffer row, int slot) {
    int column = table.uniqueIndexColumn(slot);
    if (table.uniqueIndexState(slot) != TableDefinition.INDEX_READY
        || table.isNull(row, column)) {
      return StatusCode.OK;
    }
    prepareValueIndex(table, slot);
    StatusCode status;
    if (table.isVarchar(column)) {
      status = secondaryIndexes.ensureText(
          valueIndexTable, table, column, row, key, table.indexIsUnique(slot));
    } else if (table.indexIsUnique(slot)) {
      status = secondaryIndexes.insertUnique(
          valueIndexTable, indexedValue(table, row, slot), key);
    } else {
      status = secondaryIndexes.insertNonUnique(
          valueIndexTable, indexedValue(table, row, slot), key);
    }
    return constraintStatus(table, slot, status);
  }

  private StatusCode capturePreviousIndexedValues(TableDefinition table, long key) {
    StatusCode status = previousIndexes.reserve(table.uniqueIndexCount());
    if (status.isOk()) status = fetch(table, key, indexedKeyRow);
    if (status.isOk()) {
      status = copyRow(table, indexedKeyRow, rowScratch);
    }
    for (int slot = 0; status.isOk() && slot < table.uniqueIndexCount(); slot++) {
      previousIndexes.set(
          slot,
          indexedValue(table, rowScratch, slot),
          table.isNull(rowScratch, table.uniqueIndexColumn(slot)));
    }
    return status;
  }

  private StatusCode updateIndexedValue(
      TableDefinition table,
      long key,
      ByteBuffer row,
      int slot) {
    if (table.uniqueIndexState(slot) != TableDefinition.INDEX_READY) {
      return StatusCode.OK;
    }
    int column = table.uniqueIndexColumn(slot);
    boolean nextNull = table.isNull(row, column);
    long nextValue = indexedValue(table, row, slot);
    if (previousIndexes.same(slot, nextNull, nextValue)) {
      return StatusCode.OK;
    }
    prepareValueIndex(table, slot);
    StatusCode status = removePreviousIndexedValue(table, key, slot, column);
    return status.isOk() && !nextNull
        ? addUpdatedIndexedValue(table, key, row, slot, column, nextValue)
        : status;
  }

  private StatusCode removePreviousIndexedValue(
      TableDefinition table, long key, int slot, int column) {
    if (previousIndexes.isNull(slot)) {
      return StatusCode.OK;
    }
    if (table.isVarchar(column) || !table.indexIsUnique(slot)) {
      return secondaryIndexes.deleteNonUnique(
          valueIndexTable, previousIndexes.value(slot), key);
    }
    return secondaryIndexes.deleteUnique(valueIndexTable, previousIndexes.value(slot));
  }

  private StatusCode addUpdatedIndexedValue(
      TableDefinition table,
      long key,
      ByteBuffer row,
      int slot,
      int column,
      long nextValue) {
    StatusCode status;
    if (table.isVarchar(column)) {
      status = secondaryIndexes.ensureText(
          valueIndexTable, table, column, row, key, table.indexIsUnique(slot));
    } else if (table.indexIsUnique(slot)) {
      status = secondaryIndexes.insertUnique(valueIndexTable, nextValue, key);
    } else {
      status = secondaryIndexes.insertNonUnique(valueIndexTable, nextValue, key);
    }
    return constraintStatus(table, slot, status);
  }

  private StatusCode deleteReadyIndex(TableDefinition table, long key, int slot) {
    if (table.uniqueIndexState(slot) != TableDefinition.INDEX_READY
        || previousIndexes.isNull(slot)) {
      return StatusCode.OK;
    }
    prepareValueIndex(table, slot);
    return table.isVarchar(table.uniqueIndexColumn(slot))
            || !table.indexIsUnique(slot)
        ? secondaryIndexes.deleteNonUnique(
            valueIndexTable, previousIndexes.value(slot), key)
        : secondaryIndexes.deleteUnique(valueIndexTable, previousIndexes.value(slot));
  }

  private StatusCode validateReferences(TableDefinition table, ByteBuffer row) {
    if (!table.hasReferences()) {
      return StatusCode.OK;
    }
    for (int column = 1; column < table.columnCount(); column++) {
      if (!table.hasReference(column) || table.isNull(row, column)) {
        continue;
      }
      long referencedKey = row.getLong(row.position() + table.valueOffset(column));
      referenceTable.set(
          schemaGate,
          table.referenceTableId(column),
          0,
          TableDefinition.INDEX_NONE);
      StatusCode status = resolveKey(referenceTable, referencedKey);
      if (status.isOk()) {
        status = session.protectKey(physicalKey.space(), physicalKey.key());
      }
      if (status.isOk()) {
        status = session.fetchByKey(
            physicalKey.space(), physicalKey.key(), referencedRow);
      }
      if (status == StatusCode.CONFLICT || status == StatusCode.INVALID_EXTERNAL_INPUT) {
        return StatusCode.FOREIGN_KEY_VIOLATION;
      }
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode resolveWriteKey(TableDefinition table, long key) {
    if (table != null && table.hasBuildingUniqueValueIndex()) {
      return StatusCode.RETRY;
    }
    return resolveKey(table, key);
  }

  private StatusCode resolveKey(TableDefinition table, long key) {
    if (table == null || !table.isOwnedBy(schemaGate)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return RelationalKey.tableRowKey(table.tableId(), key, physicalKey);
  }

  private void prepareValueIndex(TableDefinition table, int slot) {
    valueIndexTable.set(
        schemaGate,
        table.uniqueIndexTableId(slot),
        0,
        TableDefinition.INDEX_NONE);
  }

  private static StatusCode constraintStatus(
      TableDefinition table, int slot, StatusCode status) {
    return status == StatusCode.CONFLICT && table.indexIsConstraint(slot)
        ? StatusCode.UNIQUE_VIOLATION : status;
  }

  private static boolean validRow(TableDefinition table, ByteBuffer row) {
    return table != null && table.isValidRow(row);
  }

  private static long indexedValue(TableDefinition table, ByteBuffer row, int slot) {
    return RelationalSecondaryIndexStore.indexedValue(table, row, slot);
  }

  private static StatusCode copyRow(
      TableDefinition table,
      HeapRowResult source,
      ByteBuffer target) {
    if (source.length() < table.fixedRowBytes()
        || source.length() > table.maximumRowBytes()) {
      return StatusCode.CORRUPTION;
    }
    target.clear();
    target.limit(source.length());
    StatusCode status = source.copyTo(target);
    if (status.isOk()) {
      target.position(0);
      status = table.isValidRow(target) ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    return status;
  }
}
