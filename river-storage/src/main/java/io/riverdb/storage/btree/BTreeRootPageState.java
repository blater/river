package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Checked mutations for scalar-root allocation metadata. */
final class BTreeRootPageState {
  private BTreeRootPageState() { }

  static StatusCode initialize(ByteBuffer page, int root, int next, long magic, int version) {
    if (page == null || page.limit() < BTreeRootPage.BYTES || root <= 0 || next <= root) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < page.limit(); index++) page.put(index, (byte) 0);
    FormatBytes.putLong(page, 0, magic);
    FormatBytes.putInt(page, 8, version);
    FormatBytes.putInt(page, 12, root);
    FormatBytes.putInt(page, 16, next);
    return StatusCode.OK;
  }

  static StatusCode validate(ByteBuffer page, long magic, int version) {
    if (page == null || page.limit() < BTreeRootPage.BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int root = BTreeRootPage.rootPageId(page);
    int next = BTreeRootPage.nextPageId(page);
    int head = BTreeRootPage.freePageHead(page);
    int count = BTreeRootPage.freePageCount(page);
    return FormatBytes.getLong(page, 0) == magic && FormatBytes.getInt(page, 8) == version
            && root > 0 && next > root && count >= 0
            && count <= next - BTreeRootPage.FIRST_REUSABLE_PAGE_ID
            && (count == 0 ? head == 0
                : head >= BTreeRootPage.FIRST_REUSABLE_PAGE_ID && head < next)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  static StatusCode allocate(ByteBuffer metadata, int pageId, int nextFreePageId) {
    if (metadata == null || metadata.limit() < BTreeRootPage.BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int head = BTreeRootPage.freePageHead(metadata);
    if (head == 0) {
      if (nextFreePageId != -1 || pageId != BTreeRootPage.nextPageId(metadata)) {
        return StatusCode.INVARIANT_BROKEN;
      }
      FormatBytes.putInt(metadata, 16, pageId + 1);
      return StatusCode.OK;
    }
    int count = BTreeRootPage.freePageCount(metadata);
    if (pageId != head || !BTreeFreePage.validNext(
        nextFreePageId, pageId, BTreeRootPage.nextPageId(metadata), count)) {
      return StatusCode.CORRUPTION;
    }
    FormatBytes.putInt(metadata, 20, nextFreePageId);
    FormatBytes.putInt(metadata, 24, count - 1);
    return StatusCode.OK;
  }

  static StatusCode release(ByteBuffer metadata, int pageId, ByteBuffer freePage) {
    if (metadata == null || metadata.limit() < BTreeRootPage.BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int next = BTreeRootPage.nextPageId(metadata);
    int count = BTreeRootPage.freePageCount(metadata);
    if (freePage == null || pageId < BTreeRootPage.FIRST_REUSABLE_PAGE_ID
        || pageId >= next || count >= next - BTreeRootPage.FIRST_REUSABLE_PAGE_ID) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    BTreeFreePage.initialize(freePage, BTreeRootPage.freePageHead(metadata));
    FormatBytes.putInt(metadata, 20, pageId);
    FormatBytes.putInt(metadata, 24, count + 1);
    return StatusCode.OK;
  }
}
