package io.riverdb.engine.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.nio.file.Path;
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
    assertEquals(StatusCode.OK, state.setDeleted(65));
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
        state.set(DATABASE, WalGeneration.of(1), 1, 1, 1, 1, 2049));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, state.setDeleted(1));
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
