package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalAppendResult;
import io.riverdb.wal.local.LocalWalReservation;
import java.nio.ByteBuffer;

/** Encodes and appends prepared transaction payloads without forcing or publishing them. */
final class IndexedPreparedWriteEncoder {
  private final LocalWal wal;
  private final IndexedTableKernel kernel;
  private final IndexedStorePhase phase;
  private final LocalWalReservation reservation = new LocalWalReservation();
  private final LocalWalAppendResult appendResult = new LocalWalAppendResult();
  private int recordCount;
  private int rowCount;
  private long walCopyBytes;
  private long recordStart;
  private boolean failed;

  IndexedPreparedWriteEncoder(
      LocalWal localWal, IndexedTableKernel tableKernel, IndexedStorePhase storePhase) {
    wal = localWal;
    kernel = tableKernel;
    phase = storePhase;
  }

  StatusCode append(
      long transactionId,
      long commitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    return mutations.containsNonInsertMutation()
        ? appendMutations(transactionId, commitSequence, mutations, result)
        : appendInserts(transactionId, commitSequence, mutations, result);
  }

  private StatusCode appendInserts(
      long transactionId,
      long commitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    if (!validAppend(transactionId, commitSequence, mutations, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int operationBytes = insertOperationBytes(mutations);
    StatusCode status = wal.reserve(operationBytes, reservation);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    ByteBuffer payload = reservation.writablePayload();
    int firstRowId = kernel.rowCount() + rowCount + 1;
    if (mutations.count() == 1) {
      encodeSingleInsert(payload, mutations, firstRowId);
    } else {
      encodeInsertBatch(payload, mutations, firstRowId);
    }
    return finishAppend(
        transactionId, commitSequence, operationBytes, mutations.count(), firstRowId, result);
  }

  private static int insertOperationBytes(PendingMutationBuffer mutations) {
    if (mutations.count() == 1) {
      return IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES + mutations.rowLengthAt(0);
    }
    int bytes = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutations.count(); index++) {
      bytes += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES + mutations.rowLengthAt(index);
    }
    return bytes;
  }

  private void encodeSingleInsert(
      ByteBuffer payload, PendingMutationBuffer mutations, int firstRowId) {
    int rowBytes = mutations.rowLengthAt(0);
    IndexedWalCodec.encodeInsertHeader(
        payload, mutations.spaceAt(0), mutations.keyAt(0), firstRowId, rowBytes);
    mutations.copyRowTo(0, payload, IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES);
    walCopyBytes += rowBytes;
  }

  private void encodeInsertBatch(
      ByteBuffer payload, PendingMutationBuffer mutations, int firstRowId) {
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

  private StatusCode appendMutations(
      long transactionId,
      long commitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    if (!validAppend(transactionId, commitSequence, mutations, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int operationBytes = mutationOperationBytes(mutations);
    StatusCode status = wal.reserve(operationBytes, reservation);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    ByteBuffer payload = reservation.writablePayload();
    IndexedWalCodec.encodeMutationBatchHeader(payload, mutations.count());
    int outputOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    int firstRowId = kernel.rowCount() + rowCount + 1;
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
    return finishAppend(
        transactionId, commitSequence, operationBytes, mutations.count(), firstRowId, result);
  }

  private static int mutationOperationBytes(PendingMutationBuffer mutations) {
    int bytes = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutations.count(); index++) {
      bytes += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES + mutations.rowLengthAt(index);
    }
    return bytes;
  }

  private boolean validAppend(
      long transactionId,
      long commitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    return phase.preparedInsertGroupActive()
        && phase.preparedInsertEncoding()
        && recordCount < LocalWal.MAX_PENDING_RECORDS
        && transactionId > 0
        && commitSequence == wal.nextCommitSequence()
        && mutations.count() > 0
        && result != null;
  }

  private StatusCode finishAppend(
      long transactionId,
      long commitSequence,
      int operationBytes,
      int mutationCount,
      int firstRowId,
      HeapInsertResult result) {
    ByteBuffer payload = reservation.writablePayload();
    payload.position(operationBytes);
    StatusCode status = wal.appendUnforced(
        reservation,
        transactionId,
        commitSequence,
        1,
        IndexedTableStore.WAL_FORMAT_ID,
        IndexedTableStore.WAL_FORMAT_VERSION,
        appendResult);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    recordStart = appendResult.startOffset();
    recordCount++;
    rowCount += mutationCount;
    result.setRowId(firstRowId + mutationCount - 1);
    return StatusCode.OK;
  }

  void reset() {
    recordCount = 0;
    rowCount = 0;
    recordStart = 0;
  }

  int recordCount() {
    return recordCount;
  }

  int rowCount() {
    return rowCount;
  }

  long recordStart() {
    return recordStart;
  }

  long walCopyBytes() {
    return walCopyBytes;
  }

  boolean failed() {
    return failed;
  }
}
