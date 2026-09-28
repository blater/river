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

/** Owns page-frame encoding, validation, file I/O, and WAL-record transfers. */
final class IndexedPageFrameCodec {
  private final IndexedPageFrameCache cache;
  private final IndexedPageState state;
  private final IndexedPageFrameIo io;
  private final IndexedCurrentPageFrameStore current;
  private final IndexedStagingPageFrameStore staging;

  IndexedPageFrameCodec(
      IndexedPageFrameCache owner, IndexedPageState pageState, IndexedPageFrameIo frameIo,
      IndexedCurrentPageFrameStore currentStore, IndexedStagingPageFrameStore stagingStore) {
    cache = owner;
    state = pageState;
    io = frameIo;
    current = currentStore;
    staging = stagingStore;
  }

  StatusCode encodeCurrent(
      int pageId, DatabaseIncarnation database, WalGeneration generation,
      long start, long end, CRC32C checksum) {
    IndexedPageFrame frame = current.currentFrame(pageId, true);
    return frame == null ? cache.lastStatus()
        : io.encode(frame, database, generation, start, end, checksum);
  }

  StatusCode encodeStaged(
      int pageId, DatabaseIncarnation database, WalGeneration generation,
      long start, long end, CRC32C checksum) {
    IndexedPageFrame frame = staging.frame(pageId);
    return frame == null ? cache.lastStatus()
        : io.encode(frame, database, generation, start, end, checksum);
  }

  StatusCode readCurrent(DurableFile file, int pageId, long offset, IoResult result) {
    IndexedPageFrame frame = current.currentFrameForRead(pageId);
    if (frame == null) return cache.lastStatus();
    StatusCode status = io.read(file, frame, offset, result);
    if (!status.isOk()) current.release(pageId);
    return status;
  }

  StatusCode writeCurrent(DurableFile file, int pageId, long offset, IoResult result) {
    IndexedPageFrame frame = current.currentFrame(pageId, true);
    return frame == null ? cache.lastStatus() : io.write(file, frame, offset, result);
  }

  StatusCode validateCurrent(int pageId, PageHeader header, CRC32C checksum) {
    IndexedPageFrame frame = current.currentFrame(pageId, true);
    return frame == null ? cache.lastStatus() : io.validate(frame, header, checksum);
  }

  StatusCode validateRecord(ByteBuffer source, int offset, PageHeader header, CRC32C checksum) {
    return io.validateRecord(source, offset, header, checksum);
  }

  void copyStagedToRecord(int pageId, ByteBuffer target, int targetOffset) {
    IndexedPageFrame frame = staging.frame(pageId);
    if (frame != null) copyPage(frame.page, target, targetOffset);
  }

  StatusCode installFromRecord(
      ByteBuffer source, int sourceOffset, int pageId, long start, long end) {
    StatusCode status = state.reservePublication(pageId);
    if (!status.isOk()) return cache.setStatus(status);
    IndexedPageFrame frame = current.currentFrameForRead(pageId);
    if (frame == null) {
      state.cancelReservation(pageId);
      return cache.lastStatus();
    }
    copyFromRecord(source, sourceOffset, frame.page);
    frame.invalidatePageValidation();
    status = io.captureIdentity(frame);
    if (!status.isOk()) {
      current.release(pageId);
      state.cancelReservation(pageId);
      return cache.setStatus(status);
    }
    state.installPresent(pageId);
    return cache.markCurrentChanged(pageId, start, end);
  }

  static void copyPage(ByteBuffer source, ByteBuffer target, int offset) {
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      target.put(offset + index, source.get(index));
    }
  }

  private static void copyFromRecord(ByteBuffer source, int offset, ByteBuffer target) {
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      target.put(index, source.get(offset + index));
    }
    target.position(0);
    target.limit(PageCodec.PAGE_BYTES);
  }

  static void copyPage(ByteBuffer source, ByteBuffer target) {
    target.put(0, source, 0, PageCodec.PAGE_BYTES);
    target.position(0);
    target.limit(PageCodec.PAGE_BYTES);
  }
}
