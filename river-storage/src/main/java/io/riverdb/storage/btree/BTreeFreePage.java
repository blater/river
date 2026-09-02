package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Canonical payload stored by one page in the intrusive durable free stack. */
public final class BTreeFreePage {
  public static final int BYTES = Integer.BYTES;

  private BTreeFreePage() { }

  public static void initialize(ByteBuffer page, int nextPageId) {
    for (int index = 0; index < page.limit(); index++) page.put(index, (byte) 0);
    FormatBytes.putInt(page, 0, nextPageId);
  }

  public static StatusCode validate(
      ByteBuffer page, int pageId, int nextAllocationPageId, int remainingCount) {
    return validLink(page, pageId, nextAllocationPageId, remainingCount)
            && zeroRemainder(page)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  public static int nextPageId(ByteBuffer page) { return FormatBytes.getInt(page, 0); }

  static boolean validLink(
      ByteBuffer page, int pageId, int nextAllocationPageId, int remainingCount) {
    if (page == null || page.limit() < BYTES || remainingCount <= 0) return false;
    return validNext(nextPageId(page), pageId, nextAllocationPageId, remainingCount);
  }

  static boolean validNext(
      int next, int pageId, int nextAllocationPageId, int remainingCount) {
    return remainingCount == 1 ? next == 0
        : next >= BTreeRootPage.FIRST_REUSABLE_PAGE_ID
            && next < nextAllocationPageId && next != pageId;
  }

  private static boolean zeroRemainder(ByteBuffer page) {
    for (int index = BYTES; index < page.limit(); index++) {
      if (page.get(index) != 0) return false;
    }
    return true;
  }
}
