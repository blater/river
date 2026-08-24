package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.heap.HeapInsertResult;
import java.nio.ByteBuffer;

/** Stages row versions and their corresponding index entries. */
final class IndexedTableMutationStager {
  private final IndexedTableKernel table;
  private final IndexedPageSet pages;

  IndexedTableMutationStager(IndexedTableKernel table, IndexedPageSet pages) {
    this.table = table;
    this.pages = pages;
  }

  StatusCode stageInsertBatch(
      int[] spaces, long[] keys, ByteBuffer rows, int rowStride,
      int[] rowLengths, int insertCount, HeapInsertResult result) {
    if (spaces == null || keys == null || rows == null || rowStride <= 0
        || rowLengths == null || insertCount <= 0 || insertCount > keys.length
        || insertCount > spaces.length || insertCount > rowLengths.length
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    for (int index = 0; index < insertCount; index++) {
      StatusCode status = stageInsertEntry(
          spaces[index], keys[index], rows, index * rowStride,
          rowLengths[index], rowStride);
      if (!status.isOk()) return status;
    }
    result.setRowId(table.heapInsertResult().rowId());
    return StatusCode.OK;
  }

  private StatusCode stageInsertEntry(
      int space, long key, ByteBuffer rows, int rowOffset, int rowBytes, int rowStride) {
    if (!OrderedKey.isFiniteSpace(space) || rowBytes <= 0 || rowBytes > rowStride
        || rows.limit() - rowOffset < rowBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int leafPageId = table.findOperationLeafPageId(space, key);
    if (leafPageId <= 0) return StatusCode.CORRUPTION;
    ByteBuffer leaf = pages.stageExisting(
        leafPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (leaf == null) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = table.validateNewIndexEntryIn(leaf, space, key, 0);
    if (!status.isOk() && status != StatusCode.RESOURCE_EXHAUSTED) return status;
    status = table.stageVersionRow(
        rows, rowOffset, rowBytes, 0, false, table.heapInsertResult());
    if (!status.isOk()) return status;
    return applyIndexEntry(
        leafPageId, leaf, space, key, true, table.heapInsertResult().rowId());
  }

  StatusCode stageMutationBatch(
      int[] operations, int[] spaces, long[] keys, int[] previousRowIds,
      ByteBuffer rows, int rowStride, int[] rowLengths, int mutationCount,
      HeapInsertResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    for (int index = 0; index < mutationCount; index++) {
      StatusCode status = stageMutationEntry(
          operations[index], spaces[index], keys[index], previousRowIds[index], rows,
          index * rowStride, rowLengths[index], rowStride);
      if (!status.isOk()) return status;
    }
    result.setRowId(table.heapInsertResult().rowId());
    return StatusCode.OK;
  }

  private StatusCode stageMutationEntry(
      int operation, int space, long key, int previousRowId, ByteBuffer rows,
      int rowOffset, int rowBytes, int rowStride) {
    if (!validMutation(operation) || !OrderedKey.isFiniteSpace(space)
        || rowBytes <= 0 || rowBytes > rowStride
        || rows.limit() - rowOffset < rowBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int leafPageId = table.findOperationLeafPageId(space, key);
    if (leafPageId <= 0) return StatusCode.CORRUPTION;
    ByteBuffer leaf = pages.stageExisting(
        leafPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (leaf == null) return StatusCode.RESOURCE_EXHAUSTED;
    boolean newIndexEntry = operation == IndexedWalCodec.MUTATION_INSERT
        && previousRowId == 0;
    StatusCode status = table.validateMutationTargetIn(
        leaf, operation, space, key, previousRowId, 0);
    if (!status.isOk() && (!newIndexEntry || status != StatusCode.RESOURCE_EXHAUSTED)) {
      return status;
    }
    status = table.stageVersionRow(
        rows, rowOffset, rowBytes, previousRowId,
        operation == IndexedWalCodec.MUTATION_DELETE, table.heapInsertResult());
    return status.isOk()
        ? applyIndexEntry(
            leafPageId, leaf, space, key, newIndexEntry,
            table.heapInsertResult().rowId())
        : status;
  }

  StatusCode stagePendingMutations(PendingMutationBuffer mutations, HeapInsertResult result) {
    if (mutations == null || mutations.count() <= 0 || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    for (int index = 0; index < mutations.count(); index++) {
      StatusCode status = stagePendingMutation(mutations, index);
      if (!status.isOk()) return status;
    }
    result.setRowId(table.heapInsertResult().rowId());
    return StatusCode.OK;
  }

  private StatusCode stagePendingMutation(PendingMutationBuffer mutations, int index) {
    int operation = mutations.operationAt(index);
    int space = mutations.spaceAt(index);
    long key = mutations.keyAt(index);
    int previousRowId = mutations.previousRowIdAt(index);
    int rowBytes = mutations.rowLengthAt(index);
    if (!validMutation(operation) || !OrderedKey.isFiniteSpace(space)
        || rowBytes <= 0 || rowBytes > mutations.rowStride()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int leafPageId = table.findOperationLeafPageId(space, key);
    if (leafPageId <= 0) return StatusCode.CORRUPTION;
    ByteBuffer leaf = pages.stageExisting(
        leafPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (leaf == null) return StatusCode.RESOURCE_EXHAUSTED;
    boolean newIndexEntry = operation == IndexedWalCodec.MUTATION_INSERT
        && previousRowId == 0;
    StatusCode status = table.validateMutationTargetIn(
        leaf, operation, space, key, previousRowId, 0);
    if (!status.isOk()
        && (!newIndexEntry || status != StatusCode.RESOURCE_EXHAUSTED)) return status;
    status = table.stageVersionRow(
        mutations, index, previousRowId,
        operation == IndexedWalCodec.MUTATION_DELETE, table.heapInsertResult());
    return status.isOk()
        ? applyIndexEntry(
            leafPageId, leaf, space, key, newIndexEntry,
            table.heapInsertResult().rowId())
        : status;
  }

  StatusCode stagePendingInserts(PendingMutationBuffer mutations, HeapInsertResult result) {
    if (mutations == null || mutations.count() <= 0 || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    for (int index = 0; index < mutations.count(); index++) {
      StatusCode status = stagePendingInsert(mutations, index);
      if (!status.isOk()) return status;
    }
    result.setRowId(table.heapInsertResult().rowId());
    return StatusCode.OK;
  }

  private StatusCode stagePendingInsert(PendingMutationBuffer mutations, int index) {
    int space = mutations.spaceAt(index);
    long key = mutations.keyAt(index);
    int rowBytes = mutations.rowLengthAt(index);
    if (!OrderedKey.isFiniteSpace(space) || rowBytes <= 0
        || rowBytes > mutations.rowStride()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int leafPageId = table.findOperationLeafPageId(space, key);
    if (leafPageId <= 0) return StatusCode.CORRUPTION;
    ByteBuffer leaf = pages.stageExisting(
        leafPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (leaf == null) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = table.validateNewIndexEntryIn(leaf, space, key, 0);
    if (!status.isOk() && status != StatusCode.RESOURCE_EXHAUSTED) return status;
    status = table.stageVersionRow(mutations, index, 0, false, table.heapInsertResult());
    return status.isOk()
        ? applyIndexEntry(
            leafPageId, leaf, space, key, true, table.heapInsertResult().rowId())
        : status;
  }

  private StatusCode applyIndexEntry(
      int leafPageId, ByteBuffer leaf, int space, long key,
      boolean newIndexEntry, int rowId) {
    StatusCode status = newIndexEntry
        ? BTreePage.insertLeaf(leaf, space, key, rowId)
        : BTreePage.updateLeaf(leaf, space, key, rowId);
    return newIndexEntry && status == StatusCode.RESOURCE_EXHAUSTED
        ? table.splitIndexLeaf(leafPageId, leaf, space, key, rowId) : status;
  }

  private static boolean validMutation(int operation) {
    return operation == IndexedWalCodec.MUTATION_INSERT
        || operation == IndexedWalCodec.MUTATION_UPDATE
        || operation == IndexedWalCodec.MUTATION_DELETE;
  }
}
