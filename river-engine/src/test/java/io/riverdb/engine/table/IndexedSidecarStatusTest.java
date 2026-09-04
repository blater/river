package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.engine.TestDatabaseResources;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class IndexedSidecarStatusTest {
  @Test
  void rowDirectoryDoesNotTreatFailedPageReadAsEmptyTail() {
    ReadFailureFile file = new ReadFailureFile(2);
    IndexedRowDirectory rows = new IndexedRowDirectory(file);

    assertEquals(StatusCode.IO_FAILURE, rows.set(1, 3, 1));
    assertEquals(StatusCode.OK, rows.set(1, 3, 1));
    assertEquals(3, rows.pageId(1));
    assertEquals(1, rows.slot(1));
  }

  @Test
  void versionDirectoryDoesNotPublishAfterFailedPageRead() {
    ReadFailureFile file = new ReadFailureFile(1);
    IndexedVersionDirectory versions = new IndexedVersionDirectory(file);

    assertEquals(StatusCode.IO_FAILURE, versions.set(1, 7, 0, false));
    assertEquals(StatusCode.OK, versions.set(1, 7, 0, false));
    assertEquals(true, versions.read(1));
    assertEquals(7, versions.commitSequence());
  }

  @Test
  void versionLookupReturnsReadFailureInsteadOfOrdinaryAbsence() {
    ReadFailureFile file = new ReadFailureFile(2);
    IndexedVersionDirectory versions = new IndexedVersionDirectory(file);
    IndexedVersionRecord record = new IndexedVersionRecord();

    assertEquals(StatusCode.OK, versions.set(1, 7, 0, false));
    assertEquals(StatusCode.IO_FAILURE, versions.lookup(2_049, record));
    assertEquals(false, record.available());
    assertEquals(StatusCode.OK, versions.lookup(2_049, record));
    assertEquals(false, record.available());
  }

  @Test
  void checkpointVersionLoadPropagatesTruncateFailure() {
    ReadFailureFile rowsFile = new ReadFailureFile(-1);
    ReadFailureFile versionsFile = new ReadFailureFile(-1, true);
    IndexedVersionState versions = new IndexedVersionState(
        new IndexedRowDirectory(rowsFile), new IndexedVersionDirectory(versionsFile),
        TestDatabaseResources.databasePlan(1));
    CheckpointState checkpoint = new CheckpointState();
    assertEquals(StatusCode.OK, checkpoint.set(
        DatabaseIncarnation.of(7, 11), WalGeneration.of(1), 1, 3, 5, 3, 0));

    assertEquals(StatusCode.IO_FAILURE, versions.load(checkpoint));
  }

  @Test
  void checkpointVersionPageBoundCountsBaseDeltaUnion() {
    IndexedVersionState versions = new IndexedVersionState(
        new IndexedRowDirectory(new ReadFailureFile(-1)),
        new IndexedVersionDirectory(new ReadFailureFile(-1)),
        TestDatabaseResources.databasePlan(1));
    CheckpointState checkpoint = new CheckpointState();
    assertEquals(StatusCode.OK, checkpoint.setLarge(
        DatabaseIncarnation.of(7, 11), WalGeneration.of(1), 1, 3, 5, 3, 5_000));
    assertEquals(StatusCode.OK, checkpoint.setRowVersion(1, 2, 0, true));
    assertEquals(StatusCode.OK, checkpoint.setRowVersion(4_097, 2, 0, true));
    assertEquals(StatusCode.OK, versions.load(checkpoint));
    assertEquals(StatusCode.OK, versions.recordCommitted(1, 3, 0, true));
    assertEquals(StatusCode.OK, versions.recordCommitted(2_049, 3, 0, true));

    assertEquals(3, versions.checkpointVersionPageCountUpperBound());
    versions.resetCheckpointVersionPages();
    assertEquals(0, versions.nextCheckpointVersionPageId());
    assertEquals(1, versions.nextCheckpointVersionPageId());
    assertEquals(2, versions.nextCheckpointVersionPageId());
    assertEquals(-1, versions.nextCheckpointVersionPageId());
  }

  private static final class ReadFailureFile implements DurableFile {
    private final int failedRead;
    private final boolean failTruncate;
    private int reads;

    private ReadFailureFile(int failure) {
      this(failure, false);
    }

    private ReadFailureFile(int failure, boolean truncateFailure) {
      failedRead = failure;
      failTruncate = truncateFailure;
    }

    @Override
    public StatusCode read(long position, ByteBuffer target, IoResult result) {
      reads++;
      result.reset();
      return reads == failedRead ? StatusCode.IO_FAILURE : StatusCode.OK;
    }

    @Override
    public StatusCode write(long position, ByteBuffer source, IoResult result) {
      result.setBytesTransferred(source.remaining());
      source.position(source.limit());
      return StatusCode.OK;
    }

    @Override
    public StatusCode force(ForceMode mode) {
      return StatusCode.OK;
    }

    @Override
    public StatusCode truncate(long sizeBytes) {
      return failTruncate ? StatusCode.IO_FAILURE : StatusCode.OK;
    }

    @Override
    public StatusCode size(FileSizeResult result) {
      result.setSizeBytes(0);
      return StatusCode.OK;
    }

    @Override
    public StatusCode close() {
      return StatusCode.OK;
    }
  }
}
