package io.riverdb.platform.file.nio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
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
}
