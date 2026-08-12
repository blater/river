package io.riverdb.platform.file.nio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NioDurableDirectoryTest {
  @Test
  void createsWritesForcesReopensAndReads(@TempDir Path root) {
    NioIoCounters counters = new NioIoCounters();
    NioDirectoryOpenResult openResult = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            counters,
            4,
            openResult));

    NioDurableDirectory directory = openResult.directory();
    DirectoryOperationResult operationResult = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("control", operationResult));

    byte[] expected = {3, 1, 4, 1, 5, 9};
    IoResult ioResult = new IoResult();
    DurableFile created = operationResult.file();
    assertEquals(StatusCode.OK, created.write(0, ByteBuffer.wrap(expected), ioResult));
    assertEquals(expected.length, ioResult.bytesTransferred());
    assertEquals(StatusCode.OK, created.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, created.close());

    assertEquals(StatusCode.OK, directory.reopen("control", operationResult));
    ByteBuffer actual = ByteBuffer.allocate(expected.length);
    ioResult.reset();
    DurableFile reopened = operationResult.file();
    assertEquals(StatusCode.OK, reopened.read(0, actual, ioResult));
    assertEquals(expected.length, ioResult.bytesTransferred());
    assertEquals(StatusCode.OK, reopened.close());

    assertArrayEquals(expected, actual.array());
    assertEquals(expected.length, counters.bytesWritten());
    assertEquals(expected.length, counters.bytesRead());
    assertEquals(2, counters.handlesOpened());
    assertEquals(1, counters.forceCalls());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void rejectsDirectorySymlinkAndReadOnlyTarget(@TempDir Path temporaryRoot) throws Exception {
    Path root = Files.createDirectory(temporaryRoot.resolve("database"));
    Path external = temporaryRoot.resolve("external");
    Files.write(external, new byte[] {42});
    Files.createSymbolicLink(root.resolve("link"), external);
    Files.createDirectory(root.resolve("child"));

    NioDirectoryOpenResult openResult = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            4,
            openResult));
    NioDurableDirectory directory = openResult.directory();
    DirectoryOperationResult operationResult = new DirectoryOperationResult();
    assertEquals(StatusCode.CONFLICT, directory.reopen("link", operationResult));
    assertEquals(StatusCode.CONFLICT, directory.reopen("child", operationResult));

    assertEquals(StatusCode.OK, directory.createFile("data", operationResult));
    DurableFile file = operationResult.file();
    IoResult ioResult = new IoResult();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        file.read(0, ByteBuffer.allocate(1).asReadOnlyBuffer(), ioResult));
    assertEquals(StatusCode.OK, file.close());
    assertArrayEquals(new byte[] {42}, Files.readAllBytes(external));
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void truncateSetsExactLargerSize(@TempDir Path root) {
    NioDirectoryOpenResult openResult = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            4,
            openResult));
    NioDurableDirectory directory = openResult.directory();
    DirectoryOperationResult operationResult = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("data", operationResult));
    IoResult ioResult = new IoResult();
    assertEquals(
        StatusCode.OK,
        operationResult.file().write(0, ByteBuffer.wrap(new byte[] {7, 8}), ioResult));
    assertEquals(StatusCode.OK, operationResult.file().close());

    assertEquals(StatusCode.OK, directory.truncate("data", 8, operationResult));
    FileSizeResult sizeResult = new FileSizeResult();
    assertEquals(StatusCode.OK, operationResult.file().size(sizeResult));
    assertEquals(8, sizeResult.sizeBytes());
    assertEquals(StatusCode.OK, operationResult.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }
}
