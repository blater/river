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
  private final ByteBuffer[] currentPages = new ByteBuffer[IndexedTableLimits.MAX_PAGES + 1];
  private final ByteBuffer[] currentPayloads = new ByteBuffer[IndexedTableLimits.MAX_PAGES + 1];
  private final ByteBuffer[] stagingPages = new ByteBuffer[IndexedTableLimits.MAX_PAGES + 1];
  private final ByteBuffer[] stagingPayloads = new ByteBuffer[IndexedTableLimits.MAX_PAGES + 1];
  private final boolean[] present = new boolean[IndexedTableLimits.MAX_PAGES + 1];
  private final boolean[] staged = new boolean[IndexedTableLimits.MAX_PAGES + 1];
  private final boolean[] dirty = new boolean[IndexedTableLimits.MAX_PAGES + 1];
  private final long[] recordStarts = new long[IndexedTableLimits.MAX_PAGES + 1];
  private final long[] recordEnds = new long[IndexedTableLimits.MAX_PAGES + 1];
  private final int[] changedPageIds = new int[IndexedTableLimits.MAX_PAGES];
  private int changedPageCount;
  private int highestPageId;
  private long stagedCopyBytes;

  ByteBuffer currentPayloadUnchecked(int pageId) {
    return currentPayloads[pageId];
  }

  boolean isPresent(int pageId) {
    return present[pageId];
  }

  boolean isStaged(int pageId) {
    return staged[pageId];
  }

  boolean isDirty(int pageId) {
    return dirty[pageId];
  }

  long recordStart(int pageId) {
    return recordStarts[pageId];
  }

  long recordEnd(int pageId) {
    return recordEnds[pageId];
  }

  int changedPageCount() {
    return changedPageCount;
  }

  int changedPageId(int index) {
    return changedPageIds[index];
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
    recordStarts[pageId] = recordStart;
    recordEnds[pageId] = recordEnd;
    dirty[pageId] = true;
  }

  void installPresent(int pageId) {
    present[pageId] = true;
    highestPageId = Math.max(highestPageId, pageId);
  }

  void installChanged(int pageId, long recordStart, long recordEnd) {
    installPresent(pageId);
    markCurrentChanged(pageId, recordStart, recordEnd);
  }

  void markClean(int pageId) {
    dirty[pageId] = false;
  }

  void markRebased(int pageId) {
    recordStarts[pageId] = 0;
    recordEnds[pageId] = 0;
    dirty[pageId] = false;
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
        currentPages[pageId],
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
        stagingPages[pageId],
        checksum);
  }

  StatusCode readCurrent(
      DurableFile file,
      int pageId,
      long offset,
      IoResult result) {
    ByteBuffer page = currentPages[pageId];
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
    ByteBuffer page = currentPages[pageId];
    page.position(0);
    page.limit(PageCodec.PAGE_BYTES);
    return file.write(offset, page, result);
  }

  StatusCode validateCurrent(int pageId, PageHeader header, CRC32C checksum) {
    return PageCodec.validate(currentPages[pageId], header, checksum);
  }

  StatusCode validateRecord(
      ByteBuffer source,
      int sourceOffset,
      PageHeader header,
      CRC32C checksum) {
    return PageCodec.validateAt(source, sourceOffset, header, checksum);
  }

  void copyStagedToRecord(int pageId, ByteBuffer target, int targetOffset) {
    ByteBuffer source = stagingPages[pageId];
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
    copyFromRecord(source, sourceOffset, currentPages[pageId]);
    installChanged(pageId, recordStart, recordEnd);
  }

  /**
   * Returns the cached committed payload view. The borrowed view is immutable to callers and is
   * valid until publication, checkpoint rebase, or close.
   */
  ByteBuffer currentPayload(int pageId) {
    return validPresentPage(pageId) ? currentPayloads[pageId] : null;
  }

  /**
   * Returns a mutable staged payload for the current operation. The view expires at publication or
   * cancellation. The first touch performs the operation's single full-page copy.
   */
  ByteBuffer stageExisting(int pageId, int maximumChangedPages) {
    if (!validPageId(pageId)) {
      return null;
    }
    if (staged[pageId]) {
      return stagingPayloads[pageId];
    }
    if (!present[pageId] || !addChangedPage(pageId, maximumChangedPages)) {
      return null;
    }
    copyPage(currentPages[pageId], stagingPages[pageId]);
    stagedCopyBytes += PageCodec.PAGE_BYTES;
    return stagingPayloads[pageId];
  }

  /** Returns the active operation's staged view when present, otherwise its committed view. */
  ByteBuffer operationPayload(int pageId) {
    if (!validPageId(pageId)) {
      return null;
    }
    return staged[pageId] ? stagingPayloads[pageId]
        : present[pageId] ? currentPayloads[pageId] : null;
  }

  /** Returns a zeroed mutable payload for a new page in the current operation. */
  ByteBuffer stageNew(int pageId, int maximumChangedPages) {
    if (!validPageId(pageId) || present[pageId]) {
      return null;
    }
    if (staged[pageId]) {
      return stagingPayloads[pageId];
    }
    if (!addChangedPage(pageId, maximumChangedPages)) {
      return null;
    }
    ensureBuffers(pageId);
    ByteBuffer page = stagingPages[pageId];
    page.clear();
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      page.put(index, (byte) 0);
    }
    ByteBuffer payload = stagingPayloads[pageId];
    payload.clear();
    return payload;
  }

  void ensureBuffers(int pageId) {
    if (currentPages[pageId] != null) {
      return;
    }
    currentPages[pageId] = ByteBuffer.allocateDirect(PageCodec.PAGE_BYTES);
    currentPayloads[pageId] = payloadView(currentPages[pageId]);
    stagingPages[pageId] = ByteBuffer.allocateDirect(PageCodec.PAGE_BYTES);
    stagingPayloads[pageId] = payloadView(stagingPages[pageId]);
  }

  boolean validPresentPage(int pageId) {
    return validPageId(pageId) && present[pageId];
  }

  boolean hasDirtyPages() {
    for (int pageId = 1; pageId <= highestPageId; pageId++) {
      if (dirty[pageId]) {
        return true;
      }
    }
    return false;
  }

  boolean addChangedPage(int pageId, int maximumChangedPages) {
    if (changedPageCount >= maximumChangedPages) {
      return false;
    }
    changedPageIds[changedPageCount++] = pageId;
    staged[pageId] = true;
    return true;
  }

  void clearStagedFlags() {
    for (int index = 0; index < changedPageCount; index++) {
      staged[changedPageIds[index]] = false;
    }
  }

  void publish(long recordStart, long recordEnd) {
    for (int index = 0; index < changedPageCount; index++) {
      int pageId = changedPageIds[index];
      ByteBuffer page = currentPages[pageId];
      currentPages[pageId] = stagingPages[pageId];
      stagingPages[pageId] = page;
      ByteBuffer payload = currentPayloads[pageId];
      currentPayloads[pageId] = stagingPayloads[pageId];
      stagingPayloads[pageId] = payload;
      present[pageId] = true;
      dirty[pageId] = true;
      staged[pageId] = false;
      recordStarts[pageId] = recordStart;
      recordEnds[pageId] = recordEnd;
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
