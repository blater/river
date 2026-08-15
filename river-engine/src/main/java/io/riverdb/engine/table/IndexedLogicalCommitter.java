package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalAppendResult;
import io.riverdb.wal.local.LocalWalReservation;
import java.nio.ByteBuffer;

/** Owns validation, encoding, and application of compact logical WAL mutations. */
final class IndexedLogicalCommitter {
  private final LocalWal wal;
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedStorePhase phase;
  private final LocalWalReservation reservation = new LocalWalReservation();
  private final LocalWalAppendResult appendResult = new LocalWalAppendResult();
  private long walCopyBytes;
  private boolean failed;

  IndexedLogicalCommitter(
      LocalWal localWal,
      IndexedTableKernel tableKernel,
      IndexedPageSet pageSet,
      IndexedStorePhase storePhase) {
    wal = localWal;
    kernel = tableKernel;
    pages = pageSet;
    phase = storePhase;
  }

  StatusCode commitInsert(
      long transactionId,
      long commitSequence,
      int space,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    if (operationBusy()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int rowBytes = row.remaining();
    StatusCode status = kernel.validateNewIndexEntry(space, key, 0);
    if (!status.isOk()) {
      return status;
    }
    if (!kernel.canAppendRow(rowBytes)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int rowId = kernel.rowCount() + 1;
    int operationBytes = IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES + rowBytes;
    status = wal.reserve(operationBytes, reservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer payload = reservation.writablePayload();
    IndexedWalCodec.encodeInsertHeader(payload, space, key, rowId, rowBytes);
    copyRow(row, row.position(), payload, IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES, rowBytes);
    payload.position(operationBytes);
    status = publishInsert(transactionId, commitSequence, payload, true);
    if (status.isOk()) {
      result.setRowId(rowId);
    }
    return status;
  }

  StatusCode commitInsertBatch(
      long transactionId,
      long commitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    if (operationBusy()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = validatePendingInserts(mutations);
    if (!status.isOk()) {
      return status;
    }
    if (!kernel.canAppendRows(mutations)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int operationBytes = pendingInsertBytes(mutations);
    status = wal.reserve(operationBytes, reservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer payload = reservation.writablePayload();
    int firstRowId = kernel.rowCount() + 1;
    encodePendingInserts(mutations, payload, operationBytes, firstRowId);
    status = publishInsert(transactionId, commitSequence, payload, mutations.count() == 1);
    if (status.isOk()) {
      result.setRowId(firstRowId + mutations.count() - 1);
    }
    return status;
  }

  private StatusCode validatePendingInserts(PendingMutationBuffer mutations) {
    for (int index = 0; index < mutations.count(); index++) {
      StatusCode status = validatePendingInsert(mutations, index);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode validatePendingInsert(PendingMutationBuffer mutations, int index) {
    int rowBytes = mutations.rowLengthAt(index);
    int space = mutations.spaceAt(index);
    long key = mutations.keyAt(index);
    if (!OrderedKey.isFiniteSpace(space)
        || rowBytes <= 0 || rowBytes > mutations.rowStride()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int leafPageId = kernel.findLeafPageId(space, key);
    int earlierInLeaf = 0;
    for (int previous = 0; previous < index; previous++) {
      if (mutations.spaceAt(previous) == space && mutations.keyAt(previous) == key) {
        return StatusCode.CONFLICT;
      }
      if (kernel.findLeafPageId(
          mutations.spaceAt(previous), mutations.keyAt(previous)) == leafPageId) {
        earlierInLeaf++;
      }
    }
    return kernel.validateNewIndexEntryAt(leafPageId, space, key, earlierInLeaf);
  }

  private static int pendingInsertBytes(PendingMutationBuffer mutations) {
    if (mutations.count() == 1) {
      return IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES + mutations.rowLengthAt(0);
    }
    int bytes = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutations.count(); index++) {
      bytes += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES + mutations.rowLengthAt(index);
    }
    return bytes;
  }

  private void encodePendingInserts(
      PendingMutationBuffer mutations,
      ByteBuffer payload,
      int operationBytes,
      int firstRowId) {
    if (mutations.count() == 1) {
      IndexedWalCodec.encodeInsertHeader(
          payload, mutations.spaceAt(0), mutations.keyAt(0),
          firstRowId, mutations.rowLengthAt(0));
      mutations.copyRowTo(0, payload, IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES);
      walCopyBytes += mutations.rowLengthAt(0);
    } else {
      IndexedWalCodec.encodeInsertBatchHeader(payload, mutations.count());
      int outputOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
      for (int index = 0; index < mutations.count(); index++) {
        int rowBytes = mutations.rowLengthAt(index);
        IndexedWalCodec.encodeInsertBatchEntry(
            payload, outputOffset, mutations.spaceAt(index), mutations.keyAt(index),
            firstRowId + index, rowBytes);
        int rowOffset = outputOffset + IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES;
        mutations.copyRowTo(index, payload, rowOffset);
        walCopyBytes += rowBytes;
        outputOffset = rowOffset + rowBytes;
      }
    }
    payload.position(operationBytes);
  }

  StatusCode commitInsertBatch(
      long transactionId,
      long commitSequence,
      int[] spaces,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount,
      HeapInsertResult result) {
    if (operationBusy()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = validateRawInserts(
        spaces, keys, rows, rowStride, rowLengths, insertCount);
    if (!status.isOk()) {
      return status;
    }
    if (!canAppendRows(rowLengths, insertCount)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int operationBytes = rawInsertBytes(rowLengths, insertCount);
    status = wal.reserve(operationBytes, reservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer payload = reservation.writablePayload();
    int firstRowId = kernel.rowCount() + 1;
    encodeRawInserts(
        payload, spaces, keys, rows, rowStride, rowLengths,
        insertCount, firstRowId, operationBytes);
    status = publishInsert(transactionId, commitSequence, payload, false);
    if (status.isOk()) {
      result.setRowId(firstRowId + insertCount - 1);
    }
    return status;
  }

  private StatusCode validateRawInserts(
      int[] spaces,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount) {
    for (int index = 0; index < insertCount; index++) {
      StatusCode status = validateRawInsert(
          spaces, keys, rows, rowStride, rowLengths, index);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode validateRawInsert(
      int[] spaces, long[] keys, ByteBuffer rows,
      int rowStride, int[] rowLengths, int index) {
    int rowBytes = rowLengths[index];
    int rowOffset = index * rowStride;
    long key = keys[index];
    if (!OrderedKey.isFiniteSpace(spaces[index])
        || rowBytes <= 0
        || rowBytes > rowStride
        || rows.limit() - rowOffset < rowBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int previous = 0; previous < index; previous++) {
      if (spaces[previous] == spaces[index] && keys[previous] == key) {
        return StatusCode.CONFLICT;
      }
    }
    int leafPageId = kernel.findLeafPageId(spaces[index], key);
    int earlierInLeaf = 0;
    for (int previous = 0; previous < index; previous++) {
      if (kernel.findLeafPageId(spaces[previous], keys[previous]) == leafPageId) {
        earlierInLeaf++;
      }
    }
    return kernel.validateNewIndexEntryAt(
        leafPageId, spaces[index], key, earlierInLeaf);
  }

  private static int rawInsertBytes(int[] rowLengths, int insertCount) {
    int bytes = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    for (int index = 0; index < insertCount; index++) {
      bytes += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES + rowLengths[index];
    }
    return bytes;
  }

  private void encodeRawInserts(
      ByteBuffer payload,
      int[] spaces,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount,
      int firstRowId,
      int operationBytes) {
    IndexedWalCodec.encodeInsertBatchHeader(payload, insertCount);
    int outputOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    for (int index = 0; index < insertCount; index++) {
      int rowBytes = rowLengths[index];
      IndexedWalCodec.encodeInsertBatchEntry(
          payload, outputOffset, spaces[index], keys[index], firstRowId + index, rowBytes);
      int rowOffset = outputOffset + IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES;
      copyRow(rows, index * rowStride, payload, rowOffset, rowBytes);
      outputOffset = rowOffset + rowBytes;
    }
    payload.position(operationBytes);
  }

  StatusCode commitMutations(
      long transactionId,
      long commitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    if (operationBusy() || !pages.isPresent(IndexedTableKernel.HEAP_PAGE_ID)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = validatePendingMutations(mutations);
    if (!status.isOk()) {
      return status;
    }
    if (!kernel.canAppendRows(mutations)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int operationBytes = pendingMutationBytes(mutations);
    status = wal.reserve(operationBytes, reservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer payload = reservation.writablePayload();
    int firstRowId = kernel.rowCount() + 1;
    encodePendingMutations(mutations, payload, firstRowId, operationBytes);
    status = publishMutations(transactionId, commitSequence, payload);
    if (status.isOk()) {
      result.setRowId(firstRowId + mutations.count() - 1);
    }
    return status;
  }

  private StatusCode validatePendingMutations(PendingMutationBuffer mutations) {
    for (int index = 0; index < mutations.count(); index++) {
      StatusCode status = validatePendingMutation(mutations, index);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode validatePendingMutation(PendingMutationBuffer mutations, int index) {
    int operation = mutations.operationAt(index);
    int space = mutations.spaceAt(index);
    long key = mutations.keyAt(index);
    int previousRowId = mutations.previousRowIdAt(index);
    int rowBytes = mutations.rowLengthAt(index);
    if (!validMutation(operation)
        || !OrderedKey.isFiniteSpace(space)
        || rowBytes <= 0
        || rowBytes > mutations.rowStride()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int previous = 0; previous < index; previous++) {
      if (mutations.spaceAt(previous) == space && mutations.keyAt(previous) == key) {
        return StatusCode.CONFLICT;
      }
    }
    int leafPageId = kernel.findLeafPageId(space, key);
    int earlierInLeaf = earlierPendingInserts(
        mutations, operation, previousRowId, leafPageId, index);
    return kernel.validateMutationTargetAt(
        leafPageId, operation, space, key, previousRowId, earlierInLeaf);
  }

  private int earlierPendingInserts(
      PendingMutationBuffer mutations,
      int operation,
      int previousRowId,
      int leafPageId,
      int index) {
    if (operation != IndexedWalCodec.MUTATION_INSERT || previousRowId != 0) {
      return 0;
    }
    int count = 0;
    for (int previous = 0; previous < index; previous++) {
      if (mutations.operationAt(previous) == IndexedWalCodec.MUTATION_INSERT
          && mutations.previousRowIdAt(previous) == 0
          && kernel.findLeafPageId(
              mutations.spaceAt(previous), mutations.keyAt(previous)) == leafPageId) {
        count++;
      }
    }
    return count;
  }

  private static int pendingMutationBytes(PendingMutationBuffer mutations) {
    int bytes = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutations.count(); index++) {
      bytes += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES + mutations.rowLengthAt(index);
    }
    return bytes;
  }

  private void encodePendingMutations(
      PendingMutationBuffer mutations,
      ByteBuffer payload,
      int firstRowId,
      int operationBytes) {
    IndexedWalCodec.encodeMutationBatchHeader(payload, mutations.count());
    int outputOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutations.count(); index++) {
      int rowBytes = mutations.rowLengthAt(index);
      IndexedWalCodec.encodeMutationBatchEntry(
          payload,
          outputOffset,
          mutations.operationAt(index),
          mutations.spaceAt(index),
          mutations.keyAt(index),
          firstRowId + index,
          mutations.previousRowIdAt(index),
          rowBytes);
      int rowOffset = outputOffset + IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES;
      mutations.copyRowTo(index, payload, rowOffset);
      walCopyBytes += rowBytes;
      outputOffset = rowOffset + rowBytes;
    }
    payload.position(operationBytes);
  }

  StatusCode commitMutations(
      long transactionId,
      long commitSequence,
      int[] operations,
      int[] spaces,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount,
      HeapInsertResult result) {
    if (operationBusy() || !pages.isPresent(IndexedTableKernel.HEAP_PAGE_ID)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = validateRawMutations(
        operations, spaces, keys, previousRowIds,
        rows, rowStride, rowLengths, mutationCount);
    if (!status.isOk()) {
      return status;
    }
    if (!canAppendRows(rowLengths, mutationCount)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int operationBytes = rawMutationBytes(rowLengths, mutationCount);
    status = wal.reserve(operationBytes, reservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer payload = reservation.writablePayload();
    int firstRowId = kernel.rowCount() + 1;
    encodeRawMutations(
        payload,
        operations,
        spaces,
        keys,
        previousRowIds,
        rows,
        rowStride,
        rowLengths,
        mutationCount,
        firstRowId,
        operationBytes);
    status = publishMutations(transactionId, commitSequence, payload);
    if (status.isOk()) {
      result.setRowId(firstRowId + mutationCount - 1);
    }
    return status;
  }

  private StatusCode validateRawMutations(
      int[] operations,
      int[] spaces,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount) {
    for (int index = 0; index < mutationCount; index++) {
      StatusCode status = validateRawMutation(
          operations, spaces, keys, previousRowIds,
          rows, rowStride, rowLengths, index);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode validateRawMutation(
      int[] operations,
      int[] spaces,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int index) {
    int operation = operations[index];
    long key = keys[index];
    int previousRowId = previousRowIds[index];
    int rowBytes = rowLengths[index];
    int rowOffset = index * rowStride;
    if (!validMutation(operation)
        || !OrderedKey.isFiniteSpace(spaces[index])
        || rowBytes <= 0
        || rowBytes > rowStride
        || rows.limit() - rowOffset < rowBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int previous = 0; previous < index; previous++) {
      if (spaces[previous] == spaces[index] && keys[previous] == key) {
        return StatusCode.CONFLICT;
      }
    }
    int leafPageId = kernel.findLeafPageId(spaces[index], key);
    int earlierInLeaf = earlierRawInserts(
        operations, spaces, keys, previousRowIds,
        operation, previousRowId, leafPageId, index);
    return kernel.validateMutationTargetAt(
        leafPageId, operation, spaces[index], key, previousRowId, earlierInLeaf);
  }

  private int earlierRawInserts(
      int[] operations,
      int[] spaces,
      long[] keys,
      int[] previousRowIds,
      int operation,
      int previousRowId,
      int leafPageId,
      int index) {
    if (operation != IndexedWalCodec.MUTATION_INSERT || previousRowId != 0) {
      return 0;
    }
    int count = 0;
    for (int previous = 0; previous < index; previous++) {
      if (operations[previous] == IndexedWalCodec.MUTATION_INSERT
          && previousRowIds[previous] == 0
          && kernel.findLeafPageId(spaces[previous], keys[previous]) == leafPageId) {
        count++;
      }
    }
    return count;
  }

  private static boolean validMutation(int operation) {
    return operation == IndexedWalCodec.MUTATION_INSERT
        || operation == IndexedWalCodec.MUTATION_UPDATE
        || operation == IndexedWalCodec.MUTATION_DELETE;
  }

  private static int rawMutationBytes(int[] rowLengths, int mutationCount) {
    int bytes = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutationCount; index++) {
      bytes += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES + rowLengths[index];
    }
    return bytes;
  }

  private void encodeRawMutations(
      ByteBuffer payload,
      int[] operations,
      int[] spaces,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount,
      int firstRowId,
      int operationBytes) {
    IndexedWalCodec.encodeMutationBatchHeader(payload, mutationCount);
    int outputOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutationCount; index++) {
      int rowBytes = rowLengths[index];
      IndexedWalCodec.encodeMutationBatchEntry(
          payload,
          outputOffset,
          operations[index],
          spaces[index],
          keys[index],
          firstRowId + index,
          previousRowIds[index],
          rowBytes);
      int rowOffset = outputOffset + IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES;
      copyRow(rows, index * rowStride, payload, rowOffset, rowBytes);
      outputOffset = rowOffset + rowBytes;
    }
    payload.position(operationBytes);
  }

  private StatusCode publishInsert(
      long transactionId,
      long commitSequence,
      ByteBuffer payload,
      boolean singleInsert) {
    StatusCode status = publish(transactionId, commitSequence);
    if (status.isOk()) {
      status = singleInsert
          ? kernel.applyInsertOperation(
              payload, appendResult.startOffset(), appendResult.endOffset(), commitSequence)
          : kernel.applyInsertBatchOperation(
              payload, appendResult.startOffset(), appendResult.endOffset(), commitSequence);
    }
    return fenceFailure(status);
  }

  private StatusCode publishMutations(
      long transactionId, long commitSequence, ByteBuffer payload) {
    StatusCode status = publish(transactionId, commitSequence);
    if (status.isOk()) {
      status = kernel.applyMutationBatchOperation(
          payload, appendResult.startOffset(), appendResult.endOffset(), commitSequence);
    }
    return fenceFailure(status);
  }

  private StatusCode publish(long transactionId, long commitSequence) {
    return wal.publish(
        reservation,
        transactionId,
        commitSequence,
        1,
        IndexedTableStore.WAL_FORMAT_ID,
        IndexedTableStore.WAL_FORMAT_VERSION,
        appendResult);
  }

  private StatusCode fenceFailure(StatusCode status) {
    if (!status.isOk()) {
      failed = true;
    }
    return status;
  }

  private boolean operationBusy() {
    return phase.operationActive() || phase.preparedInsertGroupActive();
  }

  private boolean canAppendRows(int[] rowLengths, int count) {
    return kernel.canAppendRows(rowLengths, count);
  }

  private void copyRow(
      ByteBuffer source,
      int sourceOffset,
      ByteBuffer destination,
      int destinationOffset,
      int rowBytes) {
    for (int index = 0; index < rowBytes; index++) {
      destination.put(destinationOffset + index, source.get(sourceOffset + index));
    }
    walCopyBytes += rowBytes;
  }

  long walCopyBytes() {
    return walCopyBytes;
  }

  boolean failed() {
    return failed;
  }
}
