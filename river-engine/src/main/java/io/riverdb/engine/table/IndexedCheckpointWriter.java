package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.util.zip.CRC32C;

/** Writes immutable zero-suffix page bases for checkpoint WAL generations. */
final class IndexedCheckpointWriter {
  private final DurableDirectory directory;
  private final IndexedPageSet pages;
  private final DatabaseIncarnation database;
  private final CRC32C checksum = new CRC32C();
  private final IoResult io = new IoResult();
  private final DirectoryOperationResult operation = new DirectoryOperationResult();

  IndexedCheckpointWriter(
      DurableDirectory durableDirectory,
      IndexedPageSet pageSet,
      DatabaseIncarnation databaseIncarnation) {
    directory = durableDirectory;
    pages = pageSet;
    database = databaseIncarnation;
  }

  StatusCode write(WalGeneration generation) {
    StatusCode status = createFile(generation);
    if (!status.isOk()) {
      return status;
    }
    DurableFile checkpoint = operation.file();
    status = writePages(checkpoint, generation);
    if (status.isOk()) {
      status = checkpoint.truncate(
          (long) pages.highestPageId() * PageCodec.PAGE_BYTES);
    }
    if (status.isOk()) {
      status = checkpoint.force(ForceMode.CONTENT_AND_METADATA);
    }
    StatusCode close = checkpoint.close();
    if (status.isOk()) {
      status = close;
    }
    return status.isOk() ? directory.force(operation) : status;
  }

  private StatusCode createFile(WalGeneration generation) {
    String name = IndexedTableStore.checkpointFileName(generation);
    operation.reset();
    StatusCode status = directory.createFile(name, operation);
    if (status != StatusCode.CONFLICT) {
      return status;
    }
    status = directory.remove(name, operation);
    if (status.isOk()) {
      status = directory.force(operation);
    }
    return status.isOk() ? directory.createFile(name, operation) : status;
  }

  private StatusCode writePages(
      DurableFile checkpoint, WalGeneration generation) {
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      if (!pages.isPresent(pageId)) {
        return StatusCode.CORRUPTION;
      }
      StatusCode status = pages.encodeCurrent(
          pageId, database, generation, 0, 0, checksum);
      if (status.isOk()) {
        status = pages.writeCurrent(
            checkpoint,
            pageId,
            (long) (pageId - 1) * PageCodec.PAGE_BYTES,
            io);
      }
      if (!status.isOk() || io.bytesTransferred() != PageCodec.PAGE_BYTES) {
        return status.isOk() ? StatusCode.IO_FAILURE : status;
      }
    }
    return StatusCode.OK;
  }
}
