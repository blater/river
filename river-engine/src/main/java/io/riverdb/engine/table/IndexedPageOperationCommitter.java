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

/** Publishes the quiescent initial page set before transaction service admission. */
final class IndexedPageOperationCommitter {
  private static final long BOOTSTRAP_TRANSACTION_ID = 1;
  private final LocalWal wal;
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedStorePhase phase;
  private final DatabaseIncarnation database;
  private final LocalWalReservation reservation = new LocalWalReservation();
  private final LocalWalAppendResult appendResult = new LocalWalAppendResult();
  private final CRC32C checksum = new CRC32C();
  private final long[] sequence = new long[1];
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

  StatusCode beginBootstrap() {
    if (phase.operationActive() || phase.commitGroupActive()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = kernel.reserveOperationVersions(0);
    if (!status.isOk()) return status;
    pages.resetChanges();
    pages.beginPageImageOperation();
    kernel.beginOperationState();
    if (!phase.beginStaged()) return StatusCode.INVARIANT_BROKEN;
    status = pages.beginPreparedBatch();
    if (!status.isOk()) phase.reset();
    return status;
  }

  StatusCode commitBootstrap(long commitSequence, WalGeneration walGeneration) {
    if (!phase.operationActive()
        || commitSequence <= 0
        || pages.changedPageCount() <= 0
        || pages.changedPageCount() > IndexedTableLimits.MAX_CHANGED_PAGES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int operationBytes = IndexedWalCodec.PAGE_OPERATION_HEADER_BYTES
        + pages.changedPageCount() * PageCodec.PAGE_BYTES
        + kernel.operationVersionCount() * IndexedWalCodec.PAGE_OPERATION_VERSION_BYTES;
    StatusCode status = kernel.operationVersionCount() == 0
        ? StatusCode.OK : kernel.admitOperationPublication();
    if (!status.isOk()) return status;
    return commitRetained(
        BOOTSTRAP_TRANSACTION_ID, commitSequence, walGeneration, operationBytes);
  }

  private StatusCode commitRetained(
      long transactionId,
      long commitSequence,
      WalGeneration walGeneration,
      int operationBytes) {
    StatusCode status = wal.reserve(operationBytes, reservation);
    if (!status.isOk()) return status;
    ByteBuffer payload = reservation.writablePayload();
    IndexedWalCodec.encodePageOperationHeader(
        payload, pages.changedPageCount(), kernel.operationVersionCount());
    status = encodePages(payload, walGeneration);
    if (!status.isOk()) {
      wal.cancel(reservation);
      return status;
    }
    status = pages.freezeChangedPages(0, Long.MAX_VALUE);
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
    sequence[0] = commitSequence;
    status = pages.installPreparedPages(
        sequence, 1, appendResult.startOffset(), appendResult.endOffset());
    if (status.isOk()) status = kernel.publishOperationRows(previousRowCount);
    if (!status.isOk()
        || kernel.rowCount() != kernel.operationRowCount()
        || kernel.rowCount() - previousRowCount != kernel.operationVersionCount()) {
      return status.isOk() ? StatusCode.INVARIANT_BROKEN : status;
    }
    status = kernel.recordOperationVersions(previousRowCount, commitSequence);
    if (!status.isOk()) return status;
    status = pages.releasePreparedBatch();
    if (!status.isOk()) return status;
    phase.reset();
    pages.resetChanges();
    kernel.clearOperationVersions();
    sequence[0] = 0;
    return StatusCode.OK;
  }

  long copiedBytes() {
    return copiedBytes;
  }

  boolean failed() {
    return failed;
  }
}
