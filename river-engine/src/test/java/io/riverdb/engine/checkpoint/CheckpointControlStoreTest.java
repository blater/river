package io.riverdb.engine.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.testsupport.fault.CrashPointController;
import io.riverdb.engine.testsupport.fault.DirectoryFaultPoints;
import io.riverdb.engine.testsupport.fault.DirectoryOperation;
import io.riverdb.engine.testsupport.fault.FaultAction;
import io.riverdb.engine.testsupport.fault.FaultBoundary;
import io.riverdb.engine.testsupport.fault.FaultOperation;
import io.riverdb.engine.testsupport.fault.FaultPointRegistry;
import io.riverdb.engine.testsupport.fault.FaultPointSlot;
import io.riverdb.engine.testsupport.fault.FaultingDurableDirectory;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
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
  private static final String TEMPORARY_FILE_NAME = "river.checkpoint.tmp";

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

  @Test
  void shortWriteAndFileForceFailurePreserveOldCheckpoint() {
    FaultFixture shortWrite = fixtureWithOldCheckpoint();
    assertEquals(
        StatusCode.OK,
        shortWrite.arm(
            DirectoryOperation.FILE_WRITE,
            FaultBoundary.BEFORE,
            FaultAction.SHORT_WRITE,
            CheckpointControlStore.BYTES - 1L));
    assertInstallFailurePreservesOld(shortWrite, StatusCode.IO_FAILURE);

    FaultFixture forceFailure = fixtureWithOldCheckpoint();
    assertEquals(
        StatusCode.OK,
        forceFailure.arm(
            DirectoryOperation.FILE_FORCE,
            FaultBoundary.BEFORE,
            FaultAction.FORCE_FAILURE,
            0));
    assertInstallFailurePreservesOld(forceFailure, StatusCode.IO_FAILURE);
  }

  @Test
  void crashBeforeOrAfterReplacePreservesOldCheckpoint() {
    for (FaultBoundary boundary : FaultBoundary.values()) {
      FaultFixture fixture = fixtureWithOldCheckpoint();
      assertEquals(
          StatusCode.OK,
          fixture.arm(
              DirectoryOperation.RENAME,
              boundary,
              FaultAction.CRASH,
              0));

      assertEquals(
          StatusCode.IO_FAILURE,
          fixture.store.install(fixture.directory, newState()));
      assertEquals(StatusCode.OK, fixture.directory.restart());
      fixture.assertOldCheckpoint();
      assertEquals(false, fixture.exists(TEMPORARY_FILE_NAME));
    }
  }

  @Test
  void finalDirectoryForceCrashBoundaryDeterminesCheckpointAuthority() {
    for (FaultBoundary boundary : FaultBoundary.values()) {
      FaultFixture fixture = fixtureWithOldCheckpoint();
      assertEquals(
          StatusCode.OK,
          fixture.arm(
              DirectoryOperation.DIRECTORY_FORCE,
              boundary,
              FaultAction.CRASH,
              0));

      assertEquals(
          StatusCode.IO_FAILURE,
          fixture.store.install(fixture.directory, newState()));
      assertEquals(StatusCode.OK, fixture.directory.restart());
      if (boundary == FaultBoundary.BEFORE) {
        fixture.assertOldCheckpoint();
      } else {
        fixture.assertNewCheckpoint();
      }
      assertEquals(false, fixture.exists(TEMPORARY_FILE_NAME));
    }
  }

  @Test
  void durableStaleTemporaryIsRemovedBeforeSuccessfulReplacement() {
    FaultFixture fixture = fixtureWithOldCheckpoint();
    fixture.createDurable(TEMPORARY_FILE_NAME, new byte[] {7, 8, 9});

    assertEquals(
        StatusCode.OK,
        fixture.store.install(fixture.directory, newState()));
    fixture.crashRestart();
    fixture.assertNewCheckpoint();
    assertEquals(false, fixture.exists(TEMPORARY_FILE_NAME));
  }

  @Test
  void crashBeforeStaleTemporaryRemovalPreservesOldAuthorityAndTemporary() {
    FaultFixture fixture = fixtureWithOldCheckpoint();
    fixture.createDurable(TEMPORARY_FILE_NAME, new byte[] {7, 8, 9});
    assertEquals(
        StatusCode.OK,
        fixture.arm(
            DirectoryOperation.REMOVE,
            FaultBoundary.BEFORE,
            FaultAction.CRASH,
            0));

    assertEquals(
        StatusCode.IO_FAILURE,
        fixture.store.install(fixture.directory, newState()));
    assertEquals(StatusCode.OK, fixture.directory.restart());
    fixture.assertOldCheckpoint();
    assertEquals(true, fixture.exists(TEMPORARY_FILE_NAME));
  }

  @Test
  void staleTemporaryCleanupForceCrashBoundaryControlsRemovalImage() {
    for (FaultBoundary boundary : FaultBoundary.values()) {
      FaultFixture fixture = fixtureWithOldCheckpoint();
      fixture.createDurable(TEMPORARY_FILE_NAME, new byte[] {7, 8, 9});
      assertEquals(
          StatusCode.OK,
          fixture.arm(
              DirectoryOperation.DIRECTORY_FORCE,
              boundary,
              FaultAction.CRASH,
              0));

      assertEquals(
          StatusCode.IO_FAILURE,
          fixture.store.install(fixture.directory, newState()));
      assertEquals(StatusCode.OK, fixture.directory.restart());
      fixture.assertOldCheckpoint();
      assertEquals(
          boundary == FaultBoundary.BEFORE,
          fixture.exists(TEMPORARY_FILE_NAME));
    }
  }

  private static void assertInstallFailurePreservesOld(
      FaultFixture fixture,
      StatusCode expected) {
    assertEquals(expected, fixture.store.install(fixture.directory, newState()));
    fixture.crashRestart();
    fixture.assertOldCheckpoint();
    assertEquals(false, fixture.exists(TEMPORARY_FILE_NAME));
  }

  private static FaultFixture fixtureWithOldCheckpoint() {
    FaultFixture fixture = new FaultFixture();
    assertEquals(
        StatusCode.OK,
        fixture.store.install(fixture.directory, oldState()));
    return fixture;
  }

  private static CheckpointState oldState() {
    return state(WalGeneration.of(2), 1, 19, 23, 5, 2);
  }

  private static CheckpointState newState() {
    return state(WalGeneration.of(3), 2, 29, 31, 6, 1);
  }

  private static CheckpointState state(
      WalGeneration generation,
      long checkpointId,
      long commitSequence,
      long maximumTransactionId,
      int pageCount,
      int rowCount) {
    CheckpointState state = new CheckpointState();
    assertEquals(
        StatusCode.OK,
        state.set(
            DATABASE,
            generation,
            checkpointId,
            commitSequence,
            maximumTransactionId,
            pageCount,
            rowCount));
    return state;
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

  private static final class FaultFixture {
    private final CrashPointController controller = new CrashPointController(1);
    private final DirectoryFaultPoints points = new DirectoryFaultPoints();
    private final FaultingDurableDirectory directory;
    private final CheckpointControlStore store = new CheckpointControlStore();
    private final DirectoryOperationResult operation = new DirectoryOperationResult();
    private final IoResult io = new IoResult();

    private FaultFixture() {
      FaultPointRegistry registry = new FaultPointRegistry(
          DirectoryOperation.values().length * FaultBoundary.values().length);
      for (DirectoryOperation operationName : DirectoryOperation.values()) {
        for (FaultBoundary boundary : FaultBoundary.values()) {
          FaultPointSlot slot = new FaultPointSlot();
          assertEquals(
              StatusCode.OK,
              registry.register(
                  "checkpoint-control."
                      + operationName.name().toLowerCase()
                      + "."
                      + boundary.name().toLowerCase(),
                  slot));
          points.set(operationName, boundary, slot.value());
        }
      }
      directory = new FaultingDurableDirectory(
          16,
          CheckpointControlStore.BYTES,
          8,
          controller,
          points);
    }

    private StatusCode arm(
        DirectoryOperation operationName,
        FaultBoundary boundary,
        FaultAction action,
        long argument) {
      return controller.addRule(
          points.point(operationName, boundary),
          faultOperation(operationName),
          boundary,
          1,
          1,
          action,
          argument);
    }

    private void createDurable(String name, byte[] content) {
      assertEquals(StatusCode.OK, directory.createFile(name, operation));
      DurableFile file = operation.file();
      assertEquals(StatusCode.OK, file.write(0, ByteBuffer.wrap(content), io));
      assertEquals(content.length, io.bytesTransferred());
      assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT_AND_METADATA));
      assertEquals(StatusCode.OK, file.close());
      assertEquals(StatusCode.OK, directory.force(operation));
    }

    private void crashRestart() {
      assertEquals(StatusCode.OK, directory.crash());
      assertEquals(StatusCode.OK, directory.restart());
    }

    private void assertOldCheckpoint() {
      assertCheckpoint(WalGeneration.of(2), 1, 19, 23, 5, 2);
    }

    private void assertNewCheckpoint() {
      assertCheckpoint(WalGeneration.of(3), 2, 29, 31, 6, 1);
    }

    private void assertCheckpoint(
        WalGeneration generation,
        long checkpointId,
        long commitSequence,
        long maximumTransactionId,
        int pageCount,
        int rowCount) {
      CheckpointState decoded = state(WalGeneration.of(9), 9, 99, 101, 9, 0);
      assertEquals(StatusCode.OK, store.read(directory, decoded));
      assertEquals(DATABASE, decoded.database());
      assertEquals(generation, decoded.walGeneration());
      assertEquals(checkpointId, decoded.checkpointId());
      assertEquals(commitSequence, decoded.commitSequence());
      assertEquals(maximumTransactionId, decoded.maximumTransactionId());
      assertEquals(pageCount, decoded.pageCount());
      assertEquals(rowCount, decoded.rowCount());
    }

    private boolean exists(String name) {
      DirectoryListResult entries = new DirectoryListResult(16);
      assertEquals(StatusCode.OK, directory.list(entries));
      for (int index = 0; index < entries.size(); index++) {
        if (name.equals(entries.name(index))) {
          return true;
        }
      }
      return false;
    }

    private static FaultOperation faultOperation(DirectoryOperation operation) {
      return switch (operation) {
        case CREATE_DIRECTORY -> FaultOperation.DIRECTORY_CREATE;
        case CREATE_FILE -> FaultOperation.FILE_CREATE;
        case LIST -> FaultOperation.DIRECTORY_LIST;
        case RENAME -> FaultOperation.FILE_RENAME;
        case REMOVE -> FaultOperation.FILE_REMOVE;
        case TRUNCATE -> FaultOperation.NAMED_TRUNCATE;
        case FILE_READ -> FaultOperation.DIRECTORY_FILE_READ;
        case FILE_WRITE -> FaultOperation.DIRECTORY_FILE_WRITE;
        case FILE_FORCE -> FaultOperation.DIRECTORY_FILE_FORCE;
        case DIRECTORY_FORCE -> FaultOperation.DIRECTORY_FORCE;
        case REOPEN -> FaultOperation.DIRECTORY_REOPEN;
      };
    }
  }
}
