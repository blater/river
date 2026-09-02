package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Reusable row-location and page-pin workspace for kernel row access. */
final class IndexedKernelRowAccess {
  private final IndexedPageSet pages;
  private final IndexedVersionState versions;
  private final IndexedRowLocation location = new IndexedRowLocation();

  IndexedKernelRowAccess(IndexedPageSet pageSet, IndexedVersionState versionState) {
    pages = pageSet;
    versions = versionState;
  }

  StatusCode fetch(long rowId, long rowCount, HeapRowResult result) {
    if (result == null || rowId <= 0 || rowId > rowCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = versions.rows().locate(rowId, location);
    if (!status.isOk()) return status;
    int pageId = location.pageId();
    status = pages.pinCurrentPage(pageId);
    if (!status.isOk()) return status;
    try {
      ByteBuffer page = pages.currentPayload(pageId);
      status = page == null ? pages.lastStatus() : HeapPage.fetch(page, location.slot(), result);
      if (status.isOk()) status = result.retainBytes();
      if (!status.isOk()) result.reset();
      return status;
    } finally {
      pages.unpinCurrentPage(pageId);
    }
  }

  StatusCode pin(long rowId, long rowCount, HeapRowResult result, IndexedRowPin pin) {
    if (result == null || pin == null || pin.attached()
        || rowId <= 0 || rowId > rowCount) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = versions.rows().locate(rowId, location);
    if (!status.isOk()) return status;
    int pageId = location.pageId();
    status = pages.pinCurrentPage(pageId);
    if (!status.isOk()) return status;
    ByteBuffer page = pages.currentPayload(pageId);
    status = page == null ? pages.lastStatus() : HeapPage.fetch(page, location.slot(), result);
    if (status.isOk()) pin.attach(pageId, result);
    else pages.unpinCurrentPage(pageId);
    return status;
  }

  StatusCode release(IndexedRowPin pin) {
    if (pin == null || !pin.attached()) return StatusCode.INVALID_EXTERNAL_INPUT;
    pages.unpinCurrentPage(pin.pageId());
    pin.reset();
    return StatusCode.OK;
  }

  int length(long rowId, long rowCount) {
    if (rowId <= 0 || rowId > rowCount
        || !versions.rows().locate(rowId, location).isOk()) return 0;
    return HeapPage.rowLength(
        pages.currentPayloadUnchecked(location.pageId()), location.slot());
  }

  StatusCode copyTo(
      long rowId, long rowCount, ByteBuffer destination, int destinationOffset) {
    if (rowId <= 0 || rowId > rowCount) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = versions.rows().locate(rowId, location);
    if (!status.isOk()) return status;
    int pageId = location.pageId();
    status = pages.pinCurrentPage(pageId);
    if (!status.isOk()) return status;
    try {
      ByteBuffer page = pages.currentPayload(pageId);
      return page == null ? pages.lastStatus()
          : HeapPage.copyRowTo(page, location.slot(), destination, destinationOffset);
    } finally {
      pages.unpinCurrentPage(pageId);
    }
  }
}
