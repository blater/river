package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Durable page-frame I/O kept separate from frame admission and eviction. */
final class IndexedPageFrameIo {
  private static final long STAGING_BASE_OFFSET =
      (long) IndexedTableLimits.MAX_PAGES * PageCodec.PAGE_BYTES
          + (IndexedTableLimits.MAX_ROWS + 1L) * IndexedVersionDirectory.RECORD_BYTES;
  private final DurableFile backingFile;
  private final DurableFile stagingFile;
  private final DatabaseIncarnation database;
  private WalGeneration generation;
  private final CRC32C writeBackChecksum = new CRC32C();
  private final CRC32C identityChecksum = new CRC32C();
  private final PageHeader identityHeader = new PageHeader();
  private final IoResult io = new IoResult();
  private final IndexedPageState state;

  IndexedPageFrameIo(
      DurableFile file,
      DurableFile staging,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      IndexedPageState pageState) {
    backingFile = file;
    stagingFile = staging;
    database = databaseIncarnation;
    generation = walGeneration;
    state = pageState;
  }

  void setGeneration(WalGeneration walGeneration) { generation = walGeneration; }

  StatusCode encode(
      IndexedPageFrame frame, DatabaseIncarnation database, WalGeneration generation,
      long start, long end, CRC32C checksum) {
    return PageCodec.encode(
        database, generation, frame.pageId, 1, start, end,
        frame.payloadKind,
        frame.ownerKeyId,
        frame.payloadKind == PageCodec.PAYLOAD_KIND_FREE
            ? PageCodec.FREE_PAYLOAD_BYTES : PageCodec.MAX_PAYLOAD_BYTES,
        frame.page, checksum);
  }

  StatusCode read(DurableFile file, IndexedPageFrame frame, long offset, IoResult result) {
    frame.page.clear();
    StatusCode status = file.read(offset, frame.page, result);
    if (!status.isOk() || result.bytesTransferred() != PageCodec.PAGE_BYTES) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    frame.prepare();
    return captureIdentity(frame);
  }

  StatusCode readCurrent(IndexedPageFrame frame, IoResult result) {
    if (backingFile == null) return StatusCode.RESOURCE_EXHAUSTED;
    return read(
        backingFile, frame, (long) (frame.pageId - 1) * PageCodec.PAGE_BYTES, result);
  }

  StatusCode write(DurableFile file, IndexedPageFrame frame, long offset, IoResult result) {
    frame.page.position(0);
    frame.page.limit(PageCodec.PAGE_BYTES);
    return file.write(offset, frame.page, result);
  }

  StatusCode validate(IndexedPageFrame frame, PageHeader header, CRC32C checksum) {
    return PageCodec.validate(frame.page, header, checksum);
  }

  StatusCode validateRecord(
      ByteBuffer source, int offset, PageHeader header, CRC32C checksum) {
    return PageCodec.validateAt(source, offset, header, checksum);
  }

  StatusCode captureIdentity(IndexedPageFrame frame) {
    StatusCode status = PageCodec.validate(frame.page, identityHeader, identityChecksum);
    if (!status.isOk()
        || identityHeader.databaseHigh() != database.high()
        || identityHeader.databaseLow() != database.low()
        || identityHeader.pageId() != frame.pageId) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    frame.identity(identityHeader.payloadKind(), identityHeader.ownerKeyId());
    frame.recordStart = identityHeader.recordStart();
    frame.recordEnd = identityHeader.recordEnd();
    return StatusCode.OK;
  }

  StatusCode writeBack(IndexedPageFrame frame) {
    if (!frame.dirty) return StatusCode.OK;
    if (backingFile == null || database == null || generation == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = encode(
        frame, database, generation, frame.recordStart, frame.recordEnd, writeBackChecksum);
    if (!status.isOk()) return status;
    status = write(
        backingFile, frame, (long) (frame.pageId - 1) * PageCodec.PAGE_BYTES, io);
    if (status.isOk() && io.bytesTransferred() != PageCodec.PAGE_BYTES) {
      status = StatusCode.IO_FAILURE;
    }
    if (status.isOk()) {
      frame.dirty = false;
      state.markClean(frame.pageId);
    }
    return status;
  }

  StatusCode writeStaged(IndexedPageFrame frame) {
    if (stagingFile == null) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = write(
        stagingFile,
        frame,
        STAGING_BASE_OFFSET + (long) (frame.pageId - 1) * PageCodec.PAGE_BYTES,
        io);
    return status.isOk() && io.bytesTransferred() != PageCodec.PAGE_BYTES
        ? StatusCode.IO_FAILURE : status;
  }

  StatusCode loadStaged(IndexedPageFrame frame) {
    if (stagingFile == null) return StatusCode.RESOURCE_EXHAUSTED;
    frame.page.clear();
    StatusCode status = stagingFile.read(
        STAGING_BASE_OFFSET + (long) (frame.pageId - 1) * PageCodec.PAGE_BYTES,
        frame.page, io);
    if (!status.isOk()) return status;
    if (io.bytesTransferred() != PageCodec.PAGE_BYTES) return StatusCode.CORRUPTION;
    frame.prepare();
    return StatusCode.OK;
  }

  StatusCode forceBacking() {
    return backingFile == null
        ? StatusCode.RESOURCE_EXHAUSTED
        : backingFile.force(ForceMode.CONTENT_AND_METADATA);
  }
}
