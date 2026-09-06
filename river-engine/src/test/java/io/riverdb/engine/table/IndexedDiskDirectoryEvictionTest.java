package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class IndexedDiskDirectoryEvictionTest {
  // Each directory has 64 frames; sparse records force a 65-page working set.
  private static final int PAGES = 65;
  private static final int VERSION_PAGE_ROWS = 2048;
  private static final int LOCATION_PAGE_ROWS = 8192;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesVersionsAcrossDirtyAndCleanFrameReuse(boolean reopen, @TempDir Path root) {
    try (Files files = new Files(root)) {
      IndexedVersionDirectory versions = new IndexedVersionDirectory(files.file);
      for (int page = 0; page < PAGES; page++) {
        long row = (long) page * VERSION_PAGE_ROWS + 1;
        assertEquals(StatusCode.OK, versions.set(row, page + 10, row - 1, page % 2 == 0));
        assertEquals(StatusCode.OK, versions.setVacuumDeleted(row, page % 3 == 0));
      }
      if (reopen) {
        assertEquals(StatusCode.OK, versions.flush());
        versions = new IndexedVersionDirectory(files.file);
      }
      for (int pass = 0; pass < 3; pass++) {
        for (int offset = 0; offset < PAGES; offset++) {
          int page = pass % 2 == 0 ? offset : PAGES - offset - 1;
          assertVersion(versions, page);
        }
      }
      long growth = (long) (PAGES + 1) * VERSION_PAGE_ROWS + 1;
      assertEquals(StatusCode.OK, versions.set(growth, 100, 0, false));
      assertFalse(versions.read(growth + 1));
      assertEquals(StatusCode.OK, versions.lastStatus());
      assertVersion(versions, 0);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesLocationsAcrossDirtyAndCleanFrameReuse(boolean reopen, @TempDir Path root) {
    try (Files files = new Files(root)) {
      IndexedRowDirectory rows = new IndexedRowDirectory(files.file);
      for (int page = 0; page < PAGES; page++) {
        assertEquals(StatusCode.OK, rows.set((long) page * LOCATION_PAGE_ROWS + 1,
            page + 1, page + 2));
      }
      rows.setPublishedState((long) (PAGES - 1) * LOCATION_PAGE_ROWS + 1, PAGES, 100);
      if (reopen) {
        assertEquals(StatusCode.OK, rows.flush());
        rows = new IndexedRowDirectory(files.file);
        assertTrue(rows.matches((long) (PAGES - 1) * LOCATION_PAGE_ROWS + 1, 100));
      }
      IndexedRowLocation location = new IndexedRowLocation();
      for (int pass = 0; pass < 3; pass++) {
        for (int offset = 0; offset < PAGES; offset++) {
          int page = pass % 2 == 0 ? offset : PAGES - offset - 1;
          assertEquals(StatusCode.OK,
              rows.locate((long) page * LOCATION_PAGE_ROWS + 1, location));
          assertEquals(page + 1, location.pageId());
          assertEquals(page + 2, location.slot());
        }
      }
      long growth = (long) (PAGES + 1) * LOCATION_PAGE_ROWS + 1;
      assertEquals(StatusCode.OK, rows.set(growth, PAGES + 2, 7));
      assertEquals(StatusCode.CORRUPTION, rows.locate(growth + 1, location));
      assertEquals(StatusCode.OK, rows.locate(1, location));
      assertEquals(1, location.pageId());
      assertEquals(2, location.slot());
    }
  }

  @Test
  void retriesFailedVersionLoadWithoutAdmittingPartialFrame(@TempDir Path root) {
    try (Files files = new Files(root)) {
      IndexedVersionDirectory versions = new IndexedVersionDirectory(files.file);
      assertEquals(StatusCode.OK, versions.set(1, 10, 0, true));
      assertEquals(StatusCode.OK, versions.flush());
      versions = new IndexedVersionDirectory(files.file);
      files.file.failRead = true;
      IndexedVersionRecord version = new IndexedVersionRecord();
      assertEquals(StatusCode.IO_FAILURE, versions.lookup(1, version));
      assertFalse(version.available());
      assertEquals(StatusCode.OK, versions.lookup(1, version));
      assertTrue(version.available());
      assertEquals(10, version.commitSequence());
      assertTrue(version.deleted());
    }
  }

  @Test
  void retriesFailedLocationLoadWithoutAdmittingPartialFrame(@TempDir Path root) {
    try (Files files = new Files(root)) {
      IndexedRowDirectory rows = new IndexedRowDirectory(files.file);
      assertEquals(StatusCode.OK, rows.set(1, 9, 3));
      rows.setPublishedState(1, 9, 10);
      assertEquals(StatusCode.OK, rows.flush());
      rows = new IndexedRowDirectory(files.file);
      files.file.failRead = true;
      IndexedRowLocation location = new IndexedRowLocation();
      assertEquals(StatusCode.IO_FAILURE, rows.locate(1, location));
      assertEquals(StatusCode.OK, rows.locate(1, location));
      assertEquals(9, location.pageId());
      assertEquals(3, location.slot());
    }
  }

  private static void assertVersion(IndexedVersionDirectory versions, int page) {
    long row = (long) page * VERSION_PAGE_ROWS + 1;
    assertTrue(versions.read(row), "missing version for row " + row);
    assertEquals(page + 10, versions.commitSequence());
    assertEquals(row - 1, versions.previousRowId());
    assertEquals(page % 2 == 0, versions.deleted());
    assertEquals(page % 3 == 0, versions.vacuumDeleted());
  }

  private static final class Files implements AutoCloseable {
    private final NioDurableDirectory directory;
    private final ReadFailureFile file;

    Files(Path root) {
      NioDirectoryOpenResult opened = new NioDirectoryOpenResult();
      assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(root,
          new FatalStateFence(), new NioIoCounters(), 8, opened));
      directory = opened.directory();
      DirectoryOperationResult created = new DirectoryOperationResult();
      assertEquals(StatusCode.OK, directory.createFile("directory", created));
      file = new ReadFailureFile(created.file());
    }

    @Override
    public void close() {
      assertEquals(StatusCode.OK, file.close());
      assertEquals(StatusCode.OK, directory.close());
    }
  }

  private static final class ReadFailureFile implements DurableFile {
    private final DurableFile delegate;
    private boolean failRead;

    ReadFailureFile(DurableFile file) { delegate = file; }

    @Override
    public StatusCode read(long position, ByteBuffer target, IoResult result) {
      if (failRead) {
        failRead = false;
        target.put((byte) 99);
        result.setBytesTransferred(1);
        return StatusCode.IO_FAILURE;
      }
      return delegate.read(position, target, result);
    }

    @Override
    public StatusCode write(long position, ByteBuffer source, IoResult result) {
      return delegate.write(position, source, result);
    }
    @Override public StatusCode force(ForceMode mode) { return delegate.force(mode); }
    @Override public StatusCode truncate(long size) { return delegate.truncate(size); }
    @Override public StatusCode size(FileSizeResult result) { return delegate.size(result); }
    @Override public StatusCode close() { return delegate.close(); }
  }
}
