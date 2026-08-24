package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Owns the indexed table's bounded current and staged page frames. */
final class IndexedPageSet {
  private final PagedObjectArray<ByteBuffer> currentPages =
      new PagedObjectArray<>(IndexedTableLimits.MAX_PAGES);
  private final PagedObjectArray<ByteBuffer> currentPayloads =
      new PagedObjectArray<>(IndexedTableLimits.MAX_PAGES);
  private final PagedObjectArray<ByteBuffer> stagingPages =
      new PagedObjectArray<>(IndexedTableLimits.MAX_PAGES);
  private final PagedObjectArray<ByteBuffer> stagingPayloads =
      new PagedObjectArray<>(IndexedTableLimits.MAX_PAGES);
  private final PagedBooleanArray present = new PagedBooleanArray(IndexedTableLimits.MAX_PAGES);
  private final PagedBooleanArray staged = new PagedBooleanArray(IndexedTableLimits.MAX_PAGES);
  private final PagedBooleanArray dirty = new PagedBooleanArray(IndexedTableLimits.MAX_PAGES);
  private final PagedLongArray recordStarts = new PagedLongArray(IndexedTableLimits.MAX_PAGES);
  private final PagedLongArray recordEnds = new PagedLongArray(IndexedTableLimits.MAX_PAGES);
  private final PagedIntArray changedPageIds = new PagedIntArray(IndexedTableLimits.MAX_PAGES);
  private int changedPageCount;
  private int highestPageId;
  private long stagedCopyBytes;

  ByteBuffer currentPayloadUnchecked(int pageId) {
    return currentPayloads.get(pageId);
  }

  boolean isPresent(int pageId) {
    return present.get(pageId);
  }

  boolean isStaged(int pageId) {
    return staged.get(pageId);
  }

  boolean isDirty(int pageId) {
    return dirty.get(pageId);
  }

  long recordStart(int pageId) {
    return recordStarts.get(pageId);
  }

  long recordEnd(int pageId) {
    return recordEnds.get(pageId);
  }

  int changedPageCount() {
    return changedPageCount;
  }

  int changedPageId(int index) {
    return changedPageIds.get(index);
  }

  int highestPageId() {
    return highestPageId;
  }

  long stagedCopyBytes() {
    return stagedCopyBytes;
  }

  void resetChanges() {
    changedPageCount = 0;
  }

  void markCurrentChanged(int pageId, long recordStart, long recordEnd) {
    recordStarts.set(pageId, recordStart);
    recordEnds.set(pageId, recordEnd);
    dirty.set(pageId, true);
  }

  void installPresent(int pageId) {
    present.set(pageId, true);
    highestPageId = Math.max(highestPageId, pageId);
  }

  void installChanged(int pageId, long recordStart, long recordEnd) {
    installPresent(pageId);
    markCurrentChanged(pageId, recordStart, recordEnd);
  }

  void markClean(int pageId) {
    dirty.set(pageId, false);
  }

  void markRebased(int pageId) {
    recordStarts.set(pageId, 0);
    recordEnds.set(pageId, 0);
    dirty.set(pageId, false);
  }

  StatusCode encodeCurrent(
      int pageId,
      DatabaseIncarnation database,
      WalGeneration generation,
      long recordStart,
      long recordEnd,
      CRC32C checksum) {
    return PageCodec.encode(
        database,
        generation,
        pageId,
        1,
        recordStart,
        recordEnd,
        PageCodec.MAX_PAYLOAD_BYTES,
        currentPages.get(pageId),
        checksum);
  }

  StatusCode encodeStaged(
      int pageId,
      DatabaseIncarnation database,
      WalGeneration generation,
      long recordStart,
      long recordEnd,
      CRC32C checksum) {
    return PageCodec.encode(
        database,
        generation,
        pageId,
        1,
        recordStart,
        recordEnd,
        PageCodec.MAX_PAYLOAD_BYTES,
        stagingPages.get(pageId),
        checksum);
  }

  StatusCode readCurrent(
      DurableFile file,
      int pageId,
      long offset,
      IoResult result) {
    ByteBuffer page = currentPages.get(pageId);
    page.clear();
    StatusCode status = file.read(offset, page, result);
    if (status.isOk() && result.bytesTransferred() == PageCodec.PAGE_BYTES) {
      page.position(0);
      page.limit(PageCodec.PAGE_BYTES);
    }
    return status;
  }

  StatusCode writeCurrent(
      DurableFile file,
      int pageId,
      long offset,
      IoResult result) {
    ByteBuffer page = currentPages.get(pageId);
    page.position(0);
    page.limit(PageCodec.PAGE_BYTES);
    return file.write(offset, page, result);
  }

  StatusCode validateCurrent(int pageId, PageHeader header, CRC32C checksum) {
    return PageCodec.validate(currentPages.get(pageId), header, checksum);
  }

  StatusCode validateRecord(
      ByteBuffer source,
      int sourceOffset,
      PageHeader header,
      CRC32C checksum) {
    return PageCodec.validateAt(source, sourceOffset, header, checksum);
  }

  void copyStagedToRecord(int pageId, ByteBuffer target, int targetOffset) {
    ByteBuffer source = stagingPages.get(pageId);
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      target.put(targetOffset + index, source.get(index));
    }
  }

  void installFromRecord(
      ByteBuffer source,
      int sourceOffset,
      int pageId,
      long recordStart,
      long recordEnd) {
    ensureBuffers(pageId);
    copyFromRecord(source, sourceOffset, currentPages.get(pageId));
    installChanged(pageId, recordStart, recordEnd);
  }

  /**
   * Returns the cached committed payload view. The borrowed view is immutable to callers and is
   * valid until publication, checkpoint rebase, or close.
   */
  ByteBuffer currentPayload(int pageId) {
    return validPresentPage(pageId) ? currentPayloads.get(pageId) : null;
  }

  /**
   * Returns a mutable staged payload for the current operation. The view expires at publication or
   * cancellation. The first touch performs the operation's single full-page copy.
   */
  ByteBuffer stageExisting(int pageId, int maximumChangedPages) {
    if (!validPageId(pageId)) {
      return null;
    }
    if (staged.get(pageId)) {
      return stagingPayloads.get(pageId);
    }
    if (!present.get(pageId) || !addChangedPage(pageId, maximumChangedPages)) {
      return null;
    }
    copyPage(currentPages.get(pageId), stagingPages.get(pageId));
    stagedCopyBytes += PageCodec.PAGE_BYTES;
    return stagingPayloads.get(pageId);
  }

  /** Returns the active operation's staged view when present, otherwise its committed view. */
  ByteBuffer operationPayload(int pageId) {
    if (!validPageId(pageId)) {
      return null;
    }
    return staged.get(pageId) ? stagingPayloads.get(pageId)
        : present.get(pageId) ? currentPayloads.get(pageId) : null;
  }

  /** Returns a zeroed mutable payload for a new page in the current operation. */
  ByteBuffer stageNew(int pageId, int maximumChangedPages) {
    if (!validPageId(pageId) || present.get(pageId)) {
      return null;
    }
    if (staged.get(pageId)) {
      return stagingPayloads.get(pageId);
    }
    if (!addChangedPage(pageId, maximumChangedPages)) {
      return null;
    }
    ensureBuffers(pageId);
    ByteBuffer page = stagingPages.get(pageId);
    page.clear();
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      page.put(index, (byte) 0);
    }
    ByteBuffer payload = stagingPayloads.get(pageId);
    payload.clear();
    return payload;
  }

  void ensureBuffers(int pageId) {
    if (currentPages.get(pageId) != null) {
      return;
    }
    currentPages.set(pageId, ByteBuffer.allocateDirect(PageCodec.PAGE_BYTES));
    currentPayloads.set(pageId, payloadView(currentPages.get(pageId)));
    stagingPages.set(pageId, ByteBuffer.allocateDirect(PageCodec.PAGE_BYTES));
    stagingPayloads.set(pageId, payloadView(stagingPages.get(pageId)));
  }

  boolean validPresentPage(int pageId) {
    return validPageId(pageId) && present.get(pageId);
  }

  boolean hasDirtyPages() {
    for (int pageId = 1; pageId <= highestPageId; pageId++) {
      if (dirty.get(pageId)) {
        return true;
      }
    }
    return false;
  }

  boolean addChangedPage(int pageId, int maximumChangedPages) {
    if (changedPageCount >= maximumChangedPages) {
      return false;
    }
    changedPageIds.set(changedPageCount++, pageId);
    staged.set(pageId, true);
    return true;
  }

  void clearStagedFlags() {
    for (int index = 0; index < changedPageCount; index++) {
      staged.set(changedPageIds.get(index), false);
    }
  }

  void publish(long recordStart, long recordEnd) {
    for (int index = 0; index < changedPageCount; index++) {
      int pageId = changedPageIds.get(index);
      ByteBuffer page = currentPages.get(pageId);
      currentPages.set(pageId, stagingPages.get(pageId));
      stagingPages.set(pageId, page);
      ByteBuffer payload = currentPayloads.get(pageId);
      currentPayloads.set(pageId, stagingPayloads.get(pageId));
      stagingPayloads.set(pageId, payload);
      present.set(pageId, true);
      dirty.set(pageId, true);
      staged.set(pageId, false);
      recordStarts.set(pageId, recordStart);
      recordEnds.set(pageId, recordEnd);
      highestPageId = Math.max(highestPageId, pageId);
    }
  }

  static void copyPage(ByteBuffer source, ByteBuffer target) {
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      target.put(index, source.get(index));
    }
    target.position(0);
    target.limit(PageCodec.PAGE_BYTES);
  }

  private static void copyFromRecord(ByteBuffer source, int offset, ByteBuffer target) {
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      target.put(index, source.get(offset + index));
    }
    target.position(0);
    target.limit(PageCodec.PAGE_BYTES);
  }

  private static ByteBuffer payloadView(ByteBuffer page) {
    page.clear();
    page.position(PageCodec.HEADER_BYTES);
    page.limit(PageCodec.PAGE_BYTES);
    return page.slice();
  }

  private static boolean validPageId(int pageId) {
    return pageId > 0 && pageId <= IndexedTableLimits.MAX_PAGES;
  }
}
