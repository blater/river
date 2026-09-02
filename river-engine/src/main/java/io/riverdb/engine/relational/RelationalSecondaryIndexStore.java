package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Owns physical secondary-index rows and bounded duplicate-chain mutation. */
final class RelationalSecondaryIndexStore {
  static final long NON_UNIQUE_ALLOCATOR_KEY = 0;
  static final int NON_UNIQUE_ENTRY_BYTES = 3 * Long.BYTES;
  static final int MAXIMUM_DUPLICATE_CHAIN = 64 * 1024;

  private final RelationalSchemaGate schemaGate;
  private final IndexedTransactionSession transaction;
  private final RelationalKey.KeyResult physicalKey = new RelationalKey.KeyResult();
  private final HeapRowResult indexedKeyRow = new HeapRowResult();
  private final HeapRowResult indexHeadRow = new HeapRowResult();
  private final HeapRowResult indexEntryRow = new HeapRowResult();
  private final HeapRowResult indexAllocatorRow = new HeapRowResult();
  private final ByteBuffer valueScratch = ByteBuffer.allocateDirect(Long.BYTES);
  private final ByteBuffer indexEntryScratch = ByteBuffer.allocateDirect(NON_UNIQUE_ENTRY_BYTES);
  private final ByteBuffer rowScratch = ByteBuffer.allocateDirect(TableSchema.MAXIMUM_ROW_BYTES);
  private final ByteBuffer indexRow = ByteBuffer.allocateDirect(Long.BYTES);
  private final ByteBuffer nonUniqueIndexRow = ByteBuffer.allocateDirect(NON_UNIQUE_ENTRY_BYTES);
  private final DeleteTarget deleteTarget = new DeleteTarget();
  private final LongResult allocationResult = new LongResult();

  RelationalSecondaryIndexStore(
      RelationalSchemaGate gate,
      IndexedTransactionSession indexedTransaction) {
    schemaGate = gate;
    transaction = indexedTransaction;
  }

  StatusCode insertUnique(
      TableDefinition indexTable,
      long value,
      long primaryKey) {
    encodeLong(indexRow, primaryKey);
    return insert(indexTable, value, indexRow);
  }

  StatusCode ensureUnique(
      TableDefinition indexTable,
      long value,
      long primaryKey) {
    StatusCode status = fetch(indexTable, value, indexedKeyRow);
    if (status == StatusCode.CONFLICT) {
      return insertUnique(indexTable, value, primaryKey);
    }
    if (!status.isOk()) {
      return status;
    }
    status = decodeLong(indexedKeyRow, valueScratch);
    return status.isOk() && valueScratch.getLong(0) == primaryKey
        ? StatusCode.OK : StatusCode.CONFLICT;
  }

  StatusCode ensureText(
      TableDefinition indexTable,
      TableDefinition baseTable,
      int column,
      ByteBuffer candidate,
      long primaryKey,
      boolean unique) {
    long fingerprint = textFingerprint(baseTable, candidate, column);
    StatusCode status = unique
        ? checkTextUniqueness(
            indexTable, baseTable, column, candidate, primaryKey, fingerprint)
        : StatusCode.OK;
    return status.isOk()
        ? insertNonUnique(indexTable, fingerprint, primaryKey) : status;
  }

  private StatusCode checkTextUniqueness(
      TableDefinition indexTable,
      TableDefinition baseTable,
      int column,
      ByteBuffer candidate,
      long primaryKey,
      long fingerprint) {
    StatusCode status = fetch(
        indexTable, fingerprint, indexHeadRow);
    if (status == StatusCode.CONFLICT) {
      return StatusCode.OK;
    }
    if (status.isOk()) {
      status = decodeLong(indexHeadRow, valueScratch);
    }
    long entryId = status.isOk() ? valueScratch.getLong(0) : 0;
    int visited = 0;
    while (status.isOk() && entryId != 0) {
      if (visited++ >= MAXIMUM_DUPLICATE_CHAIN) return StatusCode.CORRUPTION;
      status = readEntry(indexTable, fingerprint, entryId);
      if (!status.isOk()) return status;
      long indexedPrimaryKey = indexEntryScratch.getLong(8);
      if (indexedPrimaryKey != primaryKey) status = compareTextCandidate(
          baseTable, candidate, column, indexedPrimaryKey);
      if (status == StatusCode.CONFLICT) return status;
      entryId = indexEntryScratch.getLong(16);
    }
    return status;
  }

  private StatusCode compareTextCandidate(
      TableDefinition baseTable,
      ByteBuffer candidate,
      int column,
      long indexedPrimaryKey) {
    StatusCode status = fetch(baseTable, indexedPrimaryKey, indexedKeyRow);
    if (status.isOk()) status = copyRow(baseTable, indexedKeyRow, rowScratch);
    return status.isOk() && textEquals(baseTable, candidate, rowScratch, column)
        ? StatusCode.CONFLICT : status;
  }

  StatusCode insertNonUnique(
      TableDefinition indexTable,
      long value,
      long primaryKey) {
    LongResult allocation = allocateEntry(indexTable);
    if (!allocation.status.isOk()) {
      return allocation.status;
    }
    long headKey = value;
    StatusCode status = fetch(indexTable, headKey, indexHeadRow);
    boolean headExists = status.isOk();
    long previousHead = 0;
    if (headExists) {
      status = decodeLong(indexHeadRow, valueScratch);
      previousHead = status.isOk() ? valueScratch.getLong(0) : 0;
      if (status.isOk() && !validNonUniqueEntryId(previousHead)) {
        status = StatusCode.CORRUPTION;
      }
    } else if (status == StatusCode.CONFLICT) {
      status = StatusCode.OK;
    }
    if (status.isOk()) {
      encodeEntry(nonUniqueIndexRow, value, primaryKey, previousHead);
      status = insertAuxiliary(indexTable, allocation.value, nonUniqueIndexRow);
    }
    if (status.isOk()) {
      encodeLong(indexRow, allocation.value);
      status = headExists
          ? update(indexTable, headKey, indexRow)
          : insert(indexTable, headKey, indexRow);
    }
    return status;
  }

  private LongResult allocateEntry(TableDefinition indexTable) {
    LongResult result = allocationResult;
    StatusCode status = fetchAuxiliary(
        indexTable, NON_UNIQUE_ALLOCATOR_KEY, indexAllocatorRow);
    long entryId = 1;
    if (status.isOk()) {
      status = decodeLong(indexAllocatorRow, valueScratch);
      entryId = status.isOk() ? valueScratch.getLong(0) : 0;
      if (status.isOk() && entryId <= 0) {
        status = StatusCode.CORRUPTION;
      } else if (status.isOk() && entryId == Long.MAX_VALUE) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      }
      if (status.isOk()) {
        encodeLong(indexRow, entryId + 1);
        status = updateAuxiliary(indexTable, NON_UNIQUE_ALLOCATOR_KEY, indexRow);
      }
    } else if (status == StatusCode.CONFLICT) {
      encodeLong(indexRow, 2);
      status = insertAuxiliary(indexTable, NON_UNIQUE_ALLOCATOR_KEY, indexRow);
    }
    result.status = status;
    result.value = entryId;
    return result;
  }

  StatusCode ensureNonUnique(
      TableDefinition indexTable,
      long value,
      long primaryKey) {
    StatusCode status = fetch(indexTable, value, indexHeadRow);
    if (status == StatusCode.CONFLICT) {
      return insertNonUnique(indexTable, value, primaryKey);
    }
    if (status.isOk()) {
      status = decodeLong(indexHeadRow, valueScratch);
    }
    long entryId = status.isOk() ? valueScratch.getLong(0) : 0;
    if (status.isOk() && !validNonUniqueEntryId(entryId)) return StatusCode.CORRUPTION;
    status = findNonUniqueEntry(indexTable, value, primaryKey, entryId, status);
    return status == StatusCode.CONFLICT
        ? insertNonUnique(indexTable, value, primaryKey) : status;
  }

  private StatusCode findNonUniqueEntry(
      TableDefinition indexTable,
      long value,
      long primaryKey,
      long entryId,
      StatusCode initialStatus) {
    StatusCode status = initialStatus;
    int visited = 0;
    while (status.isOk() && entryId != 0) {
      if (visited++ >= MAXIMUM_DUPLICATE_CHAIN) return StatusCode.CORRUPTION;
      status = readEntry(indexTable, value, entryId);
      if (!status.isOk()) return status;
      if (indexEntryScratch.getLong(8) == primaryKey) return StatusCode.OK;
      entryId = indexEntryScratch.getLong(16);
    }
    return status.isOk() ? StatusCode.CONFLICT : status;
  }

  StatusCode deleteUnique(TableDefinition indexTable, long value) {
    return delete(indexTable, value);
  }

  StatusCode deleteNonUnique(
      TableDefinition indexTable,
      long value,
      long primaryKey) {
    long headKey = value;
    StatusCode status = fetch(indexTable, headKey, indexHeadRow);
    if (!status.isOk()) {
      return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
    }
    status = decodeLong(indexHeadRow, valueScratch);
    long entryId = status.isOk() ? valueScratch.getLong(0) : 0;
    if (!status.isOk()) {
      return status;
    }
    if (!validNonUniqueEntryId(entryId)) {
      return StatusCode.CORRUPTION;
    }
    status = findDeleteTarget(indexTable, value, primaryKey, entryId);
    if (status.isOk()) {
      status = unlinkDeleteTarget(indexTable, headKey);
    }
    return status.isOk()
        ? deleteAuxiliary(indexTable, deleteTarget.entryId) : status;
  }

  private StatusCode findDeleteTarget(
      TableDefinition indexTable,
      long value,
      long primaryKey,
      long firstEntryId) {
    deleteTarget.reset();
    long entryId = firstEntryId;
    int visited = 0;
    while (entryId != 0) {
      if (visited++ >= MAXIMUM_DUPLICATE_CHAIN) {
        return StatusCode.CORRUPTION;
      }
      StatusCode status = readEntry(indexTable, value, entryId);
      if (!status.isOk()) {
        return status;
      }
      long nextEntryId = indexEntryScratch.getLong(16);
      if (indexEntryScratch.getLong(8) == primaryKey) {
        deleteTarget.entryId = entryId;
        deleteTarget.nextEntryId = nextEntryId;
        return StatusCode.OK;
      }
      deleteTarget.previousEntryId = entryId;
      entryId = nextEntryId;
    }
    return StatusCode.CORRUPTION;
  }

  private StatusCode unlinkDeleteTarget(TableDefinition indexTable, long headKey) {
    if (deleteTarget.previousEntryId == 0) {
      if (deleteTarget.nextEntryId == 0) {
        return delete(indexTable, headKey);
      }
      encodeLong(indexRow, deleteTarget.nextEntryId);
      return update(indexTable, headKey, indexRow);
    }
    StatusCode status = fetchAuxiliary(
        indexTable, deleteTarget.previousEntryId, indexEntryRow);
    if (status.isOk()) {
      status = copyEntry(indexEntryRow, indexEntryScratch);
    }
    if (!status.isOk()) {
      return status;
    }
    if (indexEntryScratch.getLong(16) != deleteTarget.entryId) {
      return StatusCode.CORRUPTION;
    }
    indexEntryScratch.putLong(16, deleteTarget.nextEntryId);
    indexEntryScratch.position(0);
    indexEntryScratch.limit(NON_UNIQUE_ENTRY_BYTES);
    return updateAuxiliary(
        indexTable, deleteTarget.previousEntryId, indexEntryScratch);
  }

  private StatusCode readEntry(
      TableDefinition indexTable,
      long expectedValue,
      long entryId) {
    StatusCode status = fetchAuxiliary(indexTable, entryId, indexEntryRow);
    if (status == StatusCode.CONFLICT) {
      return StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      status = copyEntry(indexEntryRow, indexEntryScratch);
    }
    if (!status.isOk()) {
      return status;
    }
    long nextEntryId = indexEntryScratch.getLong(16);
    return indexEntryScratch.getLong(0) == expectedValue
            && nextEntryId >= 0
            && nextEntryId < Long.MAX_VALUE
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode insert(TableDefinition table, long key, ByteBuffer row) {
    StatusCode status = physicalKey(table, key);
    return status.isOk()
        ? transaction.insert(physicalKey.space(), physicalKey.key(), row) : status;
  }

  private StatusCode update(TableDefinition table, long key, ByteBuffer row) {
    StatusCode status = physicalKey(table, key);
    return status.isOk()
        ? transaction.update(physicalKey.space(), physicalKey.key(), row) : status;
  }

  private StatusCode delete(TableDefinition table, long key) {
    StatusCode status = physicalKey(table, key);
    return status.isOk()
        ? transaction.delete(physicalKey.space(), physicalKey.key()) : status;
  }

  private StatusCode fetch(TableDefinition table, long key, HeapRowResult result) {
    StatusCode status = physicalKey(table, key);
    return status.isOk()
        ? transaction.fetchByKey(physicalKey.space(), physicalKey.key(), result) : status;
  }

  private StatusCode insertAuxiliary(
      TableDefinition table, long key, ByteBuffer row) {
    return validTable(table)
        ? transaction.insert(RelationalKey.auxiliarySpace(table.tableId()), key, row)
        : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode updateAuxiliary(
      TableDefinition table, long key, ByteBuffer row) {
    return validTable(table)
        ? transaction.update(RelationalKey.auxiliarySpace(table.tableId()), key, row)
        : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode deleteAuxiliary(TableDefinition table, long key) {
    return validTable(table)
        ? transaction.delete(RelationalKey.auxiliarySpace(table.tableId()), key)
        : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode fetchAuxiliary(
      TableDefinition table, long key, HeapRowResult result) {
    return validTable(table)
        ? transaction.fetchByKey(
            RelationalKey.auxiliarySpace(table.tableId()), key, result)
        : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode physicalKey(TableDefinition table, long key) {
    return !validTable(table)
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : RelationalKey.tableRowKey(table.tableId(), key, physicalKey);
  }

  private boolean validTable(TableDefinition table) {
    return table != null && table.isOwnedBy(schemaGate);
  }

  static boolean validNonUniqueEntryId(long entryId) {
    return entryId > 0 && entryId < Long.MAX_VALUE;
  }

  static void encodeLong(ByteBuffer target, long value) {
    target.clear();
    target.putLong(0, value);
    target.position(0);
    target.limit(Long.BYTES);
  }

  static StatusCode decodeLong(HeapRowResult source, ByteBuffer target) {
    target.clear();
    return source.length() == Long.BYTES
        ? source.copyTo(target) : StatusCode.CORRUPTION;
  }

  static StatusCode copyEntry(HeapRowResult source, ByteBuffer target) {
    if (source.length() != NON_UNIQUE_ENTRY_BYTES) {
      return StatusCode.CORRUPTION;
    }
    target.clear();
    StatusCode status = source.copyTo(target);
    if (status.isOk()) {
      target.position(0);
      target.limit(NON_UNIQUE_ENTRY_BYTES);
    }
    return status;
  }

  static void encodeEntry(
      ByteBuffer target,
      long value,
      long primaryKey,
      long nextEntryId) {
    target.clear();
    target.putLong(0, value);
    target.putLong(8, primaryKey);
    target.putLong(16, nextEntryId);
    target.position(0);
    target.limit(NON_UNIQUE_ENTRY_BYTES);
  }

  static long indexedValue(TableDefinition table, ByteBuffer row, int slot) {
    int column = table.uniqueIndexColumn(slot);
    return table.isVarchar(column)
        ? textFingerprint(table, row, column)
        : row.getLong(row.position() + table.valueOffset(column));
  }

  static long textFingerprint(TableDefinition table, ByteBuffer row, int column) {
    int offset = table.textOffset(row, column);
    int length = table.textLength(row, column);
    if (offset < 0 || length < 0) {
      return Long.MIN_VALUE;
    }
    long hash = 0xcbf29ce484222325L;
    for (int index = 0; index < length; index++) {
      hash ^= Byte.toUnsignedLong(row.get(row.position() + offset + index));
      hash *= 0x100000001b3L;
    }
    return hash;
  }

  private static boolean textEquals(
      TableDefinition table,
      ByteBuffer left,
      ByteBuffer right,
      int column) {
    int leftOffset = table.textOffset(left, column);
    int leftLength = table.textLength(left, column);
    int rightOffset = table.textOffset(right, column);
    int rightLength = table.textLength(right, column);
    return leftOffset >= 0
        && rightOffset >= 0
        && leftLength == rightLength
        && Utf8Text.compare(
            left,
            left.position() + leftOffset,
            leftLength,
            right,
            right.position() + rightOffset,
            rightLength) == 0;
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

  private static final class DeleteTarget {
    private long previousEntryId;
    private long entryId;
    private long nextEntryId;

    private void reset() {
      previousEntryId = 0;
      entryId = 0;
      nextEntryId = 0;
    }
  }

  private static final class LongResult {
    private StatusCode status;
    private long value;
  }
}
