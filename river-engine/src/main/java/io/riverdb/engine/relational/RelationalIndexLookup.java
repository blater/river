package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Cursor and point-lookup state for secondary indexes. */
final class RelationalIndexLookup {
  private final RelationalSchemaGate schemaGate;
  private final IndexedTransactionSession transaction;
  private final RelationalKey.KeyResult physicalKey = new RelationalKey.KeyResult();
  private final TableDefinition indexTable = new TableDefinition();
  private final HeapRowResult indexRow = new HeapRowResult();
  private final HeapRowResult entryRow = new HeapRowResult();
  private final ByteBuffer valueScratch = ByteBuffer.allocateDirect(Long.BYTES);
  private final ByteBuffer entryScratch = ByteBuffer.allocateDirect(
      RelationalSecondaryIndexStore.NON_UNIQUE_ENTRY_BYTES);
  private final ByteBuffer rowScratch =
      ByteBuffer.allocateDirect(TableSchema.MAXIMUM_ROW_BYTES);

  RelationalIndexLookup(
      RelationalSchemaGate gate, IndexedTransactionSession indexedTransaction) {
    schemaGate = gate;
    transaction = indexedTransaction;
  }

  StatusCode fetch(
      RelationalSession owner,
      TableDefinition table,
      long value,
      ValueIndexLookupResult result) {
    int slot = table == null ? -1 : firstReadySlot(table);
    return slot < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : fetch(owner, table, table.uniqueIndexColumn(slot), value, result);
  }

  StatusCode fetch(
      RelationalSession owner,
      TableDefinition table,
      int column,
      long value,
      ValueIndexLookupResult result) {
    int slot = table == null ? -1 : table.readyIndexSlotOn(column);
    if (table == null || !table.isOwnedBy(schemaGate) || slot < 0
        || !table.indexIsUnique(slot) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    prepare(table, slot);
    StatusCode status = fetchRow(indexTable, value, indexRow);
    if (status.isOk()) {
      status = RelationalSecondaryIndexStore.decodeLong(indexRow, valueScratch);
    }
    long primaryKey = status.isOk() ? valueScratch.getLong(0) : 0;
    if (status.isOk()) {
      status = fetchRow(table, primaryKey, result.row());
      if (status == StatusCode.CONFLICT) {
        return StatusCode.CORRUPTION;
      }
    }
    if (status.isOk()) {
      status = copyRow(table, result.row());
    }
    if (status.isOk()
        && RelationalSecondaryIndexStore.indexedValue(table, rowScratch, slot) != value) {
      return StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      result.setKey(primaryKey);
    }
    return status;
  }

  StatusCode beginScan(
      RelationalSession owner,
      TableDefinition table,
      int column,
      RelationalScanCursor cursor) {
    int slot = table == null ? -1 : table.readyIndexSlotOn(column);
    return slot < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : beginFullScan(owner, table, column, cursor, slot);
  }

  StatusCode beginScan(
      RelationalSession owner,
      TableDefinition table,
      int column,
      long lowerInclusive,
      long upperExclusive,
      RelationalScanCursor cursor) {
    int slot = table == null ? -1 : table.readyIndexSlotOn(column);
    if (table == null || !table.isOwnedBy(schemaGate) || slot < 0
        || upperExclusive <= lowerInclusive
        || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    prepare(table, slot);
    StatusCode status = owner.beginScan(
        indexTable, lowerInclusive, upperExclusive, cursor);
    return status.isOk()
        ? cursor.setIndexedColumn(owner, column, table.indexIsUnique(slot)) : status;
  }

  StatusCode beginExactScan(
      RelationalSession owner,
      TableDefinition table,
      int column,
      long value,
      RelationalScanCursor cursor) {
    int slot = table == null ? -1 : table.readyIndexSlotOn(column);
    if (table == null
        || !table.isOwnedBy(schemaGate)
        || slot < 0
        || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    prepare(table, slot);
    StatusCode status = owner.beginExactScan(indexTable, value, cursor);
    return status.isOk()
        ? cursor.setIndexedColumn(owner, column, table.indexIsUnique(slot)) : status;
  }

  private StatusCode beginFullScan(
      RelationalSession owner,
      TableDefinition table,
      int column,
      RelationalScanCursor cursor,
      int slot) {
    if (table == null || !table.isOwnedBy(schemaGate) || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    prepare(table, slot);
    StatusCode status = owner.beginScan(indexTable, cursor);
    return status.isOk()
        ? cursor.setIndexedColumn(owner, column, table.indexIsUnique(slot)) : status;
  }

  StatusCode next(
      RelationalSession owner,
      TableDefinition table,
      RelationalScanCursor cursor,
      RelationalScanResult indexResult,
      ValueIndexLookupResult result) {
    if (table == null || !table.isOwnedBy(schemaGate) || cursor == null
        || table.readyIndexSlotOn(cursor.indexedColumn()) < 0
        || cursor.exactValueLookup() || indexResult == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int slot = table.readyIndexSlotOn(cursor.indexedColumn());
    if (!cursor.uniqueIndex()) {
      return nextNonUniqueScan(owner, table, cursor, indexResult, result, slot);
    }
    StatusCode status = owner.nextScan(cursor, indexResult);
    if (status.isOk()) {
      status = RelationalSecondaryIndexStore.decodeLong(indexResult.row(), valueScratch);
    }
    long primaryKey = status.isOk() ? valueScratch.getLong(0) : 0;
    long value = status.isOk() ? indexResult.key() : 0;
    return status.isOk()
        ? finishRow(table, primaryKey, value, slot, result) : status;
  }

  StatusCode beginNonUnique(
      RelationalSession owner,
      TableDefinition table,
      int column,
      long value,
      RelationalScanCursor cursor) {
    int slot = table == null ? -1 : table.readyIndexSlotOn(column);
    if (table == null || !table.isOwnedBy(schemaGate) || slot < 0
        || table.indexIsUnique(slot)
        || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    prepare(table, slot);
    StatusCode status = fetchRow(
        indexTable, value, indexRow);
    if (status.isOk()) {
      status = RelationalSecondaryIndexStore.decodeLong(indexRow, valueScratch);
    }
    long entryId = status.isOk() ? valueScratch.getLong(0) : 0;
    if (status.isOk() && !RelationalSecondaryIndexStore.validNonUniqueEntryId(entryId)) {
      status = StatusCode.CORRUPTION;
    }
    return status.isOk()
        ? cursor.claimExactValueLookup(owner, column, value, entryId) : status;
  }

  StatusCode nextNonUnique(
      RelationalSession owner,
      TableDefinition table,
      RelationalScanCursor cursor,
      ValueIndexLookupResult result) {
    int slot = table == null || cursor == null
        ? -1 : table.readyIndexSlotOn(cursor.indexedColumn());
    if (table == null || !table.isOwnedBy(schemaGate) || slot < 0
        || table.indexIsUnique(slot) || cursor == null
        || !cursor.isOwnedBy(owner) || !cursor.exactValueLookup() || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    prepare(table, slot);
    return nextEntry(table, cursor, result, slot);
  }

  private StatusCode nextNonUniqueScan(
      RelationalSession owner,
      TableDefinition table,
      RelationalScanCursor cursor,
      RelationalScanResult indexResult,
      ValueIndexLookupResult result,
      int slot) {
    prepare(table, slot);
    StatusCode status = StatusCode.OK;
    if (cursor.duplicateEntryId() == 0) {
      status = owner.nextScan(cursor, indexResult);
      if (!status.isOk()) {
        return status;
      }
      status = RelationalSecondaryIndexStore.decodeLong(indexResult.row(), valueScratch);
      long entryId = status.isOk() ? valueScratch.getLong(0) : 0;
      if (status.isOk() && !RelationalSecondaryIndexStore.validNonUniqueEntryId(entryId)) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        cursor.startDuplicateChain(indexResult.key(), entryId);
      }
    }
    return status.isOk() ? nextEntry(table, cursor, result, slot) : status;
  }

  private StatusCode nextEntry(
      TableDefinition table,
      RelationalScanCursor cursor,
      ValueIndexLookupResult result,
      int slot) {
    if (cursor.duplicateEntryId() == 0) {
      return StatusCode.CONFLICT;
    }
    if (cursor.duplicateEntriesVisited()
        >= RelationalSecondaryIndexStore.MAXIMUM_DUPLICATE_CHAIN) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = readEntry(cursor.duplicateValue(), cursor.duplicateEntryId());
    long value = status.isOk() ? entryScratch.getLong(0) : 0;
    long primaryKey = status.isOk() ? entryScratch.getLong(8) : 0;
    long nextEntryId = status.isOk() ? entryScratch.getLong(16) : 0;
    if (status.isOk() && value != cursor.duplicateValue()) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      cursor.advanceDuplicateChain(nextEntryId);
      status = finishRow(table, primaryKey, value, slot, result);
    }
    return status;
  }

  private StatusCode finishRow(
      TableDefinition table,
      long primaryKey,
      long value,
      int slot,
      ValueIndexLookupResult result) {
    StatusCode status = fetchRow(table, primaryKey, result.row());
    if (status == StatusCode.CONFLICT) {
      return StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      status = copyRow(table, result.row());
    }
    if (status.isOk()
        && RelationalSecondaryIndexStore.indexedValue(table, rowScratch, slot) != value) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      result.setKey(primaryKey);
    }
    return status;
  }

  private StatusCode readEntry(long expectedValue, long entryId) {
    StatusCode status = fetchAuxiliaryRow(indexTable, entryId, entryRow);
    if (status == StatusCode.CONFLICT) {
      return StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      status = RelationalSecondaryIndexStore.copyEntry(entryRow, entryScratch);
    }
    long next = status.isOk() ? entryScratch.getLong(16) : -1;
    return status.isOk()
            && entryScratch.getLong(0) == expectedValue
            && next >= 0
            && next < Long.MAX_VALUE
        ? StatusCode.OK : status.isOk() ? StatusCode.CORRUPTION : status;
  }

  private StatusCode fetchRow(
      TableDefinition table, long key, HeapRowResult result) {
    StatusCode status = RelationalKey.tableRowKey(table.tableId(), key, physicalKey);
    return status.isOk()
        ? transaction.fetchByKey(physicalKey.space(), physicalKey.key(), result) : status;
  }

  private StatusCode fetchAuxiliaryRow(
      TableDefinition table, long key, HeapRowResult result) {
    return transaction.fetchByKey(
        RelationalKey.auxiliarySpace(table.tableId()), key, result);
  }

  private StatusCode copyRow(TableDefinition table, HeapRowResult source) {
    if (source.length() < table.fixedRowBytes()
        || source.length() > table.maximumRowBytes()) {
      return StatusCode.CORRUPTION;
    }
    rowScratch.clear();
    rowScratch.limit(source.length());
    StatusCode status = source.copyTo(rowScratch);
    if (status.isOk()) {
      rowScratch.position(0);
      status = table.isValidRow(rowScratch) ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    return status;
  }

  private void prepare(TableDefinition table, int slot) {
    indexTable.set(
        schemaGate, table.uniqueIndexTableId(slot), 0, TableDefinition.INDEX_NONE);
  }

  private static int firstReadySlot(TableDefinition table) {
    for (int slot = 0; slot < table.uniqueIndexCount(); slot++) {
      if (table.uniqueIndexState(slot) == TableDefinition.INDEX_READY) {
        return slot;
      }
    }
    return -1;
  }
}
