package io.riverdb.testkit.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultBoundary;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class FaultingDurableDirectoryContractTest {
  @Test
  void everyModeledBoundaryCanCrashOrRestartWithoutFalseDurability() {
    for (FaultAction action : new FaultAction[] {FaultAction.CRASH, FaultAction.RESTART}) {
      for (FaultBoundary boundary : FaultBoundary.values()) {
        assertCreateBoundary(action, boundary, DirectoryOperation.CREATE_DIRECTORY);
        assertCreateBoundary(action, boundary, DirectoryOperation.CREATE_FILE);
        assertRenameBoundary(action, boundary);
        assertRemoveBoundary(action, boundary);
        assertTruncateBoundary(action, boundary);
        assertDirectoryForceBoundary(action, boundary);
        assertFileForceBoundary(action, boundary);
        assertObservationBoundary(action, boundary, DirectoryOperation.LIST);
        assertObservationBoundary(action, boundary, DirectoryOperation.REOPEN);
        assertObservationBoundary(action, boundary, DirectoryOperation.FILE_READ);
        assertWriteBoundary(action, boundary);
      }
    }
  }

  @Test
  void shortPartialAndDiskFullWritesReportExactPrefixesAndCanResume() {
    FaultAction[] actions = {
      FaultAction.SHORT_WRITE,
      FaultAction.PARTIAL_WRITE,
      FaultAction.DISK_FULL
    };
    StatusCode[] expected = {
      StatusCode.OK,
      StatusCode.IO_FAILURE,
      StatusCode.RESOURCE_EXHAUSTED
    };
    for (int index = 0; index < actions.length; index++) {
      Fixture fixture = new Fixture(1, 32);
      assertEquals(
          StatusCode.OK,
          fixture.provider.script(
              DirectoryOperation.FILE_WRITE,
              FaultBoundary.BEFORE,
              actions[index],
              2));
      assertEquals(StatusCode.OK, fixture.directory.createFile("data", fixture.result));
      DurableFile file = fixture.result.file();
      ByteBuffer source = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
      fixture.io.reset();
      assertEquals(expected[index], file.write(0, source, fixture.io));
      assertEquals(2, fixture.io.bytesTransferred());
      assertEquals(2, source.position());
      fixture.io.reset();
      assertEquals(StatusCode.OK, file.write(2, source, fixture.io));
      assertEquals(2, fixture.io.bytesTransferred());
      assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT_AND_METADATA));
      assertEquals(StatusCode.OK, file.close());
      assertEquals(StatusCode.OK, fixture.directory.force(fixture.result));
      fixture.restartAfterCrash();
      assertArrayEquals(new byte[] {1, 2, 3, 4}, fixture.read("data"));
    }
  }

  @Test
  void forceFailuresNeverPromoteContentOrNamespace() {
    Fixture content = new Fixture(1, 24);
    assertEquals(StatusCode.OK, content.directory.createFile("data", content.result));
    DurableFile file = content.result.file();
    assertEquals(StatusCode.OK, file.write(0, ByteBuffer.wrap(new byte[] {9}), content.io));
    assertEquals(
        StatusCode.OK,
        content.provider.script(
            DirectoryOperation.FILE_FORCE,
            FaultBoundary.BEFORE,
            FaultAction.FORCE_FAILURE,
            0));
    assertEquals(StatusCode.IO_FAILURE, file.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, content.directory.force(content.result));
    content.restartAfterCrash();
    assertArrayEquals(new byte[0], content.read("data"));

    for (FaultAction action : new FaultAction[] {
      FaultAction.FORCE_FAILURE,
      FaultAction.DISK_FULL
    }) {
      Fixture namespace = new Fixture(1, 16);
      assertEquals(StatusCode.OK, namespace.directory.createDirectory("wal", namespace.result));
      assertEquals(
          StatusCode.OK,
          namespace.provider.script(
              DirectoryOperation.DIRECTORY_FORCE,
              FaultBoundary.BEFORE,
              action,
              0));
      StatusCode expected = action == FaultAction.DISK_FULL
          ? StatusCode.RESOURCE_EXHAUSTED
          : StatusCode.IO_FAILURE;
      assertEquals(expected, namespace.directory.force(namespace.result));
      namespace.restartAfterCrash();
      assertFalse(namespace.exists("wal"));
    }
  }

  @Test
  void contentOnlyForceCannotPublishTruncatedLengthMetadata() {
    Fixture fixture = new Fixture(0, 24);
    fixture.createDurable("data", new byte[] {1, 2, 3, 4});
    assertEquals(StatusCode.OK, fixture.directory.truncate("data", 2, fixture.result));
    DurableFile file = fixture.result.file();
    assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT));
    assertEquals(StatusCode.OK, file.close());
    fixture.restartAfterCrash();
    assertArrayEquals(new byte[] {1, 2, 3, 4}, fixture.read("data"));
  }

  @Test
  void listCarrierIsBoundedReusableAndBorrowsStableNames() {
    Fixture fixture = new Fixture(0, 4);
    String stableName = new String("control");
    assertEquals(StatusCode.OK, fixture.directory.createFile(stableName, fixture.result));
    assertEquals(StatusCode.OK, fixture.result.file().close());
    assertEquals(StatusCode.OK, fixture.directory.createDirectory("wal", fixture.result));

    DirectoryListResult tooSmall = new DirectoryListResult(1);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, fixture.directory.list(tooSmall));
    assertEquals(1, tooSmall.size());
    assertFalse(tooSmall.complete());

    DirectoryListResult reusable = new DirectoryListResult(2);
    assertEquals(StatusCode.OK, fixture.directory.list(reusable));
    assertTrue(reusable.complete());
    assertEquals(2, reusable.size());
    assertSame(stableName, reusable.name(0));
    assertEquals(DirectoryEntryType.FILE, reusable.type(0));
    long firstGeneration = reusable.providerGeneration();
    assertEquals(StatusCode.OK, fixture.directory.force(fixture.result));
    fixture.restartAfterCrash();
    assertNotEquals(firstGeneration, fixture.provider.generation());
    assertEquals(StatusCode.OK, fixture.directory.list(reusable));
    assertEquals(fixture.provider.generation(), reusable.providerGeneration());
    assertSame(stableName, reusable.name(0));
  }

  @Test
  void invalidExternalNamesAndUnsupportedFaultsFailBeforeMutation() {
    Fixture fixture = new Fixture(1, 16);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        fixture.directory.createDirectory("../wal", fixture.result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        fixture.directory.createFile("a/b", fixture.result));
    assertEquals(StatusCode.OK, fixture.directory.createFile("data", fixture.result));
    assertEquals(StatusCode.OK, fixture.result.file().close());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        fixture.directory.rename("data", "x\\y", fixture.result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        fixture.directory.remove(".", fixture.result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        fixture.directory.truncate("data", -1, fixture.result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        fixture.provider.script(
            DirectoryOperation.LIST,
            FaultBoundary.BEFORE,
            FaultAction.PARTIAL_WRITE,
            1));
    assertTrue(fixture.exists("data"));
    assertFalse(fixture.exists("../wal"));
  }

  @Test
  void delayedSynchronousMutationIsEitherNotStartedOrCompleteOnReturn() {
    Fixture before = new Fixture(1, 8);
    assertEquals(
        StatusCode.OK,
        before.provider.script(
            DirectoryOperation.CREATE_DIRECTORY,
            FaultBoundary.BEFORE,
            FaultAction.DELAY,
            0));
    assertEquals(StatusCode.RETRY, before.directory.createDirectory("wal", before.result));
    assertFalse(before.exists("wal"));
    assertEquals(StatusCode.OK, before.directory.createDirectory("wal", before.result));
    assertTrue(before.exists("wal"));

    Fixture after = new Fixture(1, 8);
    assertEquals(
        StatusCode.OK,
        after.provider.script(
            DirectoryOperation.CREATE_DIRECTORY,
            FaultBoundary.AFTER,
            FaultAction.DELAY,
            0));
    assertEquals(StatusCode.OK, after.directory.createDirectory("wal", after.result));
    assertTrue(after.exists("wal"));
    assertEquals(StatusCode.CONFLICT, after.directory.createDirectory("wal", after.result));
  }

  @Test
  void traceSaturationNeverChangesDirectoryOutcome() {
    Fixture fixture = new Fixture(0, 1);
    assertEquals(StatusCode.OK, fixture.directory.createDirectory("a", fixture.result));
    assertEquals(StatusCode.OK, fixture.directory.createDirectory("b", fixture.result));
    assertEquals(1, fixture.provider.traceSize());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, fixture.provider.traceStatus());
    assertTrue(fixture.exists("a"));
    assertTrue(fixture.exists("b"));
  }

  private static void assertCreateBoundary(
      FaultAction action,
      FaultBoundary boundary,
      DirectoryOperation operation) {
    Fixture fixture = new Fixture(1, 16);
    assertEquals(StatusCode.OK, fixture.provider.script(operation, boundary, action, 0));
    StatusCode status = operation == DirectoryOperation.CREATE_DIRECTORY
        ? fixture.directory.createDirectory("entry", fixture.result)
        : fixture.directory.createFile("entry", fixture.result);
    assertEquals(expected(action), status, operation + " " + action + " " + boundary);
    assertEquals(
        io.riverdb.platform.file.DirectoryDurability.UNKNOWN,
        fixture.result.durability());
    fixture.ensureRunning(action);
    assertFalse(fixture.exists("entry"));
  }

  private static void assertRenameBoundary(FaultAction action, FaultBoundary boundary) {
    Fixture fixture = new Fixture(1, 24);
    fixture.createDurable("old", new byte[] {1});
    assertEquals(
        StatusCode.OK,
        fixture.provider.script(DirectoryOperation.RENAME, boundary, action, 0));
    assertEquals(expected(action), fixture.directory.rename("old", "new", fixture.result));
    fixture.ensureRunning(action);
    assertTrue(fixture.exists("old"));
    assertFalse(fixture.exists("new"));
  }

  private static void assertRemoveBoundary(FaultAction action, FaultBoundary boundary) {
    Fixture fixture = new Fixture(1, 24);
    fixture.createDurable("old", new byte[] {1});
    assertEquals(
        StatusCode.OK,
        fixture.provider.script(DirectoryOperation.REMOVE, boundary, action, 0));
    assertEquals(expected(action), fixture.directory.remove("old", fixture.result));
    fixture.ensureRunning(action);
    assertTrue(fixture.exists("old"));
  }

  private static void assertTruncateBoundary(FaultAction action, FaultBoundary boundary) {
    Fixture fixture = new Fixture(1, 32);
    fixture.createDurable("data", new byte[] {1, 2, 3, 4});
    assertEquals(
        StatusCode.OK,
        fixture.provider.script(DirectoryOperation.TRUNCATE, boundary, action, 0));
    assertEquals(expected(action), fixture.directory.truncate("data", 2, fixture.result));
    fixture.ensureRunning(action);
    assertEquals(4, fixture.size("data"));
  }

  private static void assertDirectoryForceBoundary(
      FaultAction action,
      FaultBoundary boundary) {
    Fixture fixture = new Fixture(1, 16);
    assertEquals(StatusCode.OK, fixture.directory.createDirectory("wal", fixture.result));
    assertEquals(
        StatusCode.OK,
        fixture.provider.script(DirectoryOperation.DIRECTORY_FORCE, boundary, action, 0));
    assertEquals(expected(action), fixture.directory.force(fixture.result));
    fixture.ensureRunning(action);
    assertEquals(boundary == FaultBoundary.AFTER, fixture.exists("wal"));
  }

  private static void assertFileForceBoundary(FaultAction action, FaultBoundary boundary) {
    Fixture fixture = new Fixture(1, 32);
    fixture.createDurable("data", new byte[] {1, 2, 3, 4});
    assertEquals(StatusCode.OK, fixture.directory.truncate("data", 2, fixture.result));
    DurableFile file = fixture.result.file();
    assertEquals(
        StatusCode.OK,
        fixture.provider.script(DirectoryOperation.FILE_FORCE, boundary, action, 0));
    assertEquals(expected(action), file.force(ForceMode.CONTENT_AND_METADATA));
    fixture.ensureRunning(action);
    assertEquals(boundary == FaultBoundary.AFTER ? 2 : 4, fixture.size("data"));
  }

  private static void assertObservationBoundary(
      FaultAction action,
      FaultBoundary boundary,
      DirectoryOperation operation) {
    Fixture fixture = new Fixture(1, 32);
    fixture.createDurable("data", new byte[] {1});
    DurableFile file = null;
    if (operation == DirectoryOperation.FILE_READ) {
      assertEquals(StatusCode.OK, fixture.directory.reopen("data", fixture.result));
      file = fixture.result.file();
    }
    assertEquals(StatusCode.OK, fixture.provider.script(operation, boundary, action, 0));
    StatusCode status;
    if (operation == DirectoryOperation.LIST) {
      status = fixture.directory.list(fixture.list);
    } else if (operation == DirectoryOperation.REOPEN) {
      status = fixture.directory.reopen("data", fixture.result);
    } else {
      status = file.read(0, ByteBuffer.allocate(1), fixture.io);
    }
    assertEquals(expected(action), status);
    fixture.ensureRunning(action);
    assertArrayEquals(new byte[] {1}, fixture.read("data"));
  }

  private static void assertWriteBoundary(FaultAction action, FaultBoundary boundary) {
    Fixture fixture = new Fixture(1, 32);
    fixture.createDurable("data", new byte[] {1});
    assertEquals(StatusCode.OK, fixture.directory.reopen("data", fixture.result));
    DurableFile file = fixture.result.file();
    assertEquals(
        StatusCode.OK,
        fixture.provider.script(DirectoryOperation.FILE_WRITE, boundary, action, 0));
    assertEquals(expected(action), file.write(0, ByteBuffer.wrap(new byte[] {9}), fixture.io));
    fixture.ensureRunning(action);
    assertArrayEquals(new byte[] {1}, fixture.read("data"));
  }

  private static StatusCode expected(FaultAction action) {
    return action == FaultAction.CRASH ? StatusCode.IO_FAILURE : StatusCode.CANCELLED;
  }

  private static final class Fixture {
    private final FaultingDurableDirectoryContractProvider provider;
    private final DurableDirectory directory;
    private final DirectoryOperationResult result = new DirectoryOperationResult();
    private final DirectoryListResult list = new DirectoryListResult(16);
    private final IoResult io = new IoResult();
    private final FileSizeResult size = new FileSizeResult();

    private Fixture(int rules, int trace) {
      provider = new FaultingDurableDirectoryContractProvider(rules, trace);
      directory = provider.directory();
    }

    private void createDurable(String name, byte[] bytes) {
      assertEquals(StatusCode.OK, directory.createFile(name, result));
      DurableFile file = result.file();
      assertEquals(StatusCode.OK, file.write(0, ByteBuffer.wrap(bytes), io));
      assertEquals(bytes.length, io.bytesTransferred());
      assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT_AND_METADATA));
      assertEquals(StatusCode.OK, file.close());
      assertEquals(StatusCode.OK, directory.force(result));
    }

    private void restartAfterCrash() {
      assertEquals(StatusCode.OK, provider.crash());
      assertEquals(StatusCode.OK, provider.restart());
    }

    private void ensureRunning(FaultAction action) {
      if (action == FaultAction.CRASH) {
        assertEquals(StatusCode.OK, provider.restart());
      }
    }

    private boolean exists(String name) {
      list.reset();
      assertEquals(StatusCode.OK, directory.list(list));
      for (int index = 0; index < list.size(); index++) {
        if (name.equals(list.name(index))) {
          return true;
        }
      }
      return false;
    }

    private long size(String name) {
      assertEquals(StatusCode.OK, directory.reopen(name, result));
      DurableFile file = result.file();
      assertEquals(StatusCode.OK, file.size(size));
      assertEquals(StatusCode.OK, file.close());
      return size.sizeBytes();
    }

    private byte[] read(String name) {
      assertEquals(StatusCode.OK, directory.reopen(name, result));
      DurableFile file = result.file();
      assertEquals(StatusCode.OK, file.size(size));
      ByteBuffer target = ByteBuffer.allocate((int) size.sizeBytes());
      io.reset();
      assertEquals(StatusCode.OK, file.read(0, target, io));
      assertEquals(StatusCode.OK, file.close());
      return target.array();
    }
  }
}
