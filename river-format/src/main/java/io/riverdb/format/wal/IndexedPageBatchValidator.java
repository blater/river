package io.riverdb.format.wal;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/**
 * Reusable recovery validator for one indexed page-image batch.
 *
 * <p>Validation completes before callers publish any page, root, or allocator watermark. The
 * bounded page identity bank avoids allocation while rejecting duplicate images and matching any
 * root image to its advertised generation.
 */
public final class IndexedPageBatchValidator {
  private final long[] pageIds = new long[IndexedPageBatchCodec.MAXIMUM_PAGE_COUNT];
  private final long[] pageGenerations = new long[IndexedPageBatchCodec.MAXIMUM_PAGE_COUNT];
  private final PageHeader pageHeader = new PageHeader();
  private final IndexedRootUpdate root = new IndexedRootUpdate();
  private final CRC32C checksum = new CRC32C();
  private int pageCount;

  public StatusCode validate(
      ByteBuffer source,
      int start,
      DatabaseIncarnation expectedDatabase,
      WalGeneration expectedWalGeneration,
      long expectedRecordStart,
      long expectedRecordEnd,
      IndexedPageBatchHeader result) {
    reset();
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null
        || start < 0
        || expectedDatabase == null
        || !expectedDatabase.isValid()
        || expectedWalGeneration == null
        || !expectedWalGeneration.isValid()
        || expectedRecordStart <= 0
        || expectedRecordEnd <= expectedRecordStart) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (source.limit() - start < IndexedPageBatchCodec.HEADER_BYTES) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = IndexedPageBatchCodec.validateStructure(source, start, result);
    if (!status.isOk()) {
      return status == StatusCode.INVALID_EXTERNAL_INPUT ? StatusCode.CORRUPTION : status;
    }
    for (int index = 0; index < result.pageCount(); index++) {
      int offset = start + IndexedPageBatchCodec.pageOffset(index, result.pageCount());
      status = PageCodec.validateAt(source, offset, pageHeader, checksum);
      if (!status.isOk()
          || pageHeader.databaseHigh() != expectedDatabase.high()
          || pageHeader.databaseLow() != expectedDatabase.low()
          || pageHeader.walGeneration() != expectedWalGeneration.value()
          || pageHeader.recordStart() != expectedRecordStart
          || pageHeader.recordEnd() != expectedRecordEnd
          || pageHeader.pageId() > Integer.MAX_VALUE
          || pageHeader.pageId() >= result.nextPageId()
          || contains(pageHeader.pageId())) {
        result.reset();
        reset();
        return StatusCode.CORRUPTION;
      }
      pageIds[pageCount] = pageHeader.pageId();
      pageGenerations[pageCount] = pageHeader.pageGeneration();
      pageCount++;
    }
    for (int index = 0; index < result.rootCount(); index++) {
      status = IndexedPageBatchCodec.decodeRoot(source, start, result, index, root);
      if (!status.isOk()) {
        result.reset();
        reset();
        return status;
      }
      int image = indexOf(root.pageId());
      if (image >= 0 && pageGenerations[image] != root.pageGeneration()) {
        result.reset();
        reset();
        return StatusCode.CORRUPTION;
      }
    }
    return StatusCode.OK;
  }

  public void reset() {
    for (int index = 0; index < pageCount; index++) {
      pageIds[index] = 0;
      pageGenerations[index] = 0;
    }
    pageCount = 0;
    pageHeader.reset();
  }

  private boolean contains(long pageId) {
    return indexOf(pageId) >= 0;
  }

  private int indexOf(long pageId) {
    for (int index = 0; index < pageCount; index++) {
      if (pageIds[index] == pageId) return index;
    }
    return -1;
  }
}
