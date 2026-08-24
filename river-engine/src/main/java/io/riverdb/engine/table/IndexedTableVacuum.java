package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Encodes and applies the indexed-table vacuum stream. */
final class IndexedTableVacuum {
  private final IndexedTableKernel table;
  private final IndexedPageSet pages;
  private final IndexedVersionState versions;
  private final io.riverdb.storage.heap.HeapInsertResult heapInsert;
  private long encodeOrdinal;
  private int encodedRows;
  private int outputOffset;
  private int heapPageId;
  private int lastSpace;
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
  }

  int chunkCount() {
    int chunks = 0;
    int chunkBytes = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    int rows = 0;
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      ByteBuffer leaf = leafPayload(pageId);
      if (leaf == null) continue;
      int entryCount = BTreePage.entryCount(leaf);
      for (int entry = 0; entry < entryCount; entry++) {
        int rowBytes = table.rowLength(BTreePage.leafValueAt(leaf, entry));
        int required = IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
        if (rowBytes <= 0
            || required > WalRecordCodec.MAX_PAYLOAD_BYTES
                - IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES) {
          return -1;
        }
        if (chunkBytes > WalRecordCodec.MAX_PAYLOAD_BYTES - required) {
          chunks++;
          chunkBytes = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
        }
        chunkBytes += required;
        rows++;
      }
    }
    if (rows > 0) chunks++;
    return rows == table.indexedEntryCount() ? chunks : -1;
  }

  int chunkRowCount(long firstRow) {
    long ordinal = 0;
    int rows = 0;
    int bytes = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      ByteBuffer leaf = leafPayload(pageId);
      if (leaf == null) continue;
      int entryCount = BTreePage.entryCount(leaf);
      for (int entry = 0; entry < entryCount; entry++) {
        if (ordinal++ < firstRow) continue;
        int rowBytes = table.rowLength(BTreePage.leafValueAt(leaf, entry));
        int required = IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
        if (rowBytes <= 0 || bytes > WalRecordCodec.MAX_PAYLOAD_BYTES - required) {
          return rows;
        }
        bytes += required;
        rows++;
      }
    }
    return rows;
  }

  int chunkPayloadBytes(long firstRow, int rowLimit) {
    long ordinal = 0;
    int rows = 0;
    int bytes = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    for (int pageId = 1;
        rows < rowLimit && pageId <= pages.highestPageId();
        pageId++) {
      ByteBuffer leaf = leafPayload(pageId);
      if (leaf == null) continue;
      int entryCount = BTreePage.entryCount(leaf);
      for (int entry = 0; rows < rowLimit && entry < entryCount; entry++) {
        if (ordinal++ < firstRow) continue;
        int rowBytes = table.rowLength(BTreePage.leafValueAt(leaf, entry));
        if (rowBytes <= 0) return -1;
        bytes += IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
        rows++;
      }
    }
    return rows == rowLimit ? bytes : -1;
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
    encodeOrdinal = 0;
    encodedRows = 0;
    outputOffset = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    StatusCode status = StatusCode.OK;
    for (int pageId = 1;
        status.isOk() && encodedRows < rowLimit && pageId <= pages.highestPageId();
        pageId++) {
      ByteBuffer leaf = leafPayload(pageId);
      if (leaf != null) status = encodeLeaf(payload, leaf, firstRow, rowLimit);
    }
    if (!status.isOk()) return status;
    if (encodedRows != rowLimit || outputOffset != payloadBytes) {
      return StatusCode.CORRUPTION;
    }
    payload.position(payloadBytes);
    return StatusCode.OK;
  }

  private StatusCode encodeLeaf(
      ByteBuffer payload, ByteBuffer leaf, long firstRow, int rowLimit) {
    int entryCount = BTreePage.entryCount(leaf);
    for (int entry = 0; encodedRows < rowLimit && entry < entryCount; entry++) {
      if (encodeOrdinal++ < firstRow) continue;
      long rowId = BTreePage.leafValueAt(leaf, entry);
      int rowBytes = table.rowLength(rowId);
      IndexedWalCodec.encodeVacuumEntry(
          payload,
          outputOffset,
          BTreePage.spaceAt(leaf, entry),
          BTreePage.keyAt(leaf, entry),
          rowId,
          rowBytes,
          table.isDeletedRow(rowId));
      StatusCode status = table.copyRowTo(
          rowId, payload, outputOffset + IndexedWalCodec.VACUUM_ENTRY_BYTES);
      if (!status.isOk()) return status;
      outputOffset += IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
      encodedRows++;
    }
    return StatusCode.OK;
  }

  StatusCode beginApply() {
    heapPageId = IndexedTableKernel.HEAP_PAGE_ID;
    lastSpace = 0;
    lastKey = 0;
    StatusCode status = StatusCode.OK;
    for (int pageId = 1; status.isOk() && pageId <= pages.highestPageId(); pageId++) {
      if (!pages.isPresent(pageId) || !HeapPage.isHeap(pages.currentPayloadUnchecked(pageId))) {
        continue;
      }
      ByteBuffer stagedHeap = pages.stageExisting(pageId, IndexedTableLimits.MAX_PAGES);
      status = stagedHeap == null
          ? StatusCode.RESOURCE_EXHAUSTED : HeapPage.initialize(stagedHeap);
    }
    return status;
  }

  StatusCode applyEntry(ByteBuffer payload, int entryOffset, long compactedRowId) {
    if (!IndexedWalCodec.validVacuumEntry(payload, entryOffset)) {
      return StatusCode.CORRUPTION;
    }
    long key = IndexedWalCodec.vacuumEntryKey(payload, entryOffset);
    int space = IndexedWalCodec.vacuumEntrySpace(payload, entryOffset);
    long oldRowId = IndexedWalCodec.vacuumEntryRowId(payload, entryOffset);
    int rowBytes = IndexedWalCodec.vacuumEntryRowBytes(payload, entryOffset);
    boolean deleted = IndexedWalCodec.vacuumEntryDeleted(payload, entryOffset);
    if (!OrderedKey.isFiniteSpace(space)
        || (compactedRowId > 1
            && !OrderedKey.lessThan(lastSpace, lastKey, space, key))
        || table.rowLength(oldRowId) != rowBytes
        || table.isDeletedRow(oldRowId) != deleted) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = table.validateVacuumHead(space, key, oldRowId);
    if (!status.isOk()) return status;
    ByteBuffer leaf = pages.stageExisting(
        table.validatedLeafPageId(), IndexedTableLimits.MAX_PAGES);
    if (leaf == null) return StatusCode.RESOURCE_EXHAUSTED;
    ByteBuffer heap = pages.operationPayload(heapPageId);
    if (!HeapPage.canInsert(heap, rowBytes)) {
      heapPageId = nextHeapPageId(heapPageId);
      heap = heapPageId == 0 ? null : pages.operationPayload(heapPageId);
      if (heap == null) return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = HeapPage.insertFrom(
        heap,
        payload,
        entryOffset + IndexedWalCodec.VACUUM_ENTRY_BYTES,
        rowBytes,
        heapInsert);
    if (status.isOk()) {
      status = BTreePage.updateLeaf(leaf, space, key, compactedRowId);
    }
    if (status.isOk()) {
      versions.recordVacuumDeleted(compactedRowId, deleted);
      lastSpace = space;
      lastKey = key;
    }
    return status;
  }

  void resetApply() {
    heapPageId = 0;
    lastSpace = 0;
    lastKey = 0;
  }

  private ByteBuffer leafPayload(int pageId) {
    if (!pages.isPresent(pageId) || pageId == IndexedTableKernel.ROOT_META_PAGE_ID) {
      return null;
    }
    ByteBuffer page = pages.currentPayloadUnchecked(pageId);
    return !HeapPage.isHeap(page) && BTreePage.type(page) == BTreePage.TYPE_LEAF
        ? page : null;
  }

  private int nextHeapPageId(int afterPageId) {
    for (int pageId = afterPageId + 1; pageId <= pages.highestPageId(); pageId++) {
      if (pages.isPresent(pageId) && HeapPage.isHeap(pages.currentPayloadUnchecked(pageId))) {
        return pageId;
      }
    }
    return 0;
  }
}
