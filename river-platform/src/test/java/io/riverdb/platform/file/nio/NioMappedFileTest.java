package io.riverdb.platform.file.nio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileIoMode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
  void rangedForceCoversHeaderAndDataWindowBoundaries(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(
        StatusCode.OK,
        directory.createFile("mapped", FileIoMode.MAPPED, operation));
    DurableFile file = operation.file();
    byte[] headerBoundary = {11, 12, 13, 14};
    byte[] dataBoundary = {21, 22, 23, 24};
    write(file, 4094, headerBoundary);
    write(file, WINDOW_BYTES - 2, dataBoundary);

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        file.force(4094, 4094, ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK,
        file.force(4094, 4094 + headerBoundary.length, ForceMode.CONTENT));
    assertEquals(StatusCode.OK,
        file.force(WINDOW_BYTES - 2, WINDOW_BYTES + 2, ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, file.close());

    assertEquals(
        StatusCode.OK,
        directory.reopen("mapped", FileIoMode.MAPPED, operation));
    DurableFile reopened = operation.file();
    assertArrayEquals(headerBoundary, read(reopened, 4094, headerBoundary.length));
    assertArrayEquals(dataBoundary, read(reopened, WINDOW_BYTES - 2, dataBoundary.length));
    assertEquals(StatusCode.OK, reopened.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void partialRangeThenLaterForceAndEvictionPreserveEarlierWrites(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(
        StatusCode.OK,
        directory.createFile("mapped", FileIoMode.MAPPED, operation));
    DurableFile file = operation.file();
    byte[] first = {31, 32, 33, 34};
    byte[] second = {41, 42, 43, 44};
    byte[] evicted = {51, 52, 53, 54};
    write(file, 8 * 1024, first);
    write(file, 16 * 1024, second);
    assertEquals(StatusCode.OK,
        file.force(8 * 1024, 8 * 1024 + first.length, ForceMode.CONTENT));

    // Moving to the next mapping evicts the first one; the later range force must
    // leave the earlier, already synchronized bytes intact.
    write(file, WINDOW_BYTES + 17, evicted);
    assertEquals(StatusCode.OK,
        file.force(16 * 1024, 16 * 1024 + second.length, ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, file.close());

    assertEquals(
        StatusCode.OK,
        directory.reopen("mapped", FileIoMode.MAPPED, operation));
    DurableFile reopened = operation.file();
    assertArrayEquals(first, read(reopened, 8 * 1024, first.length));
    assertArrayEquals(second, read(reopened, 16 * 1024, second.length));
    assertArrayEquals(evicted, read(reopened, WINDOW_BYTES + 17, evicted.length));
    assertEquals(StatusCode.OK, reopened.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void heldRangeForceAllowsSameWindowSuffixAndRetainsItsDirt(@TempDir Path root)
      throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("mapped", FileIoMode.MAPPED, operation));
    NioDurableFile file = (NioDurableFile) operation.file();
    long prefixPosition = 8 * 1024;
    byte[] prefix = {31, 32, 33, 34};
    long suffixPosition = 16 * 1024;
    byte[] suffix = {41, 42, 43, 44};
    write(file, prefixPosition, prefix);

    CountDownLatch captured = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean firstCapturedDirt = new AtomicBoolean();
    file.installMappedForceGate((dataDirty, metadataDirty) -> {
      firstCapturedDirt.set(dataDirty);
      captured.countDown();
      awaitGate(release);
    });
    AtomicReference<StatusCode> forceStatus = new AtomicReference<>();
    Thread forceThread = Thread.ofVirtual().start(() -> forceStatus.set(
        file.force(prefixPosition, prefixPosition + prefix.length, ForceMode.CONTENT)));
    assertTrue(captured.await(5, TimeUnit.SECONDS));

    IoResult suffixResult = new IoResult();
    AtomicReference<StatusCode> suffixStatus = new AtomicReference<>();
    CountDownLatch suffixDone = new CountDownLatch(1);
    Thread suffixThread = Thread.ofVirtual().start(() -> {
      suffixStatus.set(file.write(suffixPosition, ByteBuffer.wrap(suffix), suffixResult));
      suffixDone.countDown();
    });
    try {
      assertTrue(suffixDone.await(5, TimeUnit.SECONDS));
      assertEquals(StatusCode.OK, suffixStatus.get());
      assertEquals(suffix.length, suffixResult.bytesTransferred());
    } finally {
      release.countDown();
    }
    join(forceThread);
    join(suffixThread);
    assertTrue(firstCapturedDirt.get());
    assertEquals(StatusCode.OK, forceStatus.get());

    AtomicBoolean secondCapturedDirt = new AtomicBoolean();
    file.installMappedForceGate((dataDirty, metadataDirty) -> secondCapturedDirt.set(dataDirty));
    assertEquals(StatusCode.OK,
        file.force(suffixPosition, suffixPosition + suffix.length, ForceMode.CONTENT));
    assertTrue(secondCapturedDirt.get());
    file.installMappedForceGate(null);
    assertEquals(StatusCode.OK, file.close());

    assertEquals(StatusCode.OK, directory.reopen("mapped", FileIoMode.MAPPED, operation));
    DurableFile reopened = operation.file();
    assertArrayEquals(prefix, read(reopened, prefixPosition, prefix.length));
    assertArrayEquals(suffix, read(reopened, suffixPosition, suffix.length));
    assertEquals(StatusCode.OK, reopened.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void boundaryCrossingWriteJoinsHeldForceWithoutPartialRetry(@TempDir Path root)
      throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("mapped", FileIoMode.MAPPED, operation));
    NioDurableFile file = (NioDurableFile) operation.file();
    long prefixPosition = WINDOW_BYTES - 8;
    byte[] prefix = {51, 52, 53, 54};
    long suffixPosition = prefixPosition + prefix.length;
    byte[] suffix = {61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72};
    write(file, prefixPosition, prefix);

    CountDownLatch captured = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    file.installMappedForceGate((dataDirty, metadataDirty) -> {
      captured.countDown();
      awaitGate(release);
    });
    AtomicReference<StatusCode> forceStatus = new AtomicReference<>();
    Thread forceThread = Thread.ofVirtual().start(() -> forceStatus.set(
        file.force(prefixPosition, suffixPosition, ForceMode.CONTENT)));
    assertTrue(captured.await(5, TimeUnit.SECONDS));

    IoResult writeResult = new IoResult();
    AtomicReference<StatusCode> writeStatus = new AtomicReference<>();
    CountDownLatch writeDone = new CountDownLatch(1);
    Thread writer = Thread.ofVirtual().start(() -> {
      writeStatus.set(file.write(suffixPosition, ByteBuffer.wrap(suffix), writeResult));
      writeDone.countDown();
    });
    try {
      assertTrue(awaitBytes(file, suffixPosition, Arrays.copyOf(suffix, 4)));
      assertFalse(writeDone.await(100, TimeUnit.MILLISECONDS));
      writer.interrupt();
    } finally {
      release.countDown();
    }
    join(forceThread);
    join(writer);
    assertEquals(StatusCode.OK, forceStatus.get());
    assertEquals(StatusCode.OK, writeStatus.get());
    assertEquals(suffix.length, writeResult.bytesTransferred());
    assertTrue(writer.isInterrupted());
    file.installMappedForceGate(null);
    assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, file.close());

    assertEquals(StatusCode.OK, directory.reopen("mapped", FileIoMode.MAPPED, operation));
    DurableFile reopened = operation.file();
    assertArrayEquals(suffix, read(reopened, suffixPosition, suffix.length));
    assertEquals(StatusCode.OK, reopened.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void mappedForceFailureRetainsDirtAndReleasesLifecyclePin(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("mapped", FileIoMode.MAPPED, operation));
    NioDurableFile file = (NioDurableFile) operation.file();
    long position = 12 * 1024;
    byte[] payload = {81, 82, 83, 84};
    write(file, position, payload);

    AtomicBoolean failedCapturedData = new AtomicBoolean();
    AtomicBoolean failedCapturedMetadata = new AtomicBoolean();
    file.installMappedForceGate((dataDirty, metadataDirty) -> {
      failedCapturedData.set(dataDirty);
      failedCapturedMetadata.set(metadataDirty);
      throw new IOException("injected mapped force failure");
    });
    assertEquals(StatusCode.IO_FAILURE,
        file.force(position, position + payload.length, ForceMode.CONTENT_AND_METADATA));
    assertTrue(failedCapturedData.get());
    assertTrue(failedCapturedMetadata.get());

    AtomicBoolean retryCapturedData = new AtomicBoolean();
    AtomicBoolean retryCapturedMetadata = new AtomicBoolean();
    file.installMappedForceGate((dataDirty, metadataDirty) -> {
      retryCapturedData.set(dataDirty);
      retryCapturedMetadata.set(metadataDirty);
    });
    assertEquals(StatusCode.OK,
        file.force(position, position + payload.length, ForceMode.CONTENT_AND_METADATA));
    assertTrue(retryCapturedData.get());
    assertTrue(retryCapturedMetadata.get());
    file.installMappedForceGate(null);
    assertEquals(StatusCode.OK, file.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void closeWaitsForMappedForcePinBeforeRetiringMapping(@TempDir Path root) throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("mapped", FileIoMode.MAPPED, operation));
    NioDurableFile file = (NioDurableFile) operation.file();
    long position = 8 * 1024;
    write(file, position, new byte[] {91, 92, 93, 94});

    CountDownLatch captured = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    file.installMappedForceGate((dataDirty, metadataDirty) -> {
      captured.countDown();
      awaitGate(release);
    });
    AtomicReference<StatusCode> forceStatus = new AtomicReference<>();
    Thread forceThread = Thread.ofVirtual().start(() -> forceStatus.set(
        file.force(position, position + 4, ForceMode.CONTENT_AND_METADATA)));
    assertTrue(captured.await(5, TimeUnit.SECONDS));

    AtomicReference<StatusCode> queuedForceStatus = new AtomicReference<>();
    Thread queuedForceThread = Thread.ofVirtual().start(() -> queuedForceStatus.set(
        file.force(position, position + 4, ForceMode.CONTENT_AND_METADATA)));
    assertTrue(awaitThreadState(queuedForceThread, Thread.State.WAITING));

    AtomicReference<StatusCode> closeStatus = new AtomicReference<>();
    CountDownLatch closeEntered = new CountDownLatch(1);
    CountDownLatch closeDone = new CountDownLatch(1);
    Thread closeThread = Thread.ofVirtual().start(() -> {
      closeEntered.countDown();
      closeStatus.set(file.close());
      closeDone.countDown();
    });
    assertTrue(closeEntered.await(5, TimeUnit.SECONDS));
    try {
      assertTrue(awaitClosed(file, position));
      assertFalse(closeDone.await(100, TimeUnit.MILLISECONDS));
    } finally {
      release.countDown();
    }
    join(forceThread);
    join(queuedForceThread);
    join(closeThread);
    assertEquals(StatusCode.OK, forceStatus.get());
    assertEquals(StatusCode.CLOSED, queuedForceStatus.get());
    assertEquals(StatusCode.OK, closeStatus.get());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void generationChangeJoinsForceWithoutDirectoryFileLockInversion(@TempDir Path root)
      throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("mapped", FileIoMode.MAPPED, operation));
    NioDurableFile file = (NioDurableFile) operation.file();
    long position = 8 * 1024;
    write(file, position, new byte[] {95, 96, 97, 98});

    CountDownLatch captured = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    file.installMappedForceGate((dataDirty, metadataDirty) -> {
      captured.countDown();
      awaitGate(release);
    });
    AtomicReference<StatusCode> forceStatus = new AtomicReference<>();
    Thread forceThread = Thread.ofVirtual().start(() -> forceStatus.set(
        file.force(position, position + 4, ForceMode.CONTENT_AND_METADATA)));
    assertTrue(captured.await(5, TimeUnit.SECONDS));

    AtomicReference<StatusCode> queuedForceStatus = new AtomicReference<>();
    Thread queuedForceThread = Thread.ofVirtual().start(() -> queuedForceStatus.set(
        file.force(position, position + 4, ForceMode.CONTENT_AND_METADATA)));
    assertTrue(awaitThreadState(queuedForceThread, Thread.State.WAITING));

    AtomicReference<StatusCode> generationStatus = new AtomicReference<>();
    Thread generationThread = Thread.ofVirtual().start(
        () -> generationStatus.set(directory.advanceGeneration()));
    assertTrue(awaitThreadState(generationThread, Thread.State.WAITING));
    release.countDown();

    join(forceThread);
    join(queuedForceThread);
    join(generationThread);
    assertEquals(StatusCode.OK, forceStatus.get());
    assertEquals(StatusCode.CANCELLED, queuedForceStatus.get());
    assertEquals(StatusCode.OK, generationStatus.get());
    assertEquals(StatusCode.CLOSED, file.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void truncateWaitsForMappedForcePinBeforeRemapping(@TempDir Path root) throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("mapped", FileIoMode.MAPPED, operation));
    NioDurableFile file = (NioDurableFile) operation.file();
    long position = WINDOW_BYTES + 8;
    write(file, position, new byte[] {101, 102, 103, 104});

    CountDownLatch captured = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    file.installMappedForceGate((dataDirty, metadataDirty) -> {
      captured.countDown();
      awaitGate(release);
    });
    AtomicReference<StatusCode> forceStatus = new AtomicReference<>();
    Thread forceThread = Thread.ofVirtual().start(() -> forceStatus.set(
        file.force(position, position + 4, ForceMode.CONTENT_AND_METADATA)));
    assertTrue(captured.await(5, TimeUnit.SECONDS));

    AtomicReference<StatusCode> truncateStatus = new AtomicReference<>();
    CountDownLatch truncateEntered = new CountDownLatch(1);
    CountDownLatch truncateDone = new CountDownLatch(1);
    Thread truncateThread = Thread.ofVirtual().start(() -> {
      truncateEntered.countDown();
      truncateStatus.set(file.truncate(4096));
      truncateDone.countDown();
    });
    assertTrue(truncateEntered.await(5, TimeUnit.SECONDS));
    try {
      assertFalse(truncateDone.await(100, TimeUnit.MILLISECONDS));
    } finally {
      release.countDown();
    }
    join(forceThread);
    join(truncateThread);
    assertEquals(StatusCode.OK, forceStatus.get());
    assertEquals(StatusCode.OK, truncateStatus.get());
    FileSizeResult size = new FileSizeResult();
    assertEquals(StatusCode.OK, file.size(size));
    assertEquals(4096, size.sizeBytes());
    file.installMappedForceGate(null);
    assertEquals(StatusCode.OK, file.close());
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

  private static boolean awaitBytes(NioDurableFile file, long position, byte[] expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    do {
      if (Arrays.equals(expected, read(file, position, expected.length))) return true;
      Thread.onSpinWait();
    } while (System.nanoTime() < deadline);
    return false;
  }

  private static boolean awaitClosed(NioDurableFile file, long position)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    IoResult result = new IoResult();
    do {
      StatusCode status = file.read(position, ByteBuffer.allocate(1), result);
      if (status == StatusCode.CLOSED) return true;
      if (status != StatusCode.OK) return false;
      Thread.onSpinWait();
    } while (System.nanoTime() < deadline);
    return false;
  }

  private static boolean awaitThreadState(Thread thread, Thread.State expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    do {
      if (thread.getState() == expected) return true;
      Thread.onSpinWait();
    } while (System.nanoTime() < deadline);
    return false;
  }

  private static void awaitGate(CountDownLatch release) throws IOException {
    try {
      release.await();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while holding mapped force", failure);
    }
  }

  private static void join(Thread thread) throws InterruptedException {
    thread.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(thread.isAlive());
  }
}
