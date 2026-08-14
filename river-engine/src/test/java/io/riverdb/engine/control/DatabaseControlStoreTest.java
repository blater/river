package io.riverdb.engine.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.control.ControlFile;
import io.riverdb.format.control.ControlFileCodec;
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
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DatabaseControlStoreTest {
  private static final ControlFile CONTROL = new ControlFile(
      DatabaseIncarnation.of(0x1020304050607080L, 0x1122334455667788L),
      WalGeneration.of(1));

  @Test
  void createsClosesAndReopensDatabaseControl(@TempDir Path root) {
    DatabaseControlResult result = new DatabaseControlResult();

    NioDurableDirectory first = openDirectory(root);
    assertEquals(StatusCode.OK, DatabaseControlStore.create(first, CONTROL, result));
    assertEquals(CONTROL, result.controlFile());
    assertEquals(StatusCode.OK, first.close());

    NioDurableDirectory reopened = openDirectory(root);
    assertEquals(StatusCode.OK, DatabaseControlStore.open(reopened, result));
    assertEquals(CONTROL, result.controlFile());
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

  @Test
  void shortWriteAndFileForceFailureNeverPublishControl() {
    FaultFixture shortWrite = new FaultFixture();
    assertEquals(
        StatusCode.OK,
        shortWrite.arm(
            DirectoryOperation.FILE_WRITE,
            FaultBoundary.BEFORE,
            FaultAction.SHORT_WRITE,
            ControlFileCodec.RECORD_BYTES - 1L));
    assertCreateFailsAndLeavesNoDurableControl(shortWrite, StatusCode.IO_FAILURE);

    FaultFixture forceFailure = new FaultFixture();
    assertEquals(
        StatusCode.OK,
        forceFailure.arm(
            DirectoryOperation.FILE_FORCE,
            FaultBoundary.BEFORE,
            FaultAction.FORCE_FAILURE,
            0));
    assertCreateFailsAndLeavesNoDurableControl(forceFailure, StatusCode.IO_FAILURE);
  }

  @Test
  void crashBeforeOrAfterRenameCannotPublishControl() {
    for (FaultBoundary boundary : FaultBoundary.values()) {
      FaultFixture fixture = new FaultFixture();
      assertEquals(
          StatusCode.OK,
          fixture.arm(
              DirectoryOperation.RENAME,
              boundary,
              FaultAction.CRASH,
              0));
      DatabaseControlResult result = seededResult();

      assertEquals(
          StatusCode.IO_FAILURE,
          DatabaseControlStore.create(fixture.directory, CONTROL, result));
      assertNull(result.controlFile());
      assertEquals(StatusCode.OK, fixture.directory.restart());
      assertNoDurableControl(fixture);
    }
  }

  @Test
  void directoryForceCrashBoundaryDeterminesDurableCompletionImage() {
    for (FaultBoundary boundary : FaultBoundary.values()) {
      FaultFixture fixture = new FaultFixture();
      assertEquals(
          StatusCode.OK,
          fixture.arm(
              DirectoryOperation.DIRECTORY_FORCE,
              boundary,
              FaultAction.CRASH,
              0));
      DatabaseControlResult result = seededResult();

      assertEquals(
          StatusCode.IO_FAILURE,
          DatabaseControlStore.create(fixture.directory, CONTROL, result));
      assertNull(result.controlFile());
      assertEquals(StatusCode.OK, fixture.directory.restart());
      if (boundary == FaultBoundary.BEFORE) {
        assertNoDurableControl(fixture);
      } else {
        assertEquals(
            StatusCode.OK,
            DatabaseControlStore.open(fixture.directory, result));
        assertEquals(CONTROL, result.controlFile());
        assertEquals(false, fixture.exists(DatabaseControlStore.TEMPORARY_FILE_NAME));
      }
    }
  }

  @Test
  void existingDurableTemporaryFileIsAConflictAndIsNotReplaced() {
    FaultFixture fixture = new FaultFixture();
    fixture.createDurable(DatabaseControlStore.TEMPORARY_FILE_NAME, new byte[] {7, 8, 9});
    DatabaseControlResult result = seededResult();

    assertEquals(
        StatusCode.CONFLICT,
        DatabaseControlStore.create(fixture.directory, CONTROL, result));
    assertNull(result.controlFile());
    fixture.crashRestart();
    assertTrue(fixture.exists(DatabaseControlStore.TEMPORARY_FILE_NAME));
    assertEquals(false, fixture.exists(DatabaseControlStore.CONTROL_FILE_NAME));
  }

  private static void assertCreateFailsAndLeavesNoDurableControl(
      FaultFixture fixture,
      StatusCode expected) {
    DatabaseControlResult result = seededResult();
    assertEquals(expected, DatabaseControlStore.create(fixture.directory, CONTROL, result));
    assertNull(result.controlFile());
    fixture.crashRestart();
    assertNoDurableControl(fixture);
  }

  private static void assertNoDurableControl(FaultFixture fixture) {
    DatabaseControlResult result = seededResult();
    assertEquals(StatusCode.CONFLICT, DatabaseControlStore.open(fixture.directory, result));
    assertNull(result.controlFile());
    assertEquals(false, fixture.exists(DatabaseControlStore.CONTROL_FILE_NAME));
    assertEquals(false, fixture.exists(DatabaseControlStore.TEMPORARY_FILE_NAME));
  }

  private static DatabaseControlResult seededResult() {
    DatabaseControlResult result = new DatabaseControlResult();
    result.set(new ControlFile(DatabaseIncarnation.of(3, 5), WalGeneration.of(7)));
    return result;
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

  private static final class FaultFixture {
    private final CrashPointController controller = new CrashPointController(1);
    private final DirectoryFaultPoints points = new DirectoryFaultPoints();
    private final FaultingDurableDirectory directory;
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
                  "database-control."
                      + operationName.name().toLowerCase()
                      + "."
                      + boundary.name().toLowerCase(),
                  slot));
          points.set(operationName, boundary, slot.value());
        }
      }
      directory = new FaultingDurableDirectory(
          16,
          ControlFileCodec.RECORD_BYTES,
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
