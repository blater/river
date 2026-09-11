package io.riverdb.engine.checkpoint;

import static io.riverdb.engine.checkpoint.CheckpointControlStoreTestFixture.DATABASE;
import static io.riverdb.engine.checkpoint.CheckpointControlStoreTestFixture.TEMPORARY_FILE_NAME;
import static io.riverdb.engine.checkpoint.CheckpointControlStoreTestFixture.assertInstallFailurePreservesOld;
import static io.riverdb.engine.checkpoint.CheckpointControlStoreTestFixture.assertVersion;
import static io.riverdb.engine.checkpoint.CheckpointControlStoreTestFixture.fileSize;
import static io.riverdb.engine.checkpoint.CheckpointControlStoreTestFixture.fixtureWithOldCheckpoint;
import static io.riverdb.engine.checkpoint.CheckpointControlStoreTestFixture.fixtureWithOldSparseCheckpoint;
import static io.riverdb.engine.checkpoint.CheckpointControlStoreTestFixture.largeState;
import static io.riverdb.engine.checkpoint.CheckpointControlStoreTestFixture.newState;
import static io.riverdb.engine.checkpoint.CheckpointControlStoreTestFixture.openDirectory;
import static io.riverdb.engine.checkpoint.CheckpointControlStoreTestFixture.sparseState;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointControlStoreTestFixture.FaultFixture;
import io.riverdb.engine.testsupport.fault.DirectoryOperation;
import io.riverdb.engine.testsupport.fault.FaultAction;
import io.riverdb.engine.testsupport.fault.FaultBoundary;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.platform.file.FileIoMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckpointControlStoreTest {
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
    CheckpointVersionResult version = new CheckpointVersionResult();
    assertEquals(StatusCode.OK, control.read(directory, decoded));
    assertEquals(DATABASE, decoded.database());
    assertEquals(WalGeneration.of(2), decoded.walGeneration());
    assertEquals(1, decoded.checkpointId());
    assertEquals(19, decoded.commitSequence());
    assertEquals(23, decoded.maximumTransactionId());
    assertEquals(5, decoded.pageCount());
    assertEquals(65, decoded.rowCount());
    assertVersion(decoded, version, 1, 19, 0, true);
    assertVersion(decoded, version, 64, 19, 0, true);
    assertVersion(decoded, version, 65, 17, 64, true);

    state.reset();
    assertEquals(
        StatusCode.OK,
        state.set(DATABASE, WalGeneration.of(3), 2, 29, 31, 6, 1));
    assertEquals(StatusCode.OK, control.install(directory, state));
    assertEquals(StatusCode.OK, control.read(directory, decoded));
    assertEquals(WalGeneration.of(3), decoded.walGeneration());
    assertEquals(2, decoded.checkpointId());
    assertVersion(decoded, version, 1, 29, 0, false);
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
  void rejectsReusedCheckpointIdWithoutReplacingGeneration(@TempDir Path root)
      throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    CheckpointControlStore control = new CheckpointControlStore();
    CheckpointState original = largeState(1, CheckpointState.MAXIMUM_ROWS + 1L);
    assertEquals(
        StatusCode.OK,
        original.setRowVersion(original.rowCount(), 1, original.rowCount() - 1, true));
    assertEquals(StatusCode.OK, control.install(directory, original));
    Path generation = root.resolve("river.checkpoint.versions.0");
    byte[] originalBytes = Files.readAllBytes(generation);
    CheckpointState reused = new CheckpointState();
    assertEquals(
        StatusCode.OK,
        reused.setLarge(
            DATABASE, WalGeneration.of(3), 1, 29, 31, 6,
            CheckpointState.MAXIMUM_ROWS + 1L));
    assertEquals(
        StatusCode.OK,
        reused.setRowVersion(reused.rowCount(), 27, reused.rowCount() - 2, true));

    assertEquals(StatusCode.CONFLICT, control.install(directory, reused));
    assertArrayEquals(originalBytes, Files.readAllBytes(generation));

    CheckpointState decoded = new CheckpointState();
    assertEquals(StatusCode.OK, control.read(directory, decoded));
    assertEquals(WalGeneration.of(1), decoded.walGeneration());
    assertEquals(1, decoded.commitSequence());
    assertEquals(CheckpointState.MAXIMUM_ROWS + 1L, decoded.rowCount());
    assertVersion(
        decoded, new CheckpointVersionResult(), decoded.rowCount(), 1,
        decoded.rowCount() - 1, true);
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void rejectsCheckpointIdOlderThanCurrentAuthority(@TempDir Path root) throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    CheckpointControlStore control = new CheckpointControlStore();
    CheckpointState current = largeState(3, CheckpointState.MAXIMUM_ROWS + 1L);
    assertEquals(StatusCode.OK, current.setRowVersion(current.rowCount(), 1, 1, false));
    assertEquals(StatusCode.OK, control.install(directory, current));
    byte[] manifest = Files.readAllBytes(root.resolve(CheckpointControlStore.FILE_NAME));
    byte[] generation = Files.readAllBytes(root.resolve("river.checkpoint.versions.0"));

    CheckpointState stale = largeState(2, CheckpointState.MAXIMUM_ROWS + 1L);
    assertEquals(StatusCode.OK, stale.setRowVersion(stale.rowCount(), 1, 1, false));
    assertEquals(StatusCode.CONFLICT, control.install(directory, stale));
    assertArrayEquals(manifest, Files.readAllBytes(root.resolve(CheckpointControlStore.FILE_NAME)));
    assertArrayEquals(generation, Files.readAllBytes(root.resolve("river.checkpoint.versions.0")));
    assertEquals(false, Files.exists(root.resolve("river.checkpoint.versions.1")));
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void roundTripsThreeBillionRowAppendOnlyManifestWithoutRowMetadataScan(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    CheckpointState state = new CheckpointState();
    long rowCount = 3_000_000_000L;
    assertEquals(
        StatusCode.OK,
        state.setLarge(DATABASE, WalGeneration.of(7), 4, 101, 103, 900_000, rowCount));
    assertEquals(false, state.versionDirectoryRequired());
    CheckpointControlStore control = new CheckpointControlStore();
    assertEquals(StatusCode.OK, control.install(directory, state));

    CheckpointState decoded = new CheckpointState();
    CheckpointVersionResult version = new CheckpointVersionResult();
    assertEquals(StatusCode.OK, control.read(directory, decoded));
    assertEquals(rowCount, decoded.rowCount());
    assertEquals(900_000, decoded.pageCount());
    assertVersion(decoded, version, rowCount, 101, 0, false);
    decoded.close();
    state.close();
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void roundTripsHistoricalRowsThroughLazyVersionDirectory(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    CheckpointState state = new CheckpointState();
    long rowCount = CheckpointState.MAXIMUM_ROWS + 1L;
    assertEquals(
        StatusCode.OK,
        state.setLarge(DATABASE, WalGeneration.of(8), 5, 101, 103, 900_001, rowCount));
    assertEquals(StatusCode.OK, state.setRowVersion(rowCount, 100, rowCount - 1, true));
    assertEquals(true, state.versionDirectoryRequired());
    CheckpointControlStore control = new CheckpointControlStore();
    assertEquals(StatusCode.OK, control.install(directory, state));
    assertEquals(CheckpointControlStore.BYTES,
        fileSize(root.resolve(CheckpointControlStore.FILE_NAME)));
    assertTrue(fileSize(root.resolve("river.checkpoint.versions.0")) < 100_000);

    CheckpointState decoded = new CheckpointState();
    CheckpointVersionResult version = new CheckpointVersionResult();
    assertEquals(StatusCode.OK, control.read(directory, decoded));
    assertEquals(rowCount, decoded.rowCount());
    assertVersion(decoded, version, rowCount, 100, rowCount - 1, true);
    decoded.close();
    state.close();
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void cachesOneSparseVersionPageForRepeatedRandomReads(@TempDir Path root) {
    NioIoCounters counters = new NioIoCounters();
    NioDurableDirectory directory = openDirectory(root, counters);
    CheckpointState state = new CheckpointState();
    long horizon = 3_000_000_000L;
    assertEquals(
        StatusCode.OK,
        state.setLarge(DATABASE, WalGeneration.of(9), 6, 101, 103, 900_002, horizon));
    assertEquals(StatusCode.OK, state.setRowVersion(horizon, 100, horizon - 1, true));
    CheckpointControlStore control = new CheckpointControlStore();
    assertEquals(StatusCode.OK, control.install(directory, state));

    CheckpointState decoded = new CheckpointState();
    assertEquals(StatusCode.OK, control.read(directory, decoded));
    long readsBefore = counters.readCalls();
    CheckpointVersionResult version = new CheckpointVersionResult();
    assertVersion(decoded, version, horizon, 100, horizon - 1, true);
    assertVersion(decoded, version, horizon, 100, horizon - 1, true);
    assertVersion(decoded, version, horizon - 1, 101, 0, false);
    assertEquals(readsBefore + 1, counters.readCalls());
    decoded.close();
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void reclaimsSupersededSparseVersionGeneration(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    CheckpointControlStore control = new CheckpointControlStore();
    CheckpointState first = largeState(7, CheckpointState.MAXIMUM_ROWS + 1L);
    assertEquals(StatusCode.OK, first.setRowVersion(first.rowCount(), 1, 1, false));
    assertEquals(StatusCode.OK, control.install(directory, first));
    assertTrue(Files.exists(root.resolve("river.checkpoint.versions.0")));

    CheckpointState second = largeState(8, CheckpointState.MAXIMUM_ROWS + 1L);
    assertEquals(StatusCode.OK, second.setRowVersion(second.rowCount(), 1, 1, false));
    assertEquals(StatusCode.OK, control.install(directory, second));
    assertEquals(false, Files.exists(root.resolve("river.checkpoint.versions.0")));
    assertTrue(Files.exists(root.resolve("river.checkpoint.versions.1")));
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void detectsSparseSegmentCorruptionOnFirstPageRead(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    CheckpointControlStore control = new CheckpointControlStore();
    CheckpointState state = largeState(9, CheckpointState.MAXIMUM_ROWS + 1L);
    long rowId = state.rowCount();
    assertEquals(StatusCode.OK, state.setRowVersion(rowId, 1, rowId - 1, true));
    assertEquals(StatusCode.OK, control.install(directory, state));

    DirectoryOperationResult operation = new DirectoryOperationResult();
    IoResult io = new IoResult();
    assertEquals(
        StatusCode.OK, directory.reopen("river.checkpoint.versions.0", FileIoMode.POSITIONAL, operation));
    DurableFile file = operation.file();
    ByteBuffer header = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(StatusCode.OK, file.read(0, header, io));
    long segmentOffset = header.getLong(56);
    assertEquals(StatusCode.OK, file.write(segmentOffset + 48, ByteBuffer.wrap(new byte[] {7}), io));
    assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, file.close());

    CheckpointState decoded = new CheckpointState();
    assertEquals(StatusCode.OK, control.read(directory, decoded));
    assertEquals(StatusCode.CORRUPTION,
        decoded.readVersion(rowId, new CheckpointVersionResult()));
    decoded.close();
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void rejectsSparseGenerationTransplantedFromAnotherDatabase(@TempDir Path root)
      throws Exception {
    Path leftRoot = Files.createDirectories(root.resolve("left"));
    Path rightRoot = Files.createDirectories(root.resolve("right"));
    NioDurableDirectory left = openDirectory(leftRoot);
    NioDurableDirectory right = openDirectory(rightRoot);
    CheckpointControlStore leftControl = new CheckpointControlStore();
    CheckpointControlStore rightControl = new CheckpointControlStore();
    long rows = CheckpointState.MAXIMUM_ROWS + 1L;
    CheckpointState leftState = largeState(DATABASE, 11, rows);
    CheckpointState rightState = largeState(
        DatabaseIncarnation.of(DATABASE.high() + 1, DATABASE.low()), 11, rows);
    assertEquals(StatusCode.OK, leftState.setRowVersion(rows, 1, rows - 1, true));
    assertEquals(StatusCode.OK, rightState.setRowVersion(rows, 1, rows - 1, true));
    assertEquals(StatusCode.OK, leftControl.install(left, leftState));
    assertEquals(StatusCode.OK, rightControl.install(right, rightState));

    Files.copy(
        leftRoot.resolve("river.checkpoint.versions.0"),
        rightRoot.resolve("river.checkpoint.versions.0"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    assertEquals(
        StatusCode.CORRUPTION,
        rightControl.read(right, new CheckpointState()));
    assertEquals(StatusCode.OK, left.close());
    assertEquals(StatusCode.OK, right.close());
  }

  @Test
  void rejectsSparseSegmentTransplantedFromAnotherDatabase(@TempDir Path root)
      throws Exception {
    Path leftRoot = Files.createDirectories(root.resolve("left"));
    Path rightRoot = Files.createDirectories(root.resolve("right"));
    NioDurableDirectory left = openDirectory(leftRoot);
    NioDurableDirectory right = openDirectory(rightRoot);
    long rows = CheckpointState.MAXIMUM_ROWS + 1L;
    CheckpointState leftState = largeState(DATABASE, 12, rows);
    CheckpointState rightState = largeState(
        DatabaseIncarnation.of(DATABASE.high() + 1, DATABASE.low()), 12, rows);
    assertEquals(StatusCode.OK, leftState.setRowVersion(rows, 1, rows - 1, true));
    assertEquals(StatusCode.OK, rightState.setRowVersion(rows, 1, rows - 1, true));
    assertEquals(StatusCode.OK, new CheckpointControlStore().install(left, leftState));
    assertEquals(StatusCode.OK, new CheckpointControlStore().install(right, rightState));
    Path leftGeneration = leftRoot.resolve("river.checkpoint.versions.0");
    Path rightGeneration = rightRoot.resolve("river.checkpoint.versions.0");
    byte[] leftBytes = Files.readAllBytes(leftGeneration);
    byte[] rightBytes = Files.readAllBytes(rightGeneration);
    long dataOffset = ByteBuffer.wrap(rightBytes).order(ByteOrder.LITTLE_ENDIAN).getLong(56);
    System.arraycopy(
        leftBytes, Math.toIntExact(dataOffset), rightBytes, Math.toIntExact(dataOffset),
        CheckpointState.VERSION_SEGMENT_BYTES);
    Files.write(rightGeneration, rightBytes);

    CheckpointState decoded = new CheckpointState();
    assertEquals(StatusCode.OK, new CheckpointControlStore().read(right, decoded));
    assertEquals(
        StatusCode.CORRUPTION,
        decoded.readVersion(rows, new CheckpointVersionResult()));
    decoded.close();
    assertEquals(StatusCode.OK, left.close());
    assertEquals(StatusCode.OK, right.close());
  }

  @Test
  void rejectsPreV3CheckpointAuthority(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    IoResult io = new IoResult();
    assertEquals(StatusCode.OK, directory.createFile(CheckpointControlStore.FILE_NAME, FileIoMode.POSITIONAL, operation));
    DurableFile file = operation.file();
    assertEquals(StatusCode.OK, file.write(0, ByteBuffer.allocate(512), io));
    assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, file.close());
    assertEquals(StatusCode.OK, directory.force(operation));

    assertEquals(
        StatusCode.CORRUPTION,
        new CheckpointControlStore().read(directory, new CheckpointState()));
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void sparseGenerationWriteAndForceFailuresPreserveOldAuthority() {
    for (int occurrence = 1; occurrence <= 3; occurrence++) {
      FaultFixture shortWrite = fixtureWithOldSparseCheckpoint();
      assertEquals(
          StatusCode.OK,
          shortWrite.arm(
              DirectoryOperation.FILE_WRITE,
              FaultBoundary.BEFORE,
              occurrence,
              1,
              FaultAction.SHORT_WRITE,
              1));
      assertEquals(
          StatusCode.IO_FAILURE,
          shortWrite.store.install(shortWrite.directory, sparseState(2)));
      shortWrite.crashRestart();
      shortWrite.assertSparseCheckpoint(1);
    }

    FaultFixture forceFailure = fixtureWithOldSparseCheckpoint();
    assertEquals(
        StatusCode.OK,
        forceFailure.arm(
            DirectoryOperation.FILE_FORCE,
            FaultBoundary.BEFORE,
            FaultAction.FORCE_FAILURE,
            0));
    assertEquals(
        StatusCode.IO_FAILURE,
        forceFailure.store.install(forceFailure.directory, sparseState(2)));
    forceFailure.crashRestart();
    forceFailure.assertSparseCheckpoint(1);
  }

  @Test
  void sparseGenerationRenameCrashPreservesOldAuthority() {
    for (FaultBoundary boundary : FaultBoundary.values()) {
      FaultFixture fixture = fixtureWithOldSparseCheckpoint();
      assertEquals(
          StatusCode.OK,
          fixture.arm(
              DirectoryOperation.RENAME, boundary, FaultAction.CRASH, 0));
      assertEquals(
          StatusCode.IO_FAILURE,
          fixture.store.install(fixture.directory, sparseState(2)));
      assertEquals(StatusCode.OK, fixture.directory.restart());
      fixture.assertSparseCheckpoint(1);
      assertEquals(false, fixture.exists("river.checkpoint.versions.1"));
    }
  }

  @Test
  void sparseGenerationDirectoryForceCrashLeavesRetryableBoundedOrphan() {
    for (FaultBoundary boundary : FaultBoundary.values()) {
      FaultFixture fixture = fixtureWithOldSparseCheckpoint();
      assertEquals(
          StatusCode.OK,
          fixture.arm(
              DirectoryOperation.DIRECTORY_FORCE, boundary, FaultAction.CRASH, 0));
      assertEquals(
          StatusCode.IO_FAILURE,
          fixture.store.install(fixture.directory, sparseState(2)));
      assertEquals(StatusCode.OK, fixture.directory.restart());
      fixture.assertSparseCheckpoint(1);
      assertEquals(
          boundary == FaultBoundary.AFTER,
          fixture.exists("river.checkpoint.versions.1"));
      assertEquals(StatusCode.OK, fixture.store.install(fixture.directory, sparseState(3)));
      fixture.assertSparseCheckpoint(3);
    }
  }

  @Test
  void sparseManifestReplaceCrashRetainsOldRootAndRetryRemovesOrphan() {
    for (FaultBoundary boundary : FaultBoundary.values()) {
      FaultFixture fixture = fixtureWithOldSparseCheckpoint();
      assertEquals(
          StatusCode.OK,
          fixture.arm(
              DirectoryOperation.RENAME,
              boundary,
              3,
              1,
              FaultAction.CRASH,
              0));
      assertEquals(
          StatusCode.IO_FAILURE,
          fixture.store.install(fixture.directory, sparseState(2)));
      assertEquals(StatusCode.OK, fixture.directory.restart());
      fixture.assertSparseCheckpoint(1);
      assertEquals(true, fixture.exists("river.checkpoint.versions.1"));
      assertEquals(StatusCode.OK, fixture.store.install(fixture.directory, sparseState(3)));
      fixture.assertSparseCheckpoint(3);
    }
  }

  @Test
  void sparseManifestForceCrashSelectsOneIntactRoot() {
    for (FaultBoundary boundary : FaultBoundary.values()) {
      FaultFixture fixture = fixtureWithOldSparseCheckpoint();
      assertEquals(
          StatusCode.OK,
          fixture.arm(
              DirectoryOperation.DIRECTORY_FORCE,
              boundary,
              3,
              1,
              FaultAction.CRASH,
              0));
      assertEquals(
          StatusCode.IO_FAILURE,
          fixture.store.install(fixture.directory, sparseState(2)));
      assertEquals(StatusCode.OK, fixture.directory.restart());
      fixture.assertSparseCheckpoint(boundary == FaultBoundary.BEFORE ? 1 : 2);
    }
  }

  @Test
  void durableManifestForceCancellationReturnsPublishedSuccess() {
    FaultFixture fixture = fixtureWithOldSparseCheckpoint();
    assertEquals(
        StatusCode.OK,
        fixture.arm(
            DirectoryOperation.DIRECTORY_FORCE,
            FaultBoundary.AFTER,
            3,
            1,
            FaultAction.CANCEL,
            0));

    assertEquals(StatusCode.OK, fixture.store.install(fixture.directory, sparseState(2)));
    fixture.assertSparseCheckpoint(2);
  }

  @Test
  void durableAuthorityIgnoresCleanupFailureAndNextInstallBackpressures() {
    FaultFixture fixture = fixtureWithOldSparseCheckpoint();
    assertEquals(
        StatusCode.OK,
        fixture.arm(
            DirectoryOperation.REMOVE,
            FaultBoundary.BEFORE,
            3,
            2,
            FaultAction.CANCEL,
            0));

    assertEquals(StatusCode.OK, fixture.store.install(fixture.directory, sparseState(2)));
    assertEquals(true, fixture.exists("river.checkpoint.versions.0"));
    assertEquals(true, fixture.exists("river.checkpoint.versions.1"));
    assertEquals(StatusCode.RETRY, fixture.store.install(fixture.directory, sparseState(3)));
    assertEquals(true, fixture.exists("river.checkpoint.versions.0"));
    assertEquals(true, fixture.exists("river.checkpoint.versions.1"));
    assertEquals(StatusCode.OK, fixture.store.install(fixture.directory, sparseState(3)));
    fixture.assertSparseCheckpoint(3);
  }

  @Test
  void shortWriteAndFileForceFailurePreserveOldCheckpoint() {
    FaultFixture shortWrite = fixtureWithOldCheckpoint();
    assertEquals(
        StatusCode.OK,
        shortWrite.arm(
            DirectoryOperation.FILE_WRITE,
            FaultBoundary.BEFORE,
            2,
            1,
            FaultAction.SHORT_WRITE,
            CheckpointControlStore.BYTES - 1L));
    assertInstallFailurePreservesOld(shortWrite, StatusCode.IO_FAILURE);

    FaultFixture forceFailure = fixtureWithOldCheckpoint();
    assertEquals(
        StatusCode.OK,
        forceFailure.arm(
            DirectoryOperation.FILE_FORCE,
            FaultBoundary.BEFORE,
            2,
            1,
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
              2,
              1,
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
              2,
              1,
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
            3,
            1,
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
              2,
              1,
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

}
