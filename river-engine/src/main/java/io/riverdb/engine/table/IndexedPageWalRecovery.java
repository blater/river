package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageHeader;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Validates and installs one indexed page-image WAL operation. */
final class IndexedPageWalRecovery {
  private final IndexedPageSet pages;
  private final IndexedTableKernel kernel;
  private final DatabaseIncarnation database;
  private final int[] pageIds = new int[IndexedTableLimits.MAX_CHANGED_PAGES];
  private final CRC32C checksum = new CRC32C();
  private final PageHeader header = new PageHeader();

  IndexedPageWalRecovery(
      IndexedPageSet pageSet, IndexedTableKernel table, DatabaseIncarnation incarnation) {
    pages = pageSet;
    kernel = table;
    database = incarnation;
  }

  StatusCode apply(
      ByteBuffer payload, long recordStart, long recordEnd,
      long commitSequence, WalGeneration generation) {
    StatusCode status = IndexedWalCodec.validatePageOperation(
        payload, IndexedTableLimits.MAX_CHANGED_PAGES,
        IndexedTableLimits.MAX_OPERATION_ROWS);
    if (!status.isOk()) return status;
    int pagesInRecord = IndexedWalCodec.pageOperationPageCount(payload);
    int versions = IndexedWalCodec.pageOperationVersionCount(payload);
    long previousRows = kernel.rowCount();
    status = validatePages(payload, recordStart, recordEnd, generation, pagesInRecord);
    if (status.isOk()) status = installPages(payload, recordStart, recordEnd, pagesInRecord);
    if (status.isOk()) status = kernel.validateAppliedPages(pageIds, pagesInRecord);
    if (status.isOk()) status = kernel.rebuildRowLocations();
    if (status.isOk() && kernel.rowCount() - previousRows != versions) {
      status = StatusCode.CORRUPTION;
    }
    return status.isOk() ? kernel.applyRecoveredVersions(
        payload, IndexedWalCodec.pageOperationVersionsOffset(pagesInRecord),
        previousRows, versions, commitSequence) : status;
  }

  private StatusCode validatePages(
      ByteBuffer payload, long start, long end, WalGeneration generation, int count) {
    for (int index = 0; index < count; index++) {
      StatusCode status = pages.validateRecord(
          payload, IndexedWalCodec.pageOperationPageOffset(index), header, checksum);
      int pageId = (int) header.pageId();
      if (!status.isOk()) return status;
      if (pageId <= 0 || pageId > IndexedTableLimits.MAX_PAGES
          || header.pageGeneration() != 1
          || header.databaseHigh() != database.high()
          || header.databaseLow() != database.low()
          || header.walGeneration() != generation.value()
          || header.recordStart() != start || header.recordEnd() != end
          || IndexedWalCodec.containsEarlierPageId(pageIds, index, pageId)) {
        return StatusCode.CORRUPTION;
      }
      pageIds[index] = pageId;
    }
    return StatusCode.OK;
  }

  private StatusCode installPages(ByteBuffer payload, long start, long end, int count) {
    for (int index = 0; index < count; index++) {
      int pageId = pageIds[index];
      StatusCode status = pages.ensureBuffers(pageId);
      if (status.isOk()) status = pages.installFromRecord(
          payload, IndexedWalCodec.pageOperationPageOffset(index), pageId, start, end);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }
}
