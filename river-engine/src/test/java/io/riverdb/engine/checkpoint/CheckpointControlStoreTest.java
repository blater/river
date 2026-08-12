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
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckpointControlStoreTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(811, 821);

  @Test
  void atomicallyReplacesAndRoundTripsFixedCheckpointState(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    CheckpointControlStore control = new CheckpointControlStore();
    CheckpointState state = new CheckpointState();
    assertEquals(
        StatusCode.OK,
        state.set(DATABASE, WalGeneration.of(2), 1, 19, 23, 5, 65));
    assertEquals(StatusCode.OK, state.setDeleted(1));
    assertEquals(StatusCode.OK, state.setDeleted(64));
    assertEquals(StatusCode.OK, state.setRowVersion(65, 17, 64, true));
    assertEquals(StatusCode.OK, control.install(directory, state));

    CheckpointState decoded = new CheckpointState();
    assertEquals(StatusCode.OK, control.read(directory, decoded));
    assertEquals(DATABASE, decoded.database());
    assertEquals(WalGeneration.of(2), decoded.walGeneration());
    assertEquals(1, decoded.checkpointId());
    assertEquals(19, decoded.commitSequence());
    assertEquals(23, decoded.maximumTransactionId());
    assertEquals(5, decoded.pageCount());
    assertEquals(65, decoded.rowCount());
    assertEquals(true, decoded.isDeleted(1));
    assertEquals(true, decoded.isDeleted(64));
    assertEquals(true, decoded.isDeleted(65));
    assertEquals(17, decoded.rowCommitSequence(65));
    assertEquals(64, decoded.previousRowId(65));

    state.reset();
    assertEquals(
        StatusCode.OK,
        state.set(DATABASE, WalGeneration.of(3), 2, 29, 31, 6, 1));
    assertEquals(StatusCode.OK, control.install(directory, state));
    assertEquals(StatusCode.OK, control.read(directory, decoded));
    assertEquals(WalGeneration.of(3), decoded.walGeneration());
    assertEquals(2, decoded.checkpointId());
    assertEquals(false, decoded.isDeleted(1));
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void rejectsInvalidStateWithoutThrowing() {
    CheckpointState state = new CheckpointState();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        state.set(
            DATABASE,
            WalGeneration.of(1),
            1,
            1,
            1,
            1,
            CheckpointState.MAXIMUM_ROWS + 1));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, state.setDeleted(1));
  }

  @Test
  void readsVersionOneVacuumedCheckpoint(@TempDir Path root) throws Exception {
    ByteBuffer legacy = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
    legacy.putLong(0, 0x5249564552434b50L);
    legacy.putInt(8, 1);
    legacy.putInt(12, 512);
    legacy.putLong(16, DATABASE.high());
    legacy.putLong(24, DATABASE.low());
    legacy.putLong(32, 2);
    legacy.putLong(40, 1);
    legacy.putLong(48, 19);
    legacy.putLong(56, 23);
    legacy.putInt(64, 5);
    legacy.putInt(68, 2);
    legacy.putLong(72, 2);
    CRC32C checksum = new CRC32C();
    checksum.update(legacy.array(), 0, 512);
    int value = (int) checksum.getValue();
    legacy.putInt(504, value);
    legacy.putInt(508, ~value);
    Files.write(root.resolve(CheckpointControlStore.FILE_NAME), legacy.array());

    NioDurableDirectory directory = openDirectory(root);
    CheckpointState decoded = new CheckpointState();
    assertEquals(StatusCode.OK, new CheckpointControlStore().read(directory, decoded));
    assertEquals(2, decoded.rowCount());
    assertEquals(19, decoded.rowCommitSequence(1));
    assertEquals(0, decoded.previousRowId(1));
    assertEquals(false, decoded.isDeleted(1));
    assertEquals(true, decoded.isDeleted(2));
    assertEquals(StatusCode.OK, directory.close());
  }

  private static NioDurableDirectory openDirectory(Path root) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            8,
            result));
    return result.directory();
  }
}
