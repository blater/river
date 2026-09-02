package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.IoResult;
import java.util.zip.CRC32C;

/** Loads, repairs, validates, and republishes one checkpoint page. */
final class IndexedCheckpointPageLoader {
  private final DurableFile primaryFile;
  private final IndexedPageSet pages;
  private final IndexedTableKernel kernel;
  private final DatabaseIncarnation database;
  private final IoResult io = new IoResult();
  private final PageHeader header = new PageHeader();
  private final CRC32C checksum = new CRC32C();

  IndexedCheckpointPageLoader(
      DurableFile currentFile,
      IndexedPageSet pageSet,
      IndexedTableKernel tableKernel,
      DatabaseIncarnation databaseIncarnation) {
    primaryFile = currentFile;
    pages = pageSet;
    kernel = tableKernel;
    database = databaseIncarnation;
  }

  PageLoad loadPage(
      DurableFile checkpoint, int pageId, long checkpointBytes, WalGeneration generation) {
    StatusCode admitted = pages.ensureBuffers(pageId);
    if (!admitted.isOk()) return new PageLoad(admitted, false);
    long pageOffset = (long) (pageId - 1) * PageCodec.PAGE_BYTES;
    boolean loaded = false;
    StatusCode status = StatusCode.OK;
    if (pageOffset + PageCodec.PAGE_BYTES <= checkpointBytes) {
      status = pages.readCurrent(checkpoint, pageId, pageOffset, io);
      if (status.isOk()) {
        loaded = io.bytesTransferred() == PageCodec.PAGE_BYTES
            && validatePage(pageId, generation.value(), generation).isOk();
      } else if (status == StatusCode.CORRUPTION) {
        // A structurally torn checkpoint page is repaired from the forced
        // pre-rotation primary base. Provider I/O and pressure statuses remain fatal.
        status = StatusCode.OK;
      }
    }
    boolean repaired = false;
    if (status.isOk() && !loaded) {
      status = repairPage(checkpoint, pageId, pageOffset, generation);
      repaired = status.isOk();
    }
    if (status.isOk()) {
      status = pages.writeCurrent(primaryFile, pageId, pageOffset, io);
      if (status.isOk() && io.bytesTransferred() != PageCodec.PAGE_BYTES) {
        status = StatusCode.IO_FAILURE;
      }
    }
    if (status.isOk()) status = pages.installPresent(pageId);
    return new PageLoad(status, repaired);
  }

  private StatusCode repairPage(
      DurableFile checkpoint, int pageId, long pageOffset, WalGeneration generation) {
    if (generation.value() <= 1) return StatusCode.CORRUPTION;
    StatusCode status = pages.readCurrent(primaryFile, pageId, pageOffset, io);
    if (!status.isOk() || io.bytesTransferred() != PageCodec.PAGE_BYTES) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    status = validatePage(pageId, 0, generation);
    if (!status.isOk()) return StatusCode.CORRUPTION;
    status = pages.encodeCurrent(pageId, database, generation, 0, 0, checksum);
    if (status.isOk()) status = pages.writeCurrent(checkpoint, pageId, pageOffset, io);
    return status.isOk() && io.bytesTransferred() != PageCodec.PAGE_BYTES
        ? StatusCode.IO_FAILURE : status;
  }

  private StatusCode validatePage(
      int pageId, long expectedGeneration, WalGeneration generation) {
    StatusCode status = pages.validateCurrent(pageId, header, checksum);
    if (!status.isOk()
        || header.databaseHigh() != database.high()
        || header.databaseLow() != database.low()
        || header.pageId() != pageId
        || header.pageGeneration() != 1) return StatusCode.CORRUPTION;
    if ((expectedGeneration == 0 && header.walGeneration() >= generation.value())
        || (expectedGeneration != 0 && header.walGeneration() != expectedGeneration)) {
      return StatusCode.CORRUPTION;
    }
    if (expectedGeneration == generation.value()
        && (header.recordStart() != 0 || header.recordEnd() != 0)) {
      return StatusCode.CORRUPTION;
    }
    return kernel.validateCurrentPage(pageId);
  }

  static final class PageLoad {
    final StatusCode status;
    final boolean repaired;

    PageLoad(StatusCode pageStatus, boolean wasRepaired) {
      status = pageStatus;
      repaired = wasRepaired;
    }
  }
}
