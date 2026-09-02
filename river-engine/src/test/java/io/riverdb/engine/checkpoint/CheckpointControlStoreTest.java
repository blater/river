package io.riverdb.engine.checkpoint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        StatusCode.OK, directory.reopen("river.checkpoint.versions.0", operation));
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
    assertEquals(StatusCode.OK, directory.createFile(CheckpointControlStore.FILE_NAME, operation));
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

  private static FaultFixture fixtureWithOldSparseCheckpoint() {
    FaultFixture fixture = new FaultFixture();
    assertEquals(StatusCode.OK, fixture.store.install(fixture.directory, sparseState(1)));
    return fixture;
  }

  private static CheckpointState oldState() {
    return state(WalGeneration.of(2), 1, 19, 23, 5, 2);
  }

  private static CheckpointState sparseState(long checkpointId) {
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

  private static CheckpointState largeState(long checkpointId, long rows) {
    return largeState(DATABASE, checkpointId, rows);
  }

  private static CheckpointState largeState(
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

  private static void assertVersion(
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
    return openDirectory(root, new NioIoCounters());
  }

  private static NioDurableDirectory openDirectory(Path root, NioIoCounters counters) {
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

  private static long fileSize(Path file) {
    try {
      return Files.size(file);
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }

  private static final class FaultFixture {
    private final CrashPointController controller = new CrashPointController(8);
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
          CheckpointVersionFormat.SEGMENT_BYTES + 4_096,
          8,
          controller,
          points);
    }

    private StatusCode arm(
        DirectoryOperation operationName,
        FaultBoundary boundary,
        FaultAction action,
        long argument) {
      return arm(operationName, boundary, 1, 1, action, argument);
    }

    private StatusCode arm(
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

    private void assertSparseCheckpoint(long checkpointId) {
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
