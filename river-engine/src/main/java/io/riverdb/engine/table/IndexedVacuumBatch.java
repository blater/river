package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWalRecordBatch;
import java.nio.ByteBuffer;

/** Encodes one physical vacuum chunk and the optional final decision record. */
final class IndexedVacuumBatch implements LocalWalRecordBatch {
  private final IndexedCountResult count = new IndexedCountResult();
  private final IndexedTableKernel kernel;
  private long retainedRows;
  private long rowsBefore;
  private long firstRow;
  private long copiedBytes;
  private int firstChunk;
  private int chunkCount;
  private int chunkRows;
  private int chunkPayloadBytes;
  private boolean finalBatch;

  IndexedVacuumBatch(IndexedTableKernel tableKernel) { kernel = tableKernel; }

  StatusCode prepare(
      long retained,
      long before,
      long first,
      int chunk,
      int totalChunks,
      boolean last) {
    StatusCode status = kernel.vacuumChunkRowCount(first, count);
    if (!status.isOk()) return status;
    int rows = (int) count.value();
    status = kernel.vacuumChunkPayloadBytes(first, rows, count);
    if (!status.isOk()) return status;
    int bytes = (int) count.value();
    if (rows <= 0 || bytes <= IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES) {
      return StatusCode.CORRUPTION;
    }
    retainedRows = retained;
    rowsBefore = before;
    firstRow = first;
    firstChunk = chunk;
    chunkCount = totalChunks;
    chunkRows = rows;
    chunkPayloadBytes = bytes;
    finalBatch = last;
    return StatusCode.OK;
  }

  long rows() { return chunkRows; }
  long copiedBytes() { return copiedBytes; }

  @Override
  public int recordCount() { return finalBatch ? 2 : 1; }

  @Override
  public int payloadBytes(int record) {
    if (record == 0) return chunkPayloadBytes;
    return finalBatch && record == 1 ? IndexedTableStore.VACUUM_COMMIT_PAYLOAD_BYTES : -1;
  }

  @Override
  public StatusCode encodePayload(int record, ByteBuffer target) {
    if (record == 0) {
      StatusCode status = kernel.encodeVacuumChunk(
          target,
          retainedRows,
          firstRow,
          chunkRows,
          firstChunk,
          chunkCount,
          chunkPayloadBytes);
      if (status.isOk()) {
        copiedBytes += chunkPayloadBytes - IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES
            - chunkRows * IndexedWalCodec.VACUUM_ENTRY_BYTES;
      }
      return status;
    }
    if (!finalBatch || record != 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    IndexedWalCodec.encodeVacuumCommit(target, retainedRows, chunkCount, rowsBefore);
    target.position(IndexedTableStore.VACUUM_COMMIT_PAYLOAD_BYTES);
    return StatusCode.OK;
  }
}
