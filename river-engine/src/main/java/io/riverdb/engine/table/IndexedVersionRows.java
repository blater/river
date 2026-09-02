package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Keeps row-directory access and recovery separate from version-chain state. */
final class IndexedVersionRows {
  private final IndexedRowDirectory rows;
  private final IndexedRowLocation location = new IndexedRowLocation();
  private long rebuiltRowCount;
  private int rebuiltLastHeapPageId;

  IndexedVersionRows(IndexedRowDirectory rowDirectory) {
    rows = rowDirectory;
  }

  IndexedRowDirectory directory() {
    return rows;
  }

  int pageId(long rowId) {
    return rows.pageId(rowId);
  }

  int slot(long rowId) {
    return rows.slot(rowId);
  }

  StatusCode locate(long rowId, IndexedRowLocation result) {
    return rows.locate(rowId, result);
  }

  StatusCode set(long rowId, int pageId, int slot) {
    return rows.set(rowId, pageId, slot);
  }

  boolean matches(long rowCount, long commitSequence) {
    return rows.matches(rowCount, commitSequence);
  }

  int publishedLastHeapPageId() {
    return rows.publishedLastHeapPageId();
  }

  StatusCode load(long expectedRowCount) {
    int expectedLastHeapPageId = rows.publishedLastHeapPageId();
    if (expectedRowCount < 0 || expectedRowCount > IndexedTableLimits.MAX_ROWS
        || expectedLastHeapPageId <= 0
        || expectedLastHeapPageId > IndexedTableLimits.MAX_PAGES) return StatusCode.CORRUPTION;
    if (expectedRowCount > 0) {
      StatusCode status = rows.locate(expectedRowCount, location);
      if (!status.isOk()) return status;
      if (location.pageId() > expectedLastHeapPageId) return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  StatusCode rebuild(IndexedPageSet pages) {
    rebuiltRowCount = 0;
    rebuiltLastHeapPageId = 0;
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      if (!pages.isPresent(pageId) || !HeapPage.isHeap(pages.currentPayloadUnchecked(pageId))) {
        continue;
      }
      ByteBuffer page = pages.currentPayloadUnchecked(pageId);
      int pageRows = HeapPage.rowCount(page);
      if (pageRows < 0 || rebuiltRowCount > IndexedTableLimits.MAX_ROWS - pageRows) {
        return StatusCode.CORRUPTION;
      }
      for (int slot = 1; slot <= pageRows; slot++) {
        StatusCode status = rows.set(++rebuiltRowCount, pageId, slot);
        if (!status.isOk()) return status;
      }
      rebuiltLastHeapPageId = pageId;
    }
    return rebuiltLastHeapPageId == 0 ? StatusCode.CORRUPTION : StatusCode.OK;
  }

  long rebuiltRowCount() {
    return rebuiltRowCount;
  }

  int rebuiltLastHeapPageId() {
    return rebuiltLastHeapPageId;
  }
}
