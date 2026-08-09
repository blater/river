package io.riverdb.engine.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.control.ControlFile;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DatabaseControlStoreTest {
  @Test
  void createsClosesAndReopensDatabaseControl(@TempDir Path root) {
    ControlFile expected = new ControlFile(
        DatabaseIncarnation.of(0x1020304050607080L, 0x1122334455667788L),
        WalGeneration.of(1));
    DatabaseControlResult result = new DatabaseControlResult();

    NioDurableDirectory first = openDirectory(root);
    assertEquals(StatusCode.OK, DatabaseControlStore.create(first, expected, result));
    assertEquals(expected, result.controlFile());
    assertEquals(StatusCode.OK, first.close());

    NioDurableDirectory reopened = openDirectory(root);
    assertEquals(StatusCode.OK, DatabaseControlStore.open(reopened, result));
    assertEquals(expected, result.controlFile());
    assertEquals(StatusCode.OK, reopened.close());
  }

  @Test
  void rejectsCorruptDurableControl(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    DatabaseControlResult result = new DatabaseControlResult();
    ControlFile control = new ControlFile(
        DatabaseIncarnation.of(7, 9),
        WalGeneration.of(1));
    assertEquals(StatusCode.OK, DatabaseControlStore.create(directory, control, result));

    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(
        StatusCode.OK,
        directory.reopen(DatabaseControlStore.CONTROL_FILE_NAME, operation));
    DurableFile file = operation.file();
    IoResult io = new IoResult();
    assertEquals(StatusCode.OK, file.write(31, ByteBuffer.wrap(new byte[] {1}), io));
    assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, file.close());

    assertEquals(StatusCode.CORRUPTION, DatabaseControlStore.open(directory, result));
    assertEquals(StatusCode.OK, directory.close());
  }

  private static NioDurableDirectory openDirectory(Path root) {
    NioDirectoryOpenResult open = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            8,
            open));
    return open.directory();
  }
}
