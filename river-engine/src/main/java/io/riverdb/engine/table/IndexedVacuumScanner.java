package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.storage.heap.HeapRowResult;

/** Scans committed leaves to size vacuum chunks without allocating row carriers. */
final class IndexedVacuumScanner {
  private final IndexedTableKernel table;
  private final HeapRowResult row = new HeapRowResult();
  private final IndexedCountResult indexedCount = new IndexedCountResult();
  private final IndexedVacuumRowCursor cursor;

  IndexedVacuumScanner(IndexedTableKernel table, IndexedPageSet pages) {
    this.table = table;
    cursor = new IndexedVacuumRowCursor(table, pages);
  }

  StatusCode chunkCount(IndexedCountResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    int chunks = 0;
    int chunkBytes = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    long rows = 0;
    StatusCode status = cursor.reset(0);
    try {
      while (status.isOk() && (status = cursor.next(row)).isOk()) {
        int rowBytes = row.length();
        int required = IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
        if (!validEntryBytes(rowBytes, required)) return StatusCode.CORRUPTION;
        if (chunkBytes > WalRecordCodec.MAX_PAYLOAD_BYTES - required) {
          chunks++;
          chunkBytes = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
        }
        chunkBytes += required;
        rows++;
      }
    } finally {
      StatusCode closeStatus = cursor.close();
      if (status.isOk()) status = closeStatus;
    }
    if (status != StatusCode.CONFLICT || !cursor.exhausted()) return status;
    if (rows > 0) chunks++;
    status = table.indexedEntryCount(indexedCount);
    if (!status.isOk()) return status;
    if (rows != indexedCount.value()) return StatusCode.CORRUPTION;
    result.set(chunks);
    return StatusCode.OK;
  }

  StatusCode chunkRowCount(long firstRow, IndexedCountResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    int rows = 0;
    int bytes = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    StatusCode status = cursor.reset(firstRow);
    try {
      while (status.isOk() && (status = cursor.next(row)).isOk()) {
        int rowBytes = row.length();
        int required = IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
        if (!validEntryBytes(rowBytes, required)) return StatusCode.CORRUPTION;
        if (bytes > WalRecordCodec.MAX_PAYLOAD_BYTES - required) break;
        bytes += required;
        rows++;
      }
    } finally {
      StatusCode closeStatus = cursor.close();
      if (status.isOk()) status = closeStatus;
    }
    if (!status.isOk() && (status != StatusCode.CONFLICT || !cursor.exhausted())) {
      return status;
    }
    result.set(rows);
    return StatusCode.OK;
  }

  StatusCode chunkPayloadBytes(long firstRow, int rowLimit, IndexedCountResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    int rows = 0;
    int bytes = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    StatusCode status = cursor.reset(firstRow);
    try {
      while (rows < rowLimit && status.isOk()
          && (status = cursor.next(row)).isOk()) {
        int rowBytes = row.length();
        if (rowBytes <= 0) return StatusCode.CORRUPTION;
        bytes += IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
        rows++;
      }
    } finally {
      StatusCode closeStatus = cursor.close();
      if (status.isOk()) status = closeStatus;
    }
    if (rows != rowLimit) {
      return status == StatusCode.CONFLICT && cursor.exhausted()
          ? StatusCode.CORRUPTION : status;
    }
    result.set(bytes);
    return StatusCode.OK;
  }

  private static boolean validEntryBytes(int rowBytes, int required) {
    return rowBytes > 0
        && required <= WalRecordCodec.MAX_PAYLOAD_BYTES
            - IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
  }
}
