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
import io.riverdb.platform.file.FileIoMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

final class CheckpointControlStoreTestFixture {
  static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(811, 821);
  static final String TEMPORARY_FILE_NAME = "river.checkpoint.tmp";

  static void assertInstallFailurePreservesOld(
      FaultFixture fixture,
      StatusCode expected) {
    assertEquals(expected, fixture.store.install(fixture.directory, newState()));
    fixture.crashRestart();
    fixture.assertOldCheckpoint();
    assertEquals(false, fixture.exists(TEMPORARY_FILE_NAME));
  }

  static FaultFixture fixtureWithOldCheckpoint() {
    FaultFixture fixture = new FaultFixture();
    assertEquals(
        StatusCode.OK,
        fixture.store.install(fixture.directory, oldState()));
    return fixture;
  }

  static FaultFixture fixtureWithOldSparseCheckpoint() {
    FaultFixture fixture = new FaultFixture();
    assertEquals(StatusCode.OK, fixture.store.install(fixture.directory, sparseState(1)));
    return fixture;
  }

  static CheckpointState oldState() {
    return state(WalGeneration.of(2), 1, 19, 23, 5, 2);
  }

  static CheckpointState sparseState(long checkpointId) {
    CheckpointState state = new CheckpointState();
    long rows = CheckpointState.MAXIMUM_ROWS + 1L;
    long commitSequence = checkpointId * 10 + 1;
    assertEquals(
        StatusCode.OK,
        state.setLarge(
            DATABASE,
            WalGeneration.of(checkpointId + 1),
            checkpointId,
            commitSequence,
            checkpointId * 10 + 3,
            2,
            rows));
    assertEquals(
        StatusCode.OK,
        state.setRowVersion(rows, commitSequence - 1, rows - 1, true));
    return state;
  }

  static CheckpointState largeState(long checkpointId, long rows) {
    return largeState(DATABASE, checkpointId, rows);
  }

  static CheckpointState largeState(
      DatabaseIncarnation database, long checkpointId, long rows) {
    CheckpointState state = new CheckpointState();
    assertEquals(
        StatusCode.OK,
        state.setLarge(
            database,
            WalGeneration.of(1),
            checkpointId,
            1,
            1,
            1,
            rows));
    return state;
  }

  static void assertVersion(
      CheckpointState state,
      CheckpointVersionResult result,
      long rowId,
      long commitSequence,
      long previousRowId,
      boolean deleted) {
    assertEquals(StatusCode.OK, state.readVersion(rowId, result));
    assertEquals(commitSequence, result.commitSequence());
    assertEquals(previousRowId, result.previousRowId());
    assertEquals(deleted, result.deleted());
  }

  static CheckpointState newState() {
    return state(WalGeneration.of(3), 2, 29, 31, 6, 1);
  }

  static CheckpointState state(
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

  static NioDurableDirectory openDirectory(Path root) {
    return openDirectory(root, new NioIoCounters());
  }

  static NioDurableDirectory openDirectory(Path root, NioIoCounters counters) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            counters,
            8,
            result));
    return result.directory();
  }

  static long fileSize(Path file) {
    try {
      return Files.size(file);
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }

  static final class FaultFixture {
    final CrashPointController controller = new CrashPointController(8);
    final DirectoryFaultPoints points = new DirectoryFaultPoints();
    final FaultingDurableDirectory directory;
    final CheckpointControlStore store = new CheckpointControlStore();
    final DirectoryOperationResult operation = new DirectoryOperationResult();
    final IoResult io = new IoResult();

    FaultFixture() {
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
          CheckpointVersionFormat.SEGMENT_BYTES + 4_096,
          8,
          controller,
          points);
    }

    StatusCode arm(
        DirectoryOperation operationName,
        FaultBoundary boundary,
        FaultAction action,
        long argument) {
      return arm(operationName, boundary, 1, 1, action, argument);
    }

    StatusCode arm(
        DirectoryOperation operationName,
        FaultBoundary boundary,
        long firstOccurrence,
        long repeatCount,
        FaultAction action,
        long argument) {
      return controller.addRule(
          points.point(operationName, boundary),
          faultOperation(operationName),
          boundary,
          firstOccurrence,
          repeatCount,
          action,
          argument);
    }

    void createDurable(String name, byte[] content) {
      assertEquals(StatusCode.OK, directory.createFile(name, FileIoMode.POSITIONAL, operation));
      DurableFile file = operation.file();
      assertEquals(StatusCode.OK, file.write(0, ByteBuffer.wrap(content), io));
      assertEquals(content.length, io.bytesTransferred());
      assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT_AND_METADATA));
      assertEquals(StatusCode.OK, file.close());
      assertEquals(StatusCode.OK, directory.force(operation));
    }

    void crashRestart() {
      assertEquals(StatusCode.OK, directory.crash());
      assertEquals(StatusCode.OK, directory.restart());
    }

    void assertOldCheckpoint() {
      assertCheckpoint(WalGeneration.of(2), 1, 19, 23, 5, 2);
    }

    void assertNewCheckpoint() {
      assertCheckpoint(WalGeneration.of(3), 2, 29, 31, 6, 1);
    }

    void assertSparseCheckpoint(long checkpointId) {
      CheckpointState decoded = new CheckpointState();
      long rows = CheckpointState.MAXIMUM_ROWS + 1L;
      long commitSequence = checkpointId * 10 + 1;
      assertEquals(StatusCode.OK, store.read(directory, decoded));
      assertEquals(DATABASE, decoded.database());
      assertEquals(WalGeneration.of(checkpointId + 1), decoded.walGeneration());
      assertEquals(checkpointId, decoded.checkpointId());
      assertEquals(commitSequence, decoded.commitSequence());
      assertEquals(rows, decoded.rowCount());
      assertVersion(
          decoded, new CheckpointVersionResult(), rows,
          commitSequence - 1, rows - 1, true);
      decoded.close();
    }

    void assertCheckpoint(
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

    boolean exists(String name) {
      DirectoryListResult entries = new DirectoryListResult(16);
      assertEquals(StatusCode.OK, directory.list(entries));
      for (int index = 0; index < entries.size(); index++) {
        if (name.equals(entries.name(index))) {
          return true;
        }
      }
      return false;
    }

    static FaultOperation faultOperation(DirectoryOperation operation) {
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
