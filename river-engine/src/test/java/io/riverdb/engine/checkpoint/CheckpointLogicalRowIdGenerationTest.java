package io.riverdb.engine.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckpointLogicalRowIdGenerationTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(991, 997);

  @Test
  void streamsAndLoadsSortedCommittedFloors(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    CheckpointState state = state(7, 31);
    CheckpointLogicalRowIdManifestReference reference = new CheckpointLogicalRowIdManifestReference();
    ArraySource source = new ArraySource(
        new long[] {2, 17, 9_000_000_000L}, new long[] {19, 1, Long.MAX_VALUE});

    assertEquals(StatusCode.OK, new CheckpointLogicalRowIdGenerationWriter().install(
        directory, state, source, 0, -1, reference));
    assertEquals(3, reference.count());
    assertEquals(CheckpointLogicalRowIdFormat.HEADER_BYTES
        + 3L * CheckpointLogicalRowIdFormat.RECORD_BYTES, reference.fileBytes());

    CheckpointLogicalRowIdDirectory loaded = new CheckpointLogicalRowIdDirectory();
    assertEquals(StatusCode.OK, new CheckpointLogicalRowIdGenerationReader().open(
        directory, state, reference, loaded));
    assertEquals(3, loaded.floorCount());
    assertEquals(19, loaded.publishedFloor(2));
    assertEquals(1, loaded.publishedFloor(17));
    assertEquals(Long.MAX_VALUE, loaded.publishedFloor(9_000_000_000L));
    assertEquals(1, loaded.publishedFloor(18));
    loaded.rewind();
    assertEquals(2, loaded.nextObjectId());
    assertEquals(19, loaded.nextExclusive());
    assertEquals(17, loaded.nextObjectId());
    assertEquals(1, loaded.nextExclusive());
    assertEquals(9_000_000_000L, loaded.nextObjectId());
    assertEquals(Long.MAX_VALUE, loaded.nextExclusive());
    assertEquals(-1, loaded.nextObjectId());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void rejectsCorruptionWithoutPublishingPartialLoad(@TempDir Path root) throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    CheckpointState state = state(8, 41);
    CheckpointLogicalRowIdGenerationWriter writer = new CheckpointLogicalRowIdGenerationWriter();
    CheckpointLogicalRowIdGenerationReader reader = new CheckpointLogicalRowIdGenerationReader();
    CheckpointLogicalRowIdManifestReference first = new CheckpointLogicalRowIdManifestReference();
    assertEquals(StatusCode.OK, writer.install(
        directory, state, new ArraySource(new long[] {3}, new long[] {11}), 0, -1, first));
    CheckpointLogicalRowIdDirectory loaded = new CheckpointLogicalRowIdDirectory();
    assertEquals(StatusCode.OK, reader.open(directory, state, first, loaded));

    CheckpointLogicalRowIdManifestReference second = new CheckpointLogicalRowIdManifestReference();
    assertEquals(StatusCode.OK, writer.install(
        directory, state, new ArraySource(
            new long[] {5, 7}, new long[] {13, 17}), 1, 0, second));
    Path file = root.resolve(CheckpointLogicalRowIdGenerationWriter.fileName(1));
    byte[] bytes = Files.readAllBytes(file);
    bytes[CheckpointLogicalRowIdFormat.HEADER_BYTES + Long.BYTES] ^= 0x40;
    Files.write(file, bytes);

    assertEquals(StatusCode.CORRUPTION, reader.open(directory, state, second, loaded));
    assertEquals(1, loaded.floorCount());
    assertEquals(11, loaded.publishedFloor(3));
    assertEquals(1, loaded.publishedFloor(5));
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void rejectsOldVersionWrongIdentityAndManifestDigest(@TempDir Path root) throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    CheckpointState state = state(9, 51);
    CheckpointLogicalRowIdManifestReference reference = new CheckpointLogicalRowIdManifestReference();
    assertEquals(StatusCode.OK, new CheckpointLogicalRowIdGenerationWriter().install(
        directory, state, new ArraySource(new long[] {1}, new long[] {2}),
        0, -1, reference));
    CheckpointLogicalRowIdGenerationReader reader = new CheckpointLogicalRowIdGenerationReader();

    CheckpointLogicalRowIdManifestReference wrongDigest = copy(reference);
    wrongDigest.set(reference.count(), reference.fileBytes(), reference.digest() + 1, 0, -1);
    assertEquals(StatusCode.CORRUPTION, reader.open(
        directory, state, wrongDigest, new CheckpointLogicalRowIdDirectory()));
    assertEquals(StatusCode.CORRUPTION, reader.open(
        directory, state(10, 51), reference, new CheckpointLogicalRowIdDirectory()));

    Path file = root.resolve(CheckpointLogicalRowIdGenerationWriter.fileName(0));
    byte[] bytes = Files.readAllBytes(file);
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 0);
    Files.write(file, bytes);
    assertEquals(StatusCode.CORRUPTION, reader.open(
        directory, state, reference, new CheckpointLogicalRowIdDirectory()));
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void writerRejectsDuplicateAndMismatchedSourceCount(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    CheckpointLogicalRowIdGenerationWriter writer = new CheckpointLogicalRowIdGenerationWriter();
    CheckpointLogicalRowIdManifestReference reference = new CheckpointLogicalRowIdManifestReference();
    assertEquals(StatusCode.CORRUPTION, writer.install(
        directory, state(11, 61),
        new ArraySource(new long[] {4, 4}, new long[] {5, 6}), 0, -1, reference));
    assertEquals(StatusCode.CORRUPTION, writer.install(
        directory, state(11, 61),
        new DeclaredCountSource(2, new long[] {4}, new long[] {5}), 0, -1, reference));
    assertEquals(StatusCode.OK, directory.close());
  }

  private static CheckpointLogicalRowIdManifestReference copy(
      CheckpointLogicalRowIdManifestReference reference) {
    CheckpointLogicalRowIdManifestReference copy =
        new CheckpointLogicalRowIdManifestReference();
    copy.set(reference.count(), reference.fileBytes(), reference.digest(),
        reference.slot(), reference.cleanupSlot());
    return copy;
  }

  private static CheckpointState state(long checkpointId, long commitSequence) {
    CheckpointState state = new CheckpointState();
    assertEquals(StatusCode.OK, state.set(
        DATABASE, WalGeneration.of(3), checkpointId, commitSequence, 67, 1, 0));
    return state;
  }

  private static NioDurableDirectory openDirectory(Path root) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root, new FatalStateFence(), new NioIoCounters(), 8, result));
    return result.directory();
  }

  private static class ArraySource implements CheckpointLogicalRowIdSource {
    private final long[] objectIds;
    private final long[] floors;
    private int cursor;

    private ArraySource(long[] objectIds, long[] floors) {
      this.objectIds = objectIds;
      this.floors = floors;
    }

    @Override
    public int floorCount() { return objectIds.length; }

    @Override
    public void rewind() { cursor = 0; }

    @Override
    public long nextObjectId() { return cursor < objectIds.length ? objectIds[cursor++] : -1; }

    @Override
    public long nextExclusive() { return cursor == 0 ? -1 : floors[cursor - 1]; }
  }

  private static final class DeclaredCountSource extends ArraySource {
    private final int declaredCount;

    private DeclaredCountSource(int count, long[] objectIds, long[] floors) {
      super(objectIds, floors);
      declaredCount = count;
    }

    @Override
    public int floorCount() { return declaredCount; }
  }

}
