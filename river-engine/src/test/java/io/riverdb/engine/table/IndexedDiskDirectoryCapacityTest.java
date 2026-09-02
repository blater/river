package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedDiskDirectoryCapacityTest {
  private static final long AUDIT_ROW_ID = 3_000_000_000L;

  @Test
  void persistsRowAndVersionMetadataAtThreeBillionthLogicalRow(@TempDir Path root) {
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            8,
            directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    DirectoryOperationResult rowsOperation = new DirectoryOperationResult();
    DirectoryOperationResult versionsOperation = new DirectoryOperationResult();
    assertEquals(
        StatusCode.OK,
        directory.createFile(IndexedRowDirectory.FILE_NAME, rowsOperation));
    assertEquals(
        StatusCode.OK,
        directory.createFile(IndexedVersionDirectory.FILE_NAME, versionsOperation));

    IndexedRowDirectory rows = new IndexedRowDirectory(rowsOperation.file());
    rows.set(AUDIT_ROW_ID, 123_456, 7);
    rows.setPublishedState(AUDIT_ROW_ID, 123_456, 9);
    assertEquals(StatusCode.OK, rows.flush());

    IndexedVersionDirectory versions = new IndexedVersionDirectory(versionsOperation.file());
    versions.set(AUDIT_ROW_ID, 9, AUDIT_ROW_ID - 1, false);
    versions.setVacuumDeleted(AUDIT_ROW_ID, true);
    assertEquals(false, versions.deleted());
    assertEquals(true, versions.vacuumDeleted());
    assertEquals(StatusCode.OK, versions.flush());
    assertEquals(StatusCode.OK, rows.close());
    assertEquals(StatusCode.OK, versions.close());
    assertEquals(StatusCode.OK, directory.close());

    directoryResult = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            8,
            directoryResult));
    directory = directoryResult.directory();
    rowsOperation = new DirectoryOperationResult();
    versionsOperation = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.reopen(IndexedRowDirectory.FILE_NAME, rowsOperation));
    assertEquals(
        StatusCode.OK,
        directory.reopen(IndexedVersionDirectory.FILE_NAME, versionsOperation));
    rows = new IndexedRowDirectory(rowsOperation.file());
    versions = new IndexedVersionDirectory(versionsOperation.file());

    assertTrue(rows.matches(AUDIT_ROW_ID, 9));
    assertEquals(123_456, rows.pageId(AUDIT_ROW_ID));
    assertEquals(7, rows.slot(AUDIT_ROW_ID));
    assertTrue(versions.read(AUDIT_ROW_ID));
    assertEquals(9, versions.commitSequence());
    assertEquals(AUDIT_ROW_ID - 1, versions.previousRowId());
    assertEquals(false, versions.deleted());
    assertEquals(true, versions.vacuumDeleted());
    assertEquals(StatusCode.OK, rows.close());
    assertEquals(StatusCode.OK, versions.close());
    assertEquals(StatusCode.OK, directory.close());
  }
}
