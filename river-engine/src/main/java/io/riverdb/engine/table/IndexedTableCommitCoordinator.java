package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.storage.heap.HeapInsertResult;
import java.nio.ByteBuffer;

/** Chooses compact logical commits or the staged-page fallback for table mutations. */
final class IndexedTableCommitCoordinator {
  private static final long BOOTSTRAP_TRANSACTION_ID = 1;

  private final IndexedTableStore store;
  private final IndexedTableKernel kernel;

  IndexedTableCommitCoordinator(IndexedTableStore tableStore, IndexedTableKernel tableKernel) {
    store = tableStore;
    kernel = tableKernel;
  }

  StatusCode insert(
      long transactionId,
      int space,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    return insertCommitted(
        transactionId, store.nextCommitSequence(), space, key, row, result);
  }

  StatusCode commitInsert(
      long transactionId,
      int space,
      long key,
      ByteBuffer row,
      IndexedCommitResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    long commitSequence = store.nextCommitSequence();
    HeapInsertResult inserted = kernel.heapInsertResult();
    StatusCode status = insertCommitted(
        transactionId, commitSequence, space, key, row, inserted);
    if (status.isOk()) {
      result.set(inserted.rowId(), commitSequence);
    }
    return status;
  }

  StatusCode commitInserts(
      long transactionId,
      int[] spaces,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount,
      IndexedCommitResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID
        || keys == null
        || spaces == null
        || rows == null
        || rowStride <= 0
        || rowLengths == null
        || insertCount <= 0
        || insertCount > keys.length
        || insertCount > spaces.length
        || insertCount > rowLengths.length
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (insertCount == 1) {
      rows.position(0);
      rows.limit(rowLengths[0]);
      return commitInsert(transactionId, spaces[0], keys[0], rows, result);
    }
    long commitSequence = store.nextCommitSequence();
    HeapInsertResult inserted = kernel.heapInsertResult();
    StatusCode status = store.commitInsertBatch(
        transactionId,
        commitSequence,
        spaces,
        keys,
        rows,
        rowStride,
        rowLengths,
        insertCount,
        inserted);
    if (status.isOk()) {
      result.set(inserted.rowId(), commitSequence);
      return StatusCode.OK;
    }
    if (status != StatusCode.RESOURCE_EXHAUSTED) {
      return status;
    }
    status = store.beginOperation();
    if (!status.isOk()) {
      return status;
    }
    status = kernel.stageInsertBatch(
        spaces, keys, rows, rowStride, rowLengths, insertCount, inserted);
    if (!status.isOk()) {
      store.cancelOperation();
      return status;
    }
    status = store.commit(transactionId, commitSequence);
    if (status.isOk()) {
      result.set(inserted.rowId(), commitSequence);
    }
    return status;
  }

  StatusCode commitMutations(
      long transactionId,
      int[] operations,
      int[] spaces,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount,
      IndexedCommitResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    long commitSequence = store.nextCommitSequence();
    HeapInsertResult inserted = kernel.heapInsertResult();
    StatusCode status = store.commitMutationBatch(
        transactionId,
        commitSequence,
        operations,
        spaces,
        keys,
        previousRowIds,
        rows,
        rowStride,
        rowLengths,
        mutationCount,
        inserted);
    if (status.isOk()) {
      result.set(inserted.rowId(), commitSequence);
      return StatusCode.OK;
    }
    if (status != StatusCode.RESOURCE_EXHAUSTED) {
      return status;
    }
    status = store.beginOperation();
    if (!status.isOk()) {
      return status;
    }
    status = kernel.stageMutationBatch(
        operations,
        spaces,
        keys,
        previousRowIds,
        rows,
        rowStride,
        rowLengths,
        mutationCount,
        inserted);
    if (!status.isOk()) {
      store.cancelOperation();
      return status;
    }
    status = store.commit(transactionId, commitSequence);
    if (status.isOk()) {
      result.set(inserted.rowId(), commitSequence);
    }
    return status;
  }

  StatusCode commitMutations(
      long transactionId,
      PendingMutationBuffer mutations,
      IndexedCommitResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID
        || mutations == null
        || mutations.count() <= 0
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    long commitSequence = store.nextCommitSequence();
    HeapInsertResult inserted = kernel.heapInsertResult();
    boolean mixed = mutations.containsNonInsertMutation();
    StatusCode status = mixed
        ? store.commitMutationBatch(transactionId, commitSequence, mutations, inserted)
        : store.commitInsertBatch(transactionId, commitSequence, mutations, inserted);
    if (status.isOk()) {
      result.set(inserted.rowId(), commitSequence);
      return StatusCode.OK;
    }
    if (status != StatusCode.RESOURCE_EXHAUSTED) {
      return status;
    }
    status = store.beginOperation();
    if (!status.isOk()) {
      return status;
    }
    status = mixed
        ? kernel.stageMutationBatch(mutations, inserted)
        : kernel.stageInsertBatch(mutations, inserted);
    if (!status.isOk()) {
      store.cancelOperation();
      return status;
    }
    status = store.commit(transactionId, commitSequence);
    if (status.isOk()) {
      result.set(inserted.rowId(), commitSequence);
    }
    return status;
  }

  StatusCode vacuum(long transactionId, IndexedVacuumResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return store.commitVacuum(transactionId, store.nextCommitSequence(), result);
  }

  StatusCode insertCommitted(
      long transactionId,
      long commitSequence,
      int space,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID
        || commitSequence <= 0
        || !OrderedKey.isFiniteSpace(space)
        || row == null
        || !row.hasRemaining()
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int leafPageId = kernel.findLeafPageId(space, key);
    StatusCode status = kernel.validateNewIndexEntryAt(leafPageId, space, key, 0);
    if (!status.isOk() && status != StatusCode.RESOURCE_EXHAUSTED) {
      return status;
    }
    if (!kernel.canAppendRow(row.remaining())) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (status.isOk()) {
      return store.commitInsert(
          transactionId, commitSequence, space, key, row, result);
    }
    status = store.beginOperation();
    if (!status.isOk()) {
      return status;
    }
    status = kernel.stageInsert(leafPageId, space, key, row);
    if (!status.isOk()) {
      store.cancelOperation();
      return status;
    }
    status = store.commit(transactionId, commitSequence);
    if (status.isOk()) {
      result.setRowId(kernel.heapInsertResult().rowId());
    }
    return status;
  }

}
