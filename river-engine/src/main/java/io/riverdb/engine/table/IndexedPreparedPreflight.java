package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.storage.heap.HeapPage;

/** Owns bounded key, leaf, and heap-capacity admission for a prepared commit group. */
final class IndexedPreparedPreflight {
  private final IndexedTableKernel kernel;
  private final IndexedStorePhase phase;
  private final long[] keys = new long[IndexedTableLimits.MAX_OPERATION_ROWS];
  private final int[] spaces = new int[IndexedTableLimits.MAX_OPERATION_ROWS];
  private final int[] leafPageIds = new int[IndexedTableLimits.MAX_OPERATION_ROWS];
  private final boolean[] newIndexEntries = new boolean[IndexedTableLimits.MAX_OPERATION_ROWS];
  private int keyCount;
  private int heapBytes;

  IndexedPreparedPreflight(IndexedTableKernel tableKernel, IndexedStorePhase storePhase) {
    kernel = tableKernel;
    phase = storePhase;
  }

  StatusCode validate(PendingMutationBuffer mutations) {
    if (mutations == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return mutations.containsNonInsertMutation()
        ? validateMutations(mutations) : validateInserts(mutations);
  }

  private StatusCode validateInserts(PendingMutationBuffer mutations) {
    if (!validBatch(mutations)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int originalKeyCount = keyCount;
    int originalHeapBytes = heapBytes;
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < mutations.count(); index++) {
      long key = mutations.keyAt(index);
      int space = mutations.spaceAt(index);
      int rowBytes = mutations.rowLengthAt(index);
      status = validateInput(space, key, rowBytes, mutations.rowStride());
      int leafPageId = status.isOk() ? kernel.findLeafPageId(space, key) : 0;
      int entriesInLeaf = status.isOk() ? entriesInLeaf(leafPageId, false) : 0;
      status = status.isOk()
          ? kernel.validateNewIndexEntryAt(
              leafPageId, space, key, entriesInLeaf) : status;
      leafPageId = status.isOk() ? kernel.validatedLeafPageId() : leafPageId;
      status = addValidated(status, space, key, leafPageId, true, rowBytes);
    }
    rollback(originalKeyCount, originalHeapBytes, status);
    return status;
  }

  private StatusCode validateMutations(PendingMutationBuffer mutations) {
    if (!validBatch(mutations)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int originalKeyCount = keyCount;
    int originalHeapBytes = heapBytes;
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < mutations.count(); index++) {
      status = validateMutation(mutations, index);
    }
    rollback(originalKeyCount, originalHeapBytes, status);
    return status;
  }

  private StatusCode validateMutation(PendingMutationBuffer mutations, int index) {
    int operation = mutations.operationAt(index);
    int space = mutations.spaceAt(index);
    long key = mutations.keyAt(index);
    int previousRowId = mutations.previousRowIdAt(index);
    int rowBytes = mutations.rowLengthAt(index);
    StatusCode status = validMutation(operation)
        ? validateInput(space, key, rowBytes, mutations.rowStride())
        : StatusCode.INVALID_EXTERNAL_INPUT;
    int leafPageId = status.isOk() ? kernel.findLeafPageId(space, key) : 0;
    boolean newIndexEntry = operation == IndexedWalCodec.MUTATION_INSERT && previousRowId == 0;
    int entriesInLeaf = status.isOk() ? entriesInLeaf(leafPageId, true) : 0;
    if (status.isOk()) {
      status = kernel.validateMutationTargetAt(
          leafPageId, operation, space, key, previousRowId, entriesInLeaf);
      leafPageId = kernel.validatedLeafPageId();
    }
    return addValidated(
        status, space, key, leafPageId, newIndexEntry, rowBytes);
  }

  private StatusCode addValidated(
      StatusCode status,
      int space,
      long key,
      int leafPageId,
      boolean newIndexEntry,
      int rowBytes) {
    int required = HeapPage.SLOT_BYTES + rowBytes;
    if (status.isOk() && heapBytes + required > kernel.currentHeapAvailableBytes()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (status.isOk()) {
      spaces[keyCount] = space;
      keys[keyCount] = key;
      leafPageIds[keyCount] = leafPageId;
      newIndexEntries[keyCount] = newIndexEntry;
      keyCount++;
      heapBytes += required;
    }
    return status;
  }

  private boolean validBatch(PendingMutationBuffer mutations) {
    return phase.preparedInsertGroupActive()
        && !phase.preparedInsertEncoding()
        && mutations.count() > 0
        && keyCount + mutations.count() <= keys.length
        && kernel.rowCount() + keyCount + mutations.count() <= IndexedTableStore.MAX_ROWS;
  }

  private StatusCode validateInput(
      int space, long key, int rowBytes, int rowStride) {
    if (!OrderedKey.isFiniteSpace(space) || rowBytes <= 0 || rowBytes > rowStride) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int previous = 0; previous < keyCount; previous++) {
      if (spaces[previous] == space && keys[previous] == key) {
        return StatusCode.CONFLICT;
      }
    }
    return StatusCode.OK;
  }

  private static boolean validMutation(int operation) {
    return operation == IndexedWalCodec.MUTATION_INSERT
        || operation == IndexedWalCodec.MUTATION_UPDATE
        || operation == IndexedWalCodec.MUTATION_DELETE;
  }

  private int entriesInLeaf(int leafPageId, boolean onlyNewEntries) {
    int entries = 0;
    for (int previous = 0; previous < keyCount; previous++) {
      if ((!onlyNewEntries || newIndexEntries[previous])
          && leafPageIds[previous] == leafPageId) {
        entries++;
      }
    }
    return entries;
  }

  private void rollback(int originalKeyCount, int originalHeapBytes, StatusCode status) {
    if (status.isOk()) {
      return;
    }
    clearKeys(originalKeyCount);
    keyCount = originalKeyCount;
    heapBytes = originalHeapBytes;
  }

  void reset() {
    clearKeys(0);
    keyCount = 0;
    heapBytes = 0;
  }

  private void clearKeys(int first) {
    for (int index = first; index < keyCount; index++) {
      keys[index] = 0;
      spaces[index] = 0;
      leafPageIds[index] = 0;
      newIndexEntries[index] = false;
    }
  }

  int keyCount() {
    return keyCount;
  }
}
