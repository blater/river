package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Applies validated indexed-table insert and mutation records to a table. */
final class IndexedTableWalApplier {
  private final IndexedTableKernel table;
  private final IndexedPageSet pages;

  IndexedTableWalApplier(IndexedTableKernel table, IndexedPageSet pages) {
    this.table = table;
    this.pages = pages;
  }

  StatusCode applyInsert(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    StatusCode structural = IndexedWalCodec.validateInsert(payload);
    if (!structural.isOk()) {
      return structural;
    }
    long key = IndexedWalCodec.insertKey(payload);
    int space = IndexedWalCodec.insertSpace(payload);
    int rowId = IndexedWalCodec.insertRowId(payload);
    int rowBytes = IndexedWalCodec.insertRowBytes(payload);
    if (!OrderedKey.isFiniteSpace(space)
        || !pages.isPresent(IndexedTableKernel.HEAP_PAGE_ID)) {
      return StatusCode.CORRUPTION;
    }
    StatusCode target = table.validateNewIndexEntry(space, key, 0);
    int leafPageId = table.validatedLeafPageId();
    ByteBuffer leaf = pages.currentPayload(leafPageId);
    if (!target.isOk()
        || table.rowCount() + 1 != rowId
        || !table.canAppendRow(rowBytes)
        || leaf == null) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = table.appendCurrentRow(
        payload,
        IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES,
        rowBytes,
        rowId,
        recordStart,
        recordEnd,
        commitSequence,
        0,
        false);
    if (status.isOk()) {
      status = BTreePage.insertLeaf(leaf, space, key, rowId);
    }
    if (!status.isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    pages.markCurrentChanged(leafPageId, recordStart, recordEnd);
    return StatusCode.OK;
  }

  StatusCode applyInsertBatch(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    StatusCode structural = IndexedWalCodec.validateInsertBatch(
        payload, IndexedTableLimits.MAX_ROWS);
    if (!structural.isOk()) {
      return structural;
    }
    if (!pages.isPresent(IndexedTableKernel.HEAP_PAGE_ID)) {
      return StatusCode.CORRUPTION;
    }
    int insertCount = IndexedWalCodec.batchEntryCount(payload);
    int firstRowId = table.rowCount() + 1;
    int entryOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    for (int index = 0; index < insertCount; index++) {
      StatusCode status = validateInsertEntry(
          payload, entryOffset, firstRowId + index);
      if (!status.isOk()) return status;
      entryOffset += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES
          + IndexedWalCodec.insertBatchRowBytes(payload, entryOffset);
    }
    if (entryOffset != payload.limit()) {
      return StatusCode.CORRUPTION;
    }
    if (!table.canAppendEncodedRows(
        payload,
        IndexedWalCodec.INSERT_BATCH_HEADER_BYTES,
        insertCount,
        12,
        IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES)) {
      return StatusCode.CORRUPTION;
    }
    entryOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    for (int index = 0; index < insertCount; index++) {
      StatusCode status = applyInsertEntry(
          payload, entryOffset, recordStart, recordEnd, commitSequence);
      if (!status.isOk()) return status;
      entryOffset += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES
          + IndexedWalCodec.insertBatchRowBytes(payload, entryOffset);
    }
    return StatusCode.OK;
  }

  private StatusCode validateInsertEntry(
      ByteBuffer payload, int entryOffset, int expectedRowId) {
    if (!IndexedWalCodec.validInsertBatchEntry(payload, entryOffset)) {
      return StatusCode.CORRUPTION;
    }
    long key = IndexedWalCodec.insertBatchKey(payload, entryOffset);
    int space = IndexedWalCodec.insertBatchSpace(payload, entryOffset);
    if (!OrderedKey.isFiniteSpace(space)
        || IndexedWalCodec.insertBatchRowId(payload, entryOffset) != expectedRowId
        || containsEarlierInsertKey(payload, entryOffset, space, key)) {
      return StatusCode.CORRUPTION;
    }
    int leafPageId = table.findLeafPageId(space, key);
    int earlierInLeaf = countEarlierInsertEntriesInLeaf(
        payload, entryOffset, leafPageId);
    return table.validateNewIndexEntryAt(leafPageId, space, key, earlierInLeaf).isOk()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode applyInsertEntry(
      ByteBuffer payload,
      int entryOffset,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    long key = IndexedWalCodec.insertBatchKey(payload, entryOffset);
    int space = IndexedWalCodec.insertBatchSpace(payload, entryOffset);
    int rowId = IndexedWalCodec.insertBatchRowId(payload, entryOffset);
    int rowBytes = IndexedWalCodec.insertBatchRowBytes(payload, entryOffset);
    int rowOffset = entryOffset + IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES;
    int leafPageId = table.findLeafPageId(space, key);
    ByteBuffer leaf = pages.currentPayload(leafPageId);
    StatusCode status = table.appendCurrentRow(
        payload, rowOffset, rowBytes, rowId, recordStart, recordEnd,
        commitSequence, 0, false);
    if (status.isOk()) status = BTreePage.insertLeaf(leaf, space, key, rowId);
    if (!status.isOk()) return StatusCode.INVARIANT_BROKEN;
    pages.markCurrentChanged(leafPageId, recordStart, recordEnd);
    return StatusCode.OK;
  }

  StatusCode applyMutationBatch(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    StatusCode structural = IndexedWalCodec.validateMutationBatch(
        payload, IndexedTableLimits.MAX_ROWS);
    if (!structural.isOk()) {
      return structural;
    }
    if (!pages.isPresent(IndexedTableKernel.HEAP_PAGE_ID)) {
      return StatusCode.CORRUPTION;
    }
    int mutationCount = IndexedWalCodec.batchEntryCount(payload);
    int firstRowId = table.rowCount() + 1;
    int entryOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutationCount; index++) {
      StatusCode status = validateMutationEntry(
          payload, entryOffset, firstRowId + index);
      if (!status.isOk()) return status;
      entryOffset += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES
          + IndexedWalCodec.mutationRowBytes(payload, entryOffset);
    }
    if (entryOffset != payload.limit()) {
      return StatusCode.CORRUPTION;
    }
    if (!table.canAppendEncodedRows(
        payload,
        IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES,
        mutationCount,
        20,
        IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES)) {
      return StatusCode.CORRUPTION;
    }
    entryOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutationCount; index++) {
      StatusCode status = applyMutationEntry(
          payload, entryOffset, recordStart, recordEnd, commitSequence);
      if (!status.isOk()) return status;
      entryOffset += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES
          + IndexedWalCodec.mutationRowBytes(payload, entryOffset);
    }
    return StatusCode.OK;
  }

  private StatusCode validateMutationEntry(
      ByteBuffer payload, int entryOffset, int expectedRowId) {
    if (!IndexedWalCodec.validMutationBatchEntry(payload, entryOffset)) {
      return StatusCode.CORRUPTION;
    }
    int operation = IndexedWalCodec.mutationOperation(payload, entryOffset);
    int space = IndexedWalCodec.mutationSpace(payload, entryOffset);
    long key = IndexedWalCodec.mutationKey(payload, entryOffset);
    int previousRowId = IndexedWalCodec.mutationPreviousRowId(payload, entryOffset);
    if (!OrderedKey.isFiniteSpace(space)
        || IndexedWalCodec.mutationRowId(payload, entryOffset) != expectedRowId
        || containsEarlierMutationKey(payload, entryOffset, space, key)) {
      return StatusCode.CORRUPTION;
    }
    int leafPageId = table.findLeafPageId(space, key);
    int earlierInLeaf = countEarlierMutationInsertsInLeaf(
        payload, entryOffset, leafPageId);
    return table.validateMutationTargetAt(
        leafPageId, operation, space, key, previousRowId, earlierInLeaf).isOk()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode applyMutationEntry(
      ByteBuffer payload,
      int entryOffset,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    int operation = IndexedWalCodec.mutationOperation(payload, entryOffset);
    int space = IndexedWalCodec.mutationSpace(payload, entryOffset);
    long key = IndexedWalCodec.mutationKey(payload, entryOffset);
    int rowId = IndexedWalCodec.mutationRowId(payload, entryOffset);
    int previousRowId = IndexedWalCodec.mutationPreviousRowId(payload, entryOffset);
    int rowBytes = IndexedWalCodec.mutationRowBytes(payload, entryOffset);
    int rowOffset = entryOffset + IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES;
    int leafPageId = table.findLeafPageId(space, key);
    ByteBuffer leaf = pages.currentPayload(leafPageId);
    StatusCode status = table.appendCurrentRow(
        payload, rowOffset, rowBytes, rowId, recordStart, recordEnd,
        commitSequence, previousRowId,
        operation == IndexedWalCodec.MUTATION_DELETE);
    if (status.isOk()) {
      status = operation == IndexedWalCodec.MUTATION_INSERT && previousRowId == 0
          ? BTreePage.insertLeaf(leaf, space, key, rowId)
          : BTreePage.updateLeaf(leaf, space, key, rowId);
    }
    if (!status.isOk()) return StatusCode.INVARIANT_BROKEN;
    pages.markCurrentChanged(leafPageId, recordStart, recordEnd);
    return StatusCode.OK;
  }

  private boolean containsEarlierInsertKey(
      ByteBuffer payload, int targetEntryOffset, int space, long key) {
    int entryOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (IndexedWalCodec.insertBatchSpace(payload, entryOffset) == space
          && IndexedWalCodec.insertBatchKey(payload, entryOffset) == key) {
        return true;
      }
      entryOffset += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES
          + IndexedWalCodec.insertBatchRowBytes(payload, entryOffset);
    }
    return false;
  }

  private boolean containsEarlierMutationKey(
      ByteBuffer payload, int targetEntryOffset, int space, long key) {
    int entryOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (IndexedWalCodec.mutationSpace(payload, entryOffset) == space
          && IndexedWalCodec.mutationKey(payload, entryOffset) == key) {
        return true;
      }
      entryOffset += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES
          + IndexedWalCodec.mutationRowBytes(payload, entryOffset);
    }
    return false;
  }

  private int countEarlierInsertEntriesInLeaf(
      ByteBuffer payload, int targetEntryOffset, int leafPageId) {
    int count = 0;
    int entryOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (table.findLeafPageId(
          IndexedWalCodec.insertBatchSpace(payload, entryOffset),
          IndexedWalCodec.insertBatchKey(payload, entryOffset)) == leafPageId) {
        count++;
      }
      entryOffset += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES
          + IndexedWalCodec.insertBatchRowBytes(payload, entryOffset);
    }
    return count;
  }

  private int countEarlierMutationInsertsInLeaf(
      ByteBuffer payload, int targetEntryOffset, int leafPageId) {
    int count = 0;
    int entryOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (IndexedWalCodec.mutationOperation(payload, entryOffset)
          == IndexedWalCodec.MUTATION_INSERT
          && IndexedWalCodec.mutationPreviousRowId(payload, entryOffset) == 0
          && table.findLeafPageId(
              IndexedWalCodec.mutationSpace(payload, entryOffset),
              IndexedWalCodec.mutationKey(payload, entryOffset)) == leafPageId) {
        count++;
      }
      entryOffset += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES
          + IndexedWalCodec.mutationRowBytes(payload, entryOffset);
    }
    return count;
  }
}
