package io.riverdb.format.wal;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/**
 * Indexed WAL v5 page-image transaction. Directory pages carry logical/version records;
 * this envelope publishes their roots and high-watermarks without encoding SQL key shapes.
 */
public final class IndexedPageBatchCodec {
  public static final int FORMAT_ID = 1002;
  public static final int FORMAT_VERSION = 5;
  public static final int TYPE_PAGE_BATCH = 1;
  public static final int HEADER_BYTES = 64;
  public static final int ROOT_UPDATE_BYTES = 24;
  public static final int MAXIMUM_PAGE_COUNT = 63;
  public static final int MAXIMUM_ROOT_COUNT = 63;
  public static final int MAXIMUM_OWNER_ID = 0x7fff;

  public static final int ROOT_HEAP = 1;
  public static final int ROOT_PRIMARY = 2;
  public static final int ROOT_SECONDARY = 3;
  public static final int ROOT_LOGICAL_DIRECTORY = 4;
  public static final int ROOT_VERSION_DIRECTORY = 5;
  public static final int ROOT_CATALOG = 6;

  private static final long MAGIC = 0x5249564944585035L; // RIVIDXP5

  private IndexedPageBatchCodec() {
  }

  public static int operationBytes(int pageCount, int rootCount) {
    if (pageCount < 0
        || pageCount > MAXIMUM_PAGE_COUNT
        || rootCount < 0
        || rootCount > MAXIMUM_ROOT_COUNT
        || pageCount == 0 && rootCount == 0) {
      return 0;
    }
    long bytes = (long) HEADER_BYTES
        + (long) pageCount * PageCodec.PAGE_BYTES
        + (long) rootCount * ROOT_UPDATE_BYTES;
    return bytes <= WalRecordCodec.MAX_PAYLOAD_BYTES ? (int) bytes : 0;
  }

  public static StatusCode encodeHeader(
      ByteBuffer target,
      int start,
      int pageCount,
      int rootCount,
      long maximumLogicalRowId,
      long maximumVersionId,
      int nextPageId,
      long storageGeneration) {
    int bytes = operationBytes(pageCount, rootCount);
    if (target == null
        || target.isReadOnly()
        || start < 0
        || bytes == 0
        || target.limit() - start < bytes
        || maximumLogicalRowId < 0
        || maximumVersionId < 0
        || nextPageId <= 0
        || storageGeneration <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FormatBytes.putLong(target, start, MAGIC);
    FormatBytes.putInt(target, start + 8, FORMAT_VERSION);
    FormatBytes.putInt(target, start + 12, TYPE_PAGE_BATCH);
    FormatBytes.putInt(target, start + 16, pageCount);
    FormatBytes.putInt(target, start + 20, rootCount);
    FormatBytes.putLong(target, start + 24, maximumLogicalRowId);
    FormatBytes.putLong(target, start + 32, maximumVersionId);
    FormatBytes.putInt(target, start + 40, nextPageId);
    FormatBytes.putInt(target, start + 44, 0);
    FormatBytes.putLong(target, start + 48, storageGeneration);
    FormatBytes.putLong(target, start + 56, 0);
    return StatusCode.OK;
  }

  public static StatusCode decodeHeader(
      ByteBuffer source, int start, IndexedPageBatchHeader result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || start < 0 || source.limit() - start < HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int pages = FormatBytes.getInt(source, start + 16);
    int roots = FormatBytes.getInt(source, start + 20);
    int bytes = operationBytes(pages, roots);
    long maximumLogical = FormatBytes.getLong(source, start + 24);
    long maximumVersion = FormatBytes.getLong(source, start + 32);
    int nextPage = FormatBytes.getInt(source, start + 40);
    long generation = FormatBytes.getLong(source, start + 48);
    if (FormatBytes.getLong(source, start) != MAGIC
        || FormatBytes.getInt(source, start + 8) != FORMAT_VERSION
        || FormatBytes.getInt(source, start + 12) != TYPE_PAGE_BATCH
        || bytes == 0
        || source.limit() - start != bytes
        || maximumLogical < 0
        || maximumVersion < 0
        || nextPage <= 0
        || FormatBytes.getInt(source, start + 44) != 0
        || generation <= 0
        || FormatBytes.getLong(source, start + 56) != 0) {
      return StatusCode.CORRUPTION;
    }
    result.set(pages, roots, maximumLogical, maximumVersion, nextPage, generation);
    return StatusCode.OK;
  }

  public static int pageOffset(int pageIndex, int pageCount) {
    return pageIndex >= 0 && pageIndex < pageCount
        ? HEADER_BYTES + pageIndex * PageCodec.PAGE_BYTES : -1;
  }

  public static int rootOffset(int rootIndex, int pageCount, int rootCount) {
    return rootIndex >= 0 && rootIndex < rootCount
        ? HEADER_BYTES + pageCount * PageCodec.PAGE_BYTES
            + rootIndex * ROOT_UPDATE_BYTES : -1;
  }

  public static StatusCode encodeRoot(
      ByteBuffer target,
      int offset,
      int kind,
      int ownerId,
      int pageId,
      long pageGeneration) {
    if (target == null
        || target.isReadOnly()
        || offset < 0
        || target.limit() - offset < ROOT_UPDATE_BYTES
        || !validRoot(kind, ownerId, pageId, pageGeneration)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FormatBytes.putInt(target, offset, kind);
    FormatBytes.putInt(target, offset + 4, ownerId);
    FormatBytes.putInt(target, offset + 8, pageId);
    FormatBytes.putInt(target, offset + 12, 0);
    FormatBytes.putLong(target, offset + 16, pageGeneration);
    return StatusCode.OK;
  }

  public static StatusCode decodeRoot(
      ByteBuffer source,
      int start,
      IndexedPageBatchHeader header,
      int rootIndex,
      IndexedRootUpdate result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null
        || header == null
        || rootIndex < 0
        || rootIndex >= header.rootCount()
        || start < 0
        || source.limit() - start
            != operationBytes(header.pageCount(), header.rootCount())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int offset = start + rootOffset(rootIndex, header.pageCount(), header.rootCount());
    int kind = FormatBytes.getInt(source, offset);
    int owner = FormatBytes.getInt(source, offset + 4);
    int page = FormatBytes.getInt(source, offset + 8);
    long generation = FormatBytes.getLong(source, offset + 16);
    if (!validRoot(kind, owner, page, generation)
        || FormatBytes.getInt(source, offset + 12) != 0) {
      return StatusCode.CORRUPTION;
    }
    result.set(kind, owner, page, generation);
    return StatusCode.OK;
  }

  /** Validates header and root updates, including duplicate root ownership. */
  public static StatusCode validateStructure(
      ByteBuffer source, int start, IndexedPageBatchHeader result) {
    StatusCode status = decodeHeader(source, start, result);
    if (!status.isOk()) return status;
    int rootsStart = start + HEADER_BYTES + result.pageCount() * PageCodec.PAGE_BYTES;
    for (int index = 0; index < result.rootCount(); index++) {
      int offset = rootsStart + index * ROOT_UPDATE_BYTES;
      int kind = FormatBytes.getInt(source, offset);
      int owner = FormatBytes.getInt(source, offset + 4);
      int pageId = FormatBytes.getInt(source, offset + 8);
      if (!validRoot(
              kind,
              owner,
              pageId,
              FormatBytes.getLong(source, offset + 16))
          || pageId >= result.nextPageId()
          || FormatBytes.getInt(source, offset + 12) != 0) {
        result.reset();
        return StatusCode.CORRUPTION;
      }
      for (int previous = 0; previous < index; previous++) {
        int earlier = rootsStart + previous * ROOT_UPDATE_BYTES;
        if (FormatBytes.getInt(source, earlier) == kind
            && FormatBytes.getInt(source, earlier + 4) == owner) {
          result.reset();
          return StatusCode.CORRUPTION;
        }
      }
    }
    return StatusCode.OK;
  }

  private static boolean validRoot(
      int kind, int ownerId, int pageId, long pageGeneration) {
    boolean tableRoot = kind == ROOT_HEAP || kind == ROOT_PRIMARY || kind == ROOT_SECONDARY;
    boolean globalRoot = kind == ROOT_LOGICAL_DIRECTORY
        || kind == ROOT_VERSION_DIRECTORY || kind == ROOT_CATALOG;
    return (tableRoot && ownerId > 0 && ownerId <= MAXIMUM_OWNER_ID
            || globalRoot && ownerId == 0)
        && pageId > 0
        && pageGeneration > 0;
  }
}
