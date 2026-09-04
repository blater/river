package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.engine.runtime.DatabaseStoreLease;
import io.riverdb.platform.file.DirectoryDurability;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class IndexedTableStoreConstructionTest {
  @Test
  void checkpointPostReopenAllocationFailureClosesHandle() {
    OomSizeFile checkpointFile = new OomSizeFile();
    CheckpointState checkpoint = new CheckpointState();
    DatabaseIncarnation database = DatabaseIncarnation.of(71, 73);
    WalGeneration generation = WalGeneration.of(2);
    assertEquals(
        StatusCode.OK,
        checkpoint.setLarge(database, generation, 1, 1, 1, 1, 0));
    IndexedCheckpointLoader loader = new IndexedCheckpointLoader(
        new ReopenDirectory(checkpointFile), null, null, null, database);

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, loader.load(checkpoint, generation));
    assertEquals(1, checkpointFile.closeCount);
  }

  @Test
  void allocationFailureClosesEveryAcquiredFileAndPublishesNothing() {
    CloseFile pages = new CloseFile(StatusCode.OK);
    CloseFile rows = new CloseFile(StatusCode.OK);
    CloseFile versions = new CloseFile(StatusCode.OK);
    IndexedTableStoreOpenResult result = new IndexedTableStoreOpenResult();

    StatusCode status = IndexedTableStoreConstruction.construct(
        null, opened(pages), opened(rows), opened(versions), null, null, null, result,
        io.riverdb.engine.runtime.DatabasePageCacheTestPlan.providerLease(
            io.riverdb.engine.runtime.DatabasePageCacheTestPlan.geometry(2, 2, 2), 1),
        new DatabaseStoreLease(),
        (directory, pageFile, rowFile, versionFile, wal, database, generation,
            providerLease, storeLease) -> {
          throw new OutOfMemoryError("injected");
        });

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, status);
    assertNull(result.store());
    assertEquals(1, pages.closeCount);
    assertEquals(1, rows.closeCount);
    assertEquals(1, versions.closeCount);
  }

  @Test
  void cleanupAttemptsEveryFileAndPreservesTheFirstFailure() {
    CloseFile pages = new CloseFile(StatusCode.RETRY);
    CloseFile rows = new CloseFile(StatusCode.IO_FAILURE);
    CloseFile versions = new CloseFile(StatusCode.CORRUPTION);

    assertEquals(StatusCode.CORRUPTION, IndexedOpenFiles.close(versions, rows, pages));
    assertEquals(1, pages.closeCount);
    assertEquals(1, rows.closeCount);
    assertEquals(1, versions.closeCount);
  }

  private static DirectoryOperationResult opened(DurableFile file) {
    DirectoryOperationResult result = new DirectoryOperationResult();
    result.set(file, DirectoryDurability.VISIBLE_NOT_DURABLE);
    return result;
  }

  private static final class CloseFile implements DurableFile {
    private final StatusCode closeStatus;
    private int closeCount;

    private CloseFile(StatusCode status) { closeStatus = status; }

    @Override
    public StatusCode read(long position, ByteBuffer target, IoResult result) {
      return StatusCode.INVARIANT_BROKEN;
    }

    @Override
    public StatusCode write(long position, ByteBuffer source, IoResult result) {
      return StatusCode.INVARIANT_BROKEN;
    }

    @Override
    public StatusCode force(ForceMode mode) { return StatusCode.INVARIANT_BROKEN; }

    @Override
    public StatusCode truncate(long sizeBytes) { return StatusCode.INVARIANT_BROKEN; }

    @Override
    public StatusCode size(FileSizeResult result) { return StatusCode.INVARIANT_BROKEN; }

    @Override
    public StatusCode close() {
      closeCount++;
      return closeStatus;
    }
  }

  private static final class OomSizeFile implements DurableFile {
    private int closeCount;
    @Override public StatusCode read(long position, ByteBuffer target, IoResult result) {
      return StatusCode.INVARIANT_BROKEN;
    }
    @Override public StatusCode write(long position, ByteBuffer source, IoResult result) {
      return StatusCode.INVARIANT_BROKEN;
    }
    @Override public StatusCode force(ForceMode mode) { return StatusCode.INVARIANT_BROKEN; }
    @Override public StatusCode truncate(long sizeBytes) { return StatusCode.INVARIANT_BROKEN; }
    @Override public StatusCode size(FileSizeResult result) {
      throw new OutOfMemoryError("injected");
    }
    @Override public StatusCode close() { closeCount++; return StatusCode.OK; }
  }

  private static final class ReopenDirectory implements DurableDirectory {
    private final DurableFile file;
    private ReopenDirectory(DurableFile checkpointFile) { file = checkpointFile; }
    @Override public StatusCode reopen(String name, DirectoryOperationResult result) {
      result.set(file, DirectoryDurability.DURABLE);
      return StatusCode.OK;
    }
    @Override public StatusCode createDirectory(String name, DirectoryOperationResult result) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    @Override public StatusCode createFile(String name, DirectoryOperationResult result) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    @Override public StatusCode createTemporary(String name, DirectoryOperationResult result) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    @Override public StatusCode list(DirectoryListResult result) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    @Override public StatusCode rename(
        String source, String destination, DirectoryOperationResult result) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    @Override public StatusCode replace(
        String source, String destination, DirectoryOperationResult result) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    @Override public StatusCode remove(String name, DirectoryOperationResult result) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    @Override public StatusCode truncate(
        String name, long size, DirectoryOperationResult result) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    @Override public StatusCode force(DirectoryOperationResult result) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
  }
}
