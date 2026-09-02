package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalGroupReservation;
import io.riverdb.wal.local.LocalWalLogicalStream;
import java.nio.ByteBuffer;

/** Plans and encodes one bounded batch of a streamed vacuum operation. */
final class IndexedVacuumBatch {
  private final int[] payloadBytes = new int[LocalWal.MAX_PENDING_RECORDS];
  private final int[] chunkRows = new int[LocalWal.MAX_PENDING_RECORDS];
  private final LocalWalGroupReservation reservation = new LocalWalGroupReservation();
  private final IndexedCountResult count = new IndexedCountResult();
  private final LocalWal wal;
  private final IndexedTableKernel kernel;
  private long copiedBytes;
  private long rows;

  IndexedVacuumBatch(LocalWal localWal, IndexedTableKernel tableKernel) {
    wal = localWal;
    kernel = tableKernel;
  }

  StatusCode prepare(
      LocalWalLogicalStream stream,
      long retainedRows,
      long rowsBefore,
      long firstRow,
      int firstChunk,
      int batchChunks,
      int chunkCount,
      boolean finalBatch) {
    rows = 0;
    StatusCode status = plan(firstRow, batchChunks);
    int records = batchChunks;
    if (status.isOk() && finalBatch) {
      payloadBytes[records++] = IndexedTableStore.VACUUM_COMMIT_PAYLOAD_BYTES;
    }
    if (status.isOk()) {
      status = wal.reserveLogicalStreamBatch(stream, payloadBytes, records, reservation);
    }
    long row = firstRow;
    for (int record = 0; status.isOk() && record < batchChunks; record++) {
      int bytes = payloadBytes[record];
      status = kernel.encodeVacuumChunk(
          reservation.writablePayload(record),
          retainedRows,
          row,
          chunkRows[record],
          firstChunk + record,
          chunkCount,
          bytes);
      if (status.isOk()) {
        copiedBytes += bytes - IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES
            - chunkRows[record] * IndexedWalCodec.VACUUM_ENTRY_BYTES;
      }
      row += chunkRows[record];
    }
    if (status.isOk() && finalBatch) {
      ByteBuffer payload = reservation.writablePayload(batchChunks);
      IndexedWalCodec.encodeVacuumCommit(payload, retainedRows, chunkCount, rowsBefore);
      payload.position(IndexedTableStore.VACUUM_COMMIT_PAYLOAD_BYTES);
    }
    return status.isOk() ? status : cancel(stream, status);
  }

  LocalWalGroupReservation reservation() { return reservation; }
  long rows() { return rows; }
  long copiedBytes() { return copiedBytes; }

  StatusCode cancel(LocalWalLogicalStream stream, StatusCode failure) {
    if (!reservation.isActive()) return failure;
    StatusCode cleanup = wal.cancelLogicalStreamBatch(stream, reservation);
    return cleanup.isOk() ? failure : cleanup;
  }

  private StatusCode plan(long firstRow, int batchChunks) {
    long row = firstRow;
    for (int record = 0; record < batchChunks; record++) {
      StatusCode status = kernel.vacuumChunkRowCount(row, count);
      if (!status.isOk()) return status;
      int chunk = (int) count.value();
      status = kernel.vacuumChunkPayloadBytes(row, chunk, count);
      int bytes = (int) count.value();
      if (!status.isOk()) return status;
      if (chunk <= 0 || bytes <= IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES) {
        return StatusCode.CORRUPTION;
      }
      chunkRows[record] = chunk;
      payloadBytes[record] = bytes;
      rows += chunk;
      row += chunk;
    }
    return StatusCode.OK;
  }
}
