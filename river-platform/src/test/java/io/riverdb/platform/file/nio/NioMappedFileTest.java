package io.riverdb.platform.file.nio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileIoMode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NioMappedFileTest {
  private static final long WINDOW_BYTES = 16L * 1024 * 1024;

  @Test
  void mapsPinnedHeaderAndCrossesDataWindow(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(
        StatusCode.OK,
        directory.createFile("mapped", FileIoMode.MAPPED, operation));
    DurableFile file = operation.file();

    byte[] header = pattern(4096, 3);
    write(file, 0, header);
    byte[] headerBoundary = {11, 12, 13, 14};
    write(file, 4094, headerBoundary);
    byte[] dataBoundary = {21, 22, 23, 24};
    write(file, WINDOW_BYTES - 2, dataBoundary);

    assertArrayEquals(headerBoundary, read(file, 4094, headerBoundary.length));
    assertArrayEquals(dataBoundary, read(file, WINDOW_BYTES - 2, dataBoundary.length));

    FileSizeResult size = new FileSizeResult();
    assertEquals(StatusCode.OK, file.size(size));
    assertEquals(2 * WINDOW_BYTES, size.sizeBytes());
    assertEquals(StatusCode.OK, file.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void forceCloseAndReopenPreservesMappedData(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(
        StatusCode.OK,
        directory.createFile("mapped", FileIoMode.MAPPED, operation));
    DurableFile file = operation.file();
    byte[] first = pattern(37, 17);
    byte[] second = pattern(53, 41);
    write(file, 8192, first);
    write(file, WINDOW_BYTES + 29, second);
    assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, file.close());

    assertEquals(
        StatusCode.OK,
        directory.reopen("mapped", FileIoMode.MAPPED, operation));
    DurableFile reopened = operation.file();
    assertArrayEquals(first, read(reopened, 8192, first.length));
    assertArrayEquals(second, read(reopened, WINDOW_BYTES + 29, second.length));
    assertEquals(StatusCode.OK, reopened.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void supportsSparseWriteBeyondTwoGiBWithSmallBuffers(@TempDir Path root) throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(
        StatusCode.OK,
        directory.createFile("sparse", FileIoMode.MAPPED, operation));
    DurableFile file = operation.file();
    long position = 2L * 1024 * 1024 * 1024 + 37;
    byte[] payload = {31, 32, 33, 34, 35, 36};
    write(file, position, payload);
    assertArrayEquals(payload, read(file, position, payload.length));

    long expectedPhysicalSize = position - position % WINDOW_BYTES + WINDOW_BYTES;
    FileSizeResult size = new FileSizeResult();
    assertEquals(StatusCode.OK, file.size(size));
    assertEquals(expectedPhysicalSize, size.sizeBytes());
    assertEquals(expectedPhysicalSize, Files.size(root.resolve("sparse")));
    assertTrue(size.sizeBytes() > position + payload.length);
    assertEquals(StatusCode.OK, file.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void truncateReleasesMappingsAndAllowsSubsequentIo(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(
        StatusCode.OK,
        directory.createFile("mapped", FileIoMode.MAPPED, operation));
    DurableFile file = operation.file();
    write(file, WINDOW_BYTES + 7, new byte[] {41, 42, 43});

    assertEquals(StatusCode.OK, file.truncate(4096));
    FileSizeResult size = new FileSizeResult();
    assertEquals(StatusCode.OK, file.size(size));
    assertEquals(4096, size.sizeBytes());

    byte[] replacement = {51, 52, 53, 54};
    write(file, 0, replacement);
    assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT));
    assertArrayEquals(replacement, read(file, 0, replacement.length));
    assertEquals(StatusCode.OK, file.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void generationAndCloseInvalidateMappedHandle(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(
        StatusCode.OK,
        directory.createFile("mapped", FileIoMode.MAPPED, operation));
    DurableFile generationHandle = operation.file();
    write(generationHandle, 0, new byte[] {61});

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    IoResult io = new IoResult();
    assertEquals(
        StatusCode.CANCELLED,
        generationHandle.read(0, ByteBuffer.allocate(1), io));
    assertEquals(StatusCode.CLOSED, generationHandle.close());

    assertEquals(
        StatusCode.OK,
        directory.reopen("mapped", FileIoMode.MAPPED, operation));
    DurableFile closedHandle = operation.file();
    assertEquals(StatusCode.OK, closedHandle.close());
    assertEquals(StatusCode.CLOSED, closedHandle.close());
    assertEquals(
        StatusCode.CANCELLED,
        closedHandle.read(0, ByteBuffer.allocate(1), io));
    assertEquals(StatusCode.OK, directory.close());
  }

  private static NioDurableDirectory openDirectory(Path root) {
    NioDirectoryOpenResult opened = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            8,
            opened));
    return opened.directory();
  }

  private static void write(DurableFile file, long position, byte[] bytes) {
    IoResult result = new IoResult();
    assertEquals(StatusCode.OK, file.write(position, ByteBuffer.wrap(bytes), result));
    assertEquals(bytes.length, result.bytesTransferred());
  }

  private static byte[] read(DurableFile file, long position, int length) {
    ByteBuffer target = ByteBuffer.allocate(length);
    IoResult result = new IoResult();
    assertEquals(StatusCode.OK, file.read(position, target, result));
    assertEquals(length, result.bytesTransferred());
    return target.array();
  }

  private static byte[] pattern(int length, int seed) {
    byte[] bytes = new byte[length];
    for (int index = 0; index < bytes.length; index++) {
      bytes[index] = (byte) (seed + index * 13);
    }
    return bytes;
  }
}
