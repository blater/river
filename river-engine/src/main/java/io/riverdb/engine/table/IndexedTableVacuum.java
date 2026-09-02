package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Encodes and applies the indexed-table vacuum stream. */
final class IndexedTableVacuum {
  private final IndexedTableKernel table;
  private final IndexedPageSet pages;
  private final IndexedVersionState versions;
  private final IndexedVacuumScanner scanner;
  private final IndexedVacuumRowCursor encodeCursor;
  private final IndexedVacuumPublicationAdmission publicationAdmission;
  private final IndexedVacuumShadowPages shadow;
  private final io.riverdb.storage.heap.HeapInsertResult heapInsert;
  private final HeapRowResult sourceRow = new HeapRowResult();
  private final IndexedVersionRecord sourceVersion = new IndexedVersionRecord();
  private int encodedRows;
  private int outputOffset;
  private long lastSpace;
  private long lastKey;

  IndexedTableVacuum(
      IndexedTableKernel table,
      IndexedPageSet pages,
      IndexedVersionState versions,
      io.riverdb.storage.heap.HeapInsertResult heapInsert) {
    this.table = table;
    this.pages = pages;
    this.versions = versions;
    this.heapInsert = heapInsert;
    scanner = new IndexedVacuumScanner(table, pages);
    encodeCursor = new IndexedVacuumRowCursor(table, pages);
    publicationAdmission = new IndexedVacuumPublicationAdmission(pages);
    shadow = new IndexedVacuumShadowPages(pages);
  }

  StatusCode chunkCount(IndexedCountResult result) {
    return scanner.chunkCount(result);
  }

  StatusCode chunkRowCount(long firstRow, IndexedCountResult result) {
    return scanner.chunkRowCount(firstRow, result);
  }

  StatusCode chunkPayloadBytes(long firstRow, int rowLimit, IndexedCountResult result) {
    return scanner.chunkPayloadBytes(firstRow, rowLimit, result);
  }

  StatusCode encodeChunk(
      ByteBuffer payload,
      long retainedRows,
      long firstRow,
      int rowLimit,
      int chunk,
      int chunkCount,
      int payloadBytes) {
    if (payload == null
        || retainedRows <= 0
        || firstRow < 0
        || rowLimit <= 0
        || firstRow > retainedRows - rowLimit
        || chunk < 0
        || chunk >= chunkCount
        || payloadBytes <= IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES
        || payload.limit() != payloadBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    IndexedWalCodec.encodeVacuumChunkHeader(
        payload, retainedRows, firstRow, rowLimit, chunk, chunkCount);
    encodedRows = 0;
    outputOffset = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    StatusCode status = encodeCursor.reset(firstRow);
    try {
      while (status.isOk() && encodedRows < rowLimit) {
        status = encodeCursor.next(sourceRow);
        if (status.isOk()) status = encodeCurrent(payload);
      }
    } finally {
      encodeCursor.close();
    }
    if (!status.isOk()) return status;
    if (encodedRows != rowLimit || outputOffset != payloadBytes) {
      return StatusCode.CORRUPTION;
    }
    payload.position(payloadBytes);
    return StatusCode.OK;
  }

  private StatusCode encodeCurrent(ByteBuffer payload) {
    StatusCode status = table.readVersion(encodeCursor.rowId(), sourceVersion);
    if (!status.isOk()) return status;
    int rowBytes = sourceRow.length();
    IndexedWalCodec.encodeVacuumEntry(
        payload,
        outputOffset,
        encodeCursor.space(),
        encodeCursor.key(),
        encodeCursor.rowId(),
        rowBytes,
        sourceVersion.deleted());
    payload.position(outputOffset + IndexedWalCodec.VACUUM_ENTRY_BYTES);
    status = sourceRow.copyTo(payload);
    if (!status.isOk()) return status;
    outputOffset += IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
    encodedRows++;
    return StatusCode.OK;
  }

  StatusCode beginApply() {
    StatusCode status = publicationAdmission.admit();
    if (!status.isOk()) return status;
    lastSpace = 0;
    lastKey = 0;
    return shadow.begin();
  }

  StatusCode applyEntry(ByteBuffer payload, int entryOffset, long compactedRowId) {
    if (!IndexedWalCodec.validVacuumEntry(payload, entryOffset)) {
      return StatusCode.CORRUPTION;
    }
    long key = IndexedWalCodec.vacuumEntryKey(payload, entryOffset);
    long space = IndexedWalCodec.vacuumEntrySpace(payload, entryOffset);
    long oldRowId = IndexedWalCodec.vacuumEntryRowId(payload, entryOffset);
    int rowBytes = IndexedWalCodec.vacuumEntryRowBytes(payload, entryOffset);
    boolean deleted = IndexedWalCodec.vacuumEntryDeleted(payload, entryOffset);
    if (!OrderedKey.isFiniteSpace(space)
        || (compactedRowId > 1
            && !OrderedKey.lessThan(lastSpace, lastKey, space, key))
        || oldRowId <= 0) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = table.validateVacuumHead(
        space, key, oldRowId, compactedRowId);
    if (!status.isOk()) return status;
    ByteBuffer heap = shadow.heap(rowBytes);
    if (heap == null) return shadow.lastStatus();
    status = HeapPage.insertFrom(
        heap,
        payload,
        entryOffset + IndexedWalCodec.VACUUM_ENTRY_BYTES,
        rowBytes,
        heapInsert);
    if (status.isOk()) {
      ByteBuffer leaf = shadow.leaf(table.validatedLeafPageId());
      if (leaf == null) return shadow.lastStatus();
      status = BTreePage.updateLeaf(leaf, space, key, compactedRowId);
    }
    if (status.isOk()) {
      status = versions.recordVacuumDeleted(compactedRowId, deleted);
    }
    if (status.isOk()) {
      lastSpace = space;
      lastKey = key;
    }
    return status;
  }

  StatusCode finishApply() {
    return shadow.finish();
  }

  StatusCode publish(long start, long end) {
    return shadow.publish(start, end);
  }

  void resetApply() {
    shadow.reset();
    lastSpace = 0;
    lastKey = 0;
  }

}
