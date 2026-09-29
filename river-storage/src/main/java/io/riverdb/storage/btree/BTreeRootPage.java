package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Durable scalar-root identity and intrusive free-page stack head. */
public final class BTreeRootPage {
  public static final int BYTES = 40;
  public static final int VERSION = 5;
  public static final int FIRST_REUSABLE_PAGE_ID = PageCodec.FIRST_ALLOCATABLE_PAGE_ID;
  private static final long MAGIC = 0x5249564552425452L; // RIVERBTR

  private BTreeRootPage() { }

  public static StatusCode initialize(ByteBuffer page, int root, int next) {
    return BTreeRootPageState.initialize(page, root, next, MAGIC, VERSION);
  }

  public static StatusCode validate(ByteBuffer page) {
    return BTreeRootPageState.validate(page, MAGIC, VERSION);
  }

  public static int rootPageId(ByteBuffer page) { return FormatBytes.getInt(page, 12); }
  public static int nextPageId(ByteBuffer page) { return FormatBytes.getInt(page, 16); }
  public static int freePageHead(ByteBuffer page) { return FormatBytes.getInt(page, 20); }
  public static int freePageCount(ByteBuffer page) { return FormatBytes.getInt(page, 24); }
  public static int retiredOverflowHead(ByteBuffer page) { return FormatBytes.getInt(page, 28); }
  public static int retiredOverflowTail(ByteBuffer page) { return FormatBytes.getInt(page, 32); }
  public static int retiredOverflowCount(ByteBuffer page) { return FormatBytes.getInt(page, 36); }
  public static void publishRetiredOverflow(ByteBuffer page, int head, int tail, int count) {
    FormatBytes.putInt(page, 28, head);
    FormatBytes.putInt(page, 32, tail);
    FormatBytes.putInt(page, 36, count);
  }
  public static void publishRoot(ByteBuffer page, int root) { FormatBytes.putInt(page, 12, root); }

  public static int nextAllocationPage(ByteBuffer page) {
    int head = freePageHead(page);
    return head == 0 ? nextPageId(page) : head;
  }

  public static StatusCode allocatePage(
      ByteBuffer metadata, int pageId, int nextFreePageId) {
    return BTreeRootPageState.allocate(metadata, pageId, nextFreePageId);
  }

  public static StatusCode releasePage(
      ByteBuffer metadata, int pageId, ByteBuffer freePage) {
    return BTreeRootPageState.release(metadata, pageId, freePage);
  }

  public static boolean hasAllocations(ByteBuffer page, int count, int maximumPageId) {
    if (page == null || count < 0 || maximumPageId <= 0) return false;
    int free = freePageCount(page);
    int fresh = count - Math.min(count, free);
    return fresh == 0 || nextPageId(page) <= maximumPageId - fresh + 1;
  }
}
