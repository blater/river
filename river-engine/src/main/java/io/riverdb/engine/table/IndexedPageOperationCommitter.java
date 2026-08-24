package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalAppendResult;
import io.riverdb.wal.local.LocalWalReservation;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Publishes one staged page operation and its row-version metadata as a WAL unit. */
final class IndexedPageOperationCommitter {
  private final LocalWal wal;
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedStorePhase phase;
  private final DatabaseIncarnation database;
  private final LocalWalReservation reservation = new LocalWalReservation();
  private final LocalWalAppendResult appendResult = new LocalWalAppendResult();
  private final CRC32C checksum = new CRC32C();
  private long copiedBytes;
  private boolean failed;

  IndexedPageOperationCommitter(
      LocalWal localWal,
      IndexedTableKernel tableKernel,
      IndexedPageSet pageSet,
      IndexedStorePhase storePhase,
      DatabaseIncarnation databaseIncarnation) {
    wal = localWal;
    kernel = tableKernel;
    pages = pageSet;
    phase = storePhase;
    database = databaseIncarnation;
  }

  StatusCode begin() {
    if (phase.operationActive() || phase.preparedInsertGroupActive()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    pages.resetChanges();
    kernel.beginOperationState();
    return phase.beginStaged() ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  StatusCode commit(
      long transactionId,
      long commitSequence,
      long publishedCommitSequence,
      WalGeneration walGeneration) {
    if (!phase.operationActive()
        || transactionId <= 0
        || commitSequence <= publishedCommitSequence
        || pages.changedPageCount() <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int operationBytes = IndexedWalCodec.PAGE_OPERATION_HEADER_BYTES
        + pages.changedPageCount() * PageCodec.PAGE_BYTES
        + kernel.operationVersionCount() * IndexedWalCodec.PAGE_OPERATION_VERSION_BYTES;
    StatusCode status = wal.reserve(operationBytes, reservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer payload = reservation.writablePayload();
    IndexedWalCodec.encodePageOperationHeader(
        payload, pages.changedPageCount(), kernel.operationVersionCount());
    status = encodePages(payload, walGeneration);
    if (!status.isOk()) {
      wal.cancel(reservation);
      return status;
    }
    int outputOffset = IndexedWalCodec.PAGE_OPERATION_HEADER_BYTES
        + pages.changedPageCount() * PageCodec.PAGE_BYTES;
    for (int index = 0; index < kernel.operationVersionCount(); index++) {
      IndexedWalCodec.encodePageOperationVersion(
          payload,
          outputOffset,
          kernel.operationPreviousRowId(index),
          kernel.operationDeleted(index));
      outputOffset += IndexedWalCodec.PAGE_OPERATION_VERSION_BYTES;
    }
    payload.position(operationBytes);
    status = publish(transactionId, commitSequence, payload);
    if (!status.isOk()) {
      failed = true;
    }
    return status;
  }

  private StatusCode encodePages(ByteBuffer payload, WalGeneration walGeneration) {
    int outputOffset = IndexedWalCodec.PAGE_OPERATION_HEADER_BYTES;
    for (int index = 0; index < pages.changedPageCount(); index++) {
      int pageId = pages.changedPageId(index);
      StatusCode status = pages.encodeStaged(
          pageId,
          database,
          walGeneration,
          reservation.recordStartOffset(),
          reservation.recordEndOffset(),
          checksum);
      if (!status.isOk()) {
        return status;
      }
      pages.copyStagedToRecord(pageId, payload, outputOffset);
      copiedBytes += PageCodec.PAGE_BYTES;
      outputOffset += PageCodec.PAGE_BYTES;
    }
    return StatusCode.OK;
  }

  private StatusCode publish(
      long transactionId, long commitSequence, ByteBuffer payload) {
    StatusCode status = wal.publish(
        reservation,
        transactionId,
        commitSequence,
        1,
        IndexedTableStore.WAL_FORMAT_ID,
        IndexedTableStore.WAL_FORMAT_VERSION,
        appendResult);
    if (!status.isOk()) {
      return status;
    }
    long previousRowCount = kernel.rowCount();
    pages.publish(appendResult.startOffset(), appendResult.endOffset());
    status = kernel.rebuildRowLocations();
    if (!status.isOk()
        || kernel.rowCount() != kernel.operationRowCount()
        || kernel.rowCount() - previousRowCount != kernel.operationVersionCount()) {
      return status.isOk() ? StatusCode.INVARIANT_BROKEN : status;
    }
    kernel.recordOperationVersions(previousRowCount, commitSequence);
    phase.reset();
    pages.resetChanges();
    kernel.clearOperationVersions();
    return StatusCode.OK;
  }

  long copiedBytes() {
    return copiedBytes;
  }

  boolean failed() {
    return failed;
  }
}
