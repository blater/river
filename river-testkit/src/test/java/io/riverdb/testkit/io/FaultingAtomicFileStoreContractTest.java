package io.riverdb.testkit.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultOperation;
import io.riverdb.platform.fault.FaultPoint;
import io.riverdb.platform.fault.FaultPointRegistry;
import io.riverdb.platform.fault.FaultPointSlot;
import io.riverdb.platform.file.AtomicInstallPhase;
import io.riverdb.platform.file.AtomicInstallProgress;
import io.riverdb.platform.file.AtomicInstallRequest;
import io.riverdb.platform.file.AtomicInstallResult;
import io.riverdb.platform.file.AtomicInstallStep;
import io.riverdb.platform.file.DirectoryDurability;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import io.riverdb.testkit.crash.CrashPointController;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class FaultingAtomicFileStoreContractTest {
  @Test
  void installsForcesAndVerifiesBeforeReportingCompletion() {
    Fixture fixture = new Fixture(0, 16);
    AtomicInstallRequest request = request(new byte[] {1, 2, 3, 4});
    AtomicInstallProgress progress = new AtomicInstallProgress();
    AtomicInstallDriveResult driveResult = new AtomicInstallDriveResult();

    assertEquals(
        StatusCode.OK,
        fixture.contract.drive(
            fixture.store,
            request,
            progress,
            fixture.stepResult,
            8,
            driveResult));
    assertTrue(progress.isComplete());
    assertEquals(DirectoryDurability.DURABLE, progress.durability());
    assertEquals(6, driveResult.advances());
    assertEquals(6, fixture.trace.size());
    AtomicInstallStep[] expectedSteps = {
      AtomicInstallStep.TEMP_CREATE,
      AtomicInstallStep.TEMP_WRITE,
      AtomicInstallStep.TEMP_FORCE,
      AtomicInstallStep.DESTINATION_REPLACE,
      AtomicInstallStep.PARENT_DIRECTORY_FORCE,
      AtomicInstallStep.REOPEN_VERIFY
    };
    for (int index = 0; index < expectedSteps.length; index++) {
      assertEquals(expectedSteps[index], fixture.trace.step(index));
      assertEquals(StatusCode.OK, fixture.trace.status(index));
    }
    assertEquals(StatusCode.OK, fixture.store.crash());
    assertEquals(StatusCode.OK, fixture.store.restart());
    assertArrayEquals(new byte[] {1, 2, 3, 4}, fixture.reopenAndRead("control"));
    assertEquals(
        StatusCode.OK,
        fixture.contract.verifyInstalled(
            fixture.store,
            "control",
            ByteBuffer.wrap(new byte[] {1, 2, 3, 4}),
            ByteBuffer.allocate(4),
            new DirectoryOperationResult(),
            new FileSizeResult(),
            new IoResult()));
  }

  @Test
  void delayedReplaceCompletionCannotRepeatTheNamespaceMutation() {
    Fixture fixture = new Fixture(1, 16);
    assertEquals(
        StatusCode.OK,
        fixture.controller.addRule(
            fixture.points.replaceAfter(),
            FaultOperation.REPLACE,
            1,
            1,
            FaultAction.DELAY,
            0));
    AtomicInstallRequest request = request(new byte[] {8, 9});
    AtomicInstallProgress progress = new AtomicInstallProgress();

    assertEquals(StatusCode.OK, fixture.store.advance(request, progress, fixture.stepResult));
    assertEquals(StatusCode.OK, fixture.store.advance(request, progress, fixture.stepResult));
    assertEquals(StatusCode.OK, fixture.store.advance(request, progress, fixture.stepResult));
    assertEquals(StatusCode.RETRY, fixture.store.advance(request, progress, fixture.stepResult));
    assertEquals(AtomicInstallPhase.CONTENT_FORCED, progress.phase());
    assertTrue(progress.completionPending());
    assertEquals(AtomicInstallPhase.DESTINATION_REPLACED, fixture.stepResult.phaseAfter());
    assertEquals(
        DirectoryDurability.VISIBLE_NOT_DURABLE,
        fixture.stepResult.durability());

    assertEquals(StatusCode.OK, fixture.store.advance(request, progress, fixture.stepResult));
    assertEquals(AtomicInstallPhase.DESTINATION_REPLACED, progress.phase());
    assertFalse(progress.completionPending());
    assertEquals(StatusCode.OK, fixture.store.advance(request, progress, fixture.stepResult));
    assertEquals(StatusCode.OK, fixture.store.advance(request, progress, fixture.stepResult));
    assertTrue(progress.isComplete());
  }

  @Test
  void everyAppliedBoundaryCanDelayCompletionWithoutRepeatingWork() {
    for (int boundary = 0; boundary < 6; boundary++) {
      Fixture fixture = new Fixture(1, 24);
      assertEquals(
          StatusCode.OK,
          fixture.controller.addRule(
              fixture.afterPoint(boundary),
              fixture.operation(boundary),
              1,
              1,
              FaultAction.DELAY,
              0));
      AtomicInstallProgress progress = new AtomicInstallProgress();
      AtomicInstallDriveResult driveResult = new AtomicInstallDriveResult();
      assertEquals(
          StatusCode.OK,
          fixture.contract.drive(
              fixture.store,
              request(new byte[] {1, 3, 5}),
              progress,
              fixture.stepResult,
              9,
              driveResult),
          "boundary " + boundary);
      assertTrue(progress.isComplete(), "boundary " + boundary);
      assertEquals(7, driveResult.advances(), "boundary " + boundary);
      assertTrue(fixture.hasPendingCompletion(), "boundary " + boundary);
    }
  }

  @Test
  void delayBeforeEveryBoundaryIsAVisibleNoOpAndRetryIsSafe() {
    for (int boundary = 0; boundary < 6; boundary++) {
      Fixture fixture = new Fixture(1, 24);
      assertEquals(
          StatusCode.OK,
          fixture.controller.addRule(
              fixture.beforePoint(boundary),
              fixture.operation(boundary),
              1,
              1,
              FaultAction.DELAY,
              0));
      AtomicInstallProgress progress = new AtomicInstallProgress();
      AtomicInstallDriveResult driveResult = new AtomicInstallDriveResult();
      assertEquals(
          StatusCode.OK,
          fixture.contract.drive(
              fixture.store,
              request(new byte[] {2, 4, 6}),
              progress,
              fixture.stepResult,
              9,
              driveResult),
          "boundary " + boundary);
      assertTrue(progress.isComplete(), "boundary " + boundary);
      assertEquals(7, driveResult.advances(), "boundary " + boundary);
    }
  }

  @Test
  void shortAndDiskFullWritesExposeTheirAppliedPrefixAndCanResume() {
    Fixture shortWrite = new Fixture(1, 16);
    assertEquals(
        StatusCode.OK,
        shortWrite.controller.addRule(
            shortWrite.points.tempWriteBefore(),
            FaultOperation.TEMP_WRITE,
            1,
            1,
            FaultAction.SHORT_WRITE,
            2));
    AtomicInstallRequest request = request(new byte[] {1, 2, 3, 4});
    AtomicInstallProgress progress = new AtomicInstallProgress();
    assertEquals(StatusCode.OK, shortWrite.store.advance(request, progress, shortWrite.stepResult));
    assertEquals(StatusCode.OK, shortWrite.store.advance(request, progress, shortWrite.stepResult));
    assertEquals(2, progress.bytesWritten());
    assertEquals(AtomicInstallPhase.TEMP_CREATED, progress.phase());
    AtomicInstallDriveResult driveResult = new AtomicInstallDriveResult();
    assertEquals(
        StatusCode.OK,
        shortWrite.contract.drive(
            shortWrite.store,
            request,
            progress,
            shortWrite.stepResult,
            8,
            driveResult));

    Fixture diskFull = new Fixture(1, 16);
    assertEquals(
        StatusCode.OK,
        diskFull.controller.addRule(
            diskFull.points.tempWriteBefore(),
            FaultOperation.TEMP_WRITE,
            1,
            1,
            FaultAction.DISK_FULL,
            1));
    progress.reset();
    assertEquals(StatusCode.OK, diskFull.store.advance(request, progress, diskFull.stepResult));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        diskFull.store.advance(request, progress, diskFull.stepResult));
    assertEquals(1, progress.bytesWritten());
    assertEquals(1, diskFull.stepResult.bytesTransferred());
    assertEquals(
        StatusCode.OK,
        diskFull.contract.drive(
            diskFull.store,
            request,
            progress,
            diskFull.stepResult,
            8,
            driveResult));
  }

  @Test
  void shortVerificationReadReportsProgressAndRetriesWithoutPromotion() {
    Fixture fixture = new Fixture(1, 16);
    assertEquals(
        StatusCode.OK,
        fixture.controller.addRule(
            fixture.points.reopenVerifyBefore(),
            FaultOperation.REOPEN_VERIFY,
            1,
            1,
            FaultAction.SHORT_READ,
            2));
    AtomicInstallRequest request = request(new byte[] {4, 3, 2, 1});
    AtomicInstallProgress progress = new AtomicInstallProgress();
    for (int index = 0; index < 5; index++) {
      assertEquals(StatusCode.OK, fixture.store.advance(request, progress, fixture.stepResult));
    }

    assertEquals(StatusCode.RETRY, fixture.store.advance(request, progress, fixture.stepResult));
    assertEquals(2, fixture.stepResult.bytesTransferred());
    assertEquals(AtomicInstallPhase.DIRECTORY_FORCED, progress.phase());
    assertEquals(StatusCode.OK, fixture.store.advance(request, progress, fixture.stepResult));
    assertTrue(progress.isComplete());
  }

  @Test
  void forceFailuresDoNotPromoteTheAffectedDurabilityPhase() {
    Fixture fileForce = new Fixture(1, 16);
    assertEquals(
        StatusCode.OK,
        fileForce.controller.addRule(
            fileForce.points.tempForceBefore(),
            FaultOperation.TEMP_FORCE,
            1,
            1,
            FaultAction.FORCE_FAILURE,
            0));
    AtomicInstallRequest request = request(new byte[] {4, 5});
    AtomicInstallProgress progress = new AtomicInstallProgress();
    assertEquals(StatusCode.OK, fileForce.store.advance(request, progress, fileForce.stepResult));
    assertEquals(StatusCode.OK, fileForce.store.advance(request, progress, fileForce.stepResult));
    assertEquals(StatusCode.IO_FAILURE, fileForce.store.advance(request, progress, fileForce.stepResult));
    assertEquals(AtomicInstallPhase.CONTENT_WRITTEN, progress.phase());

    Fixture directoryForce = new Fixture(1, 16);
    assertEquals(
        StatusCode.OK,
        directoryForce.controller.addRule(
            directoryForce.points.directoryForceBefore(),
            FaultOperation.DIRECTORY_FORCE,
            1,
            1,
            FaultAction.FORCE_FAILURE,
            0));
    progress.reset();
    for (int index = 0; index < 4; index++) {
      assertEquals(
          StatusCode.OK,
          directoryForce.store.advance(request, progress, directoryForce.stepResult));
    }
    assertEquals(
        StatusCode.IO_FAILURE,
        directoryForce.store.advance(request, progress, directoryForce.stepResult));
    assertEquals(AtomicInstallPhase.DESTINATION_REPLACED, progress.phase());
    assertEquals(DirectoryDurability.VISIBLE_NOT_DURABLE, progress.durability());
    assertEquals(StatusCode.OK, directoryForce.store.crash());
    assertEquals(StatusCode.OK, directoryForce.store.restart());
    assertEquals(StatusCode.CORRUPTION, directoryForce.reopenStatus("control"));
  }

  @Test
  void diskFullIsModeledAtEveryApplicableInstallBoundary() {
    FaultPointSelector[] selectors = {
      fixture -> fixture.points.tempCreateBefore(),
      fixture -> fixture.points.tempWriteBefore(),
      fixture -> fixture.points.tempForceBefore(),
      fixture -> fixture.points.directoryForceBefore()
    };
    FaultOperation[] operations = {
      FaultOperation.TEMP_CREATE,
      FaultOperation.TEMP_WRITE,
      FaultOperation.TEMP_FORCE,
      FaultOperation.DIRECTORY_FORCE
    };
    int[] advancesBeforeFault = {0, 1, 2, 4};
    StatusCode[] expectedStatuses = {
      StatusCode.RESOURCE_EXHAUSTED,
      StatusCode.RESOURCE_EXHAUSTED,
      StatusCode.RESOURCE_EXHAUSTED,
      StatusCode.RESOURCE_EXHAUSTED
    };
    AtomicInstallPhase[] expectedPhases = {
      AtomicInstallPhase.NEW,
      AtomicInstallPhase.TEMP_CREATED,
      AtomicInstallPhase.CONTENT_WRITTEN,
      AtomicInstallPhase.DESTINATION_REPLACED
    };
    for (int caseIndex = 0; caseIndex < selectors.length; caseIndex++) {
      Fixture fixture = new Fixture(1, 16);
      assertEquals(
          StatusCode.OK,
          fixture.controller.addRule(
              selectors[caseIndex].select(fixture),
              operations[caseIndex],
              1,
              1,
              FaultAction.DISK_FULL,
              caseIndex == 1 ? 1 : 0));
      AtomicInstallRequest request = request(new byte[] {9, 8, 7});
      AtomicInstallProgress progress = new AtomicInstallProgress();
      for (int advance = 0; advance < advancesBeforeFault[caseIndex]; advance++) {
        assertEquals(StatusCode.OK, fixture.store.advance(request, progress, fixture.stepResult));
      }
      assertEquals(
          expectedStatuses[caseIndex],
          fixture.store.advance(request, progress, fixture.stepResult));
      assertEquals(expectedPhases[caseIndex], progress.phase());
    }
  }

  @Test
  void crashAfterEveryBoundaryHasTheExpectedRecoveryImage() {
    for (int boundary = 0; boundary < 6; boundary++) {
      Fixture fixture = new Fixture(1, 16);
      assertEquals(
          StatusCode.OK,
          fixture.controller.addRule(
              fixture.afterPoint(boundary),
              fixture.operation(boundary),
              1,
              1,
              FaultAction.CRASH,
              0));
      AtomicInstallProgress progress = new AtomicInstallProgress();
      AtomicInstallDriveResult driveResult = new AtomicInstallDriveResult();
      assertEquals(
          StatusCode.IO_FAILURE,
          fixture.contract.drive(
              fixture.store,
              request(new byte[] {7, 6, 5}),
              progress,
              fixture.stepResult,
              8,
              driveResult),
          "boundary " + boundary);
      assertEquals(AtomicInstallPhase.RECOVERY_REQUIRED, progress.phase());
      assertEquals(StatusCode.OK, fixture.store.restart());
      StatusCode reopenStatus = fixture.reopenStatus("control");
      if (boundary < 4) {
        assertEquals(StatusCode.CORRUPTION, reopenStatus, "boundary " + boundary);
      } else {
        assertEquals(StatusCode.OK, reopenStatus, "boundary " + boundary);
        assertArrayEquals(new byte[] {7, 6, 5}, fixture.reopenAndRead("control"));
      }
    }
  }

  @Test
  void crashBetweenReplaceAndDirectoryForceRestoresPriorDestination() {
    Fixture fixture = new Fixture(1, 32);
    AtomicInstallDriveResult driveResult = new AtomicInstallDriveResult();
    assertEquals(
        StatusCode.OK,
        fixture.contract.drive(
            fixture.store,
            request(new byte[] {1, 1}),
            new AtomicInstallProgress(),
            fixture.stepResult,
            8,
            driveResult));
    assertEquals(
        StatusCode.OK,
        fixture.controller.addRule(
            fixture.points.replaceAfter(),
            FaultOperation.REPLACE,
            1,
            1,
            FaultAction.CRASH,
            0));

    assertEquals(
        StatusCode.IO_FAILURE,
        fixture.contract.drive(
            fixture.store,
            request(new byte[] {2, 2}),
            new AtomicInstallProgress(),
            fixture.stepResult,
            8,
            driveResult));
    assertEquals(StatusCode.OK, fixture.store.restart());
    assertArrayEquals(new byte[] {1, 1}, fixture.reopenAndRead("control"));
  }

  @Test
  void crashBeforeEveryBoundaryNeverPromotesThatBoundary() {
    for (int boundary = 0; boundary < 6; boundary++) {
      Fixture fixture = new Fixture(1, 16);
      assertEquals(
          StatusCode.OK,
          fixture.controller.addRule(
              fixture.beforePoint(boundary),
              fixture.operation(boundary),
              1,
              1,
              FaultAction.CRASH,
              0));
      AtomicInstallProgress progress = new AtomicInstallProgress();
      AtomicInstallDriveResult driveResult = new AtomicInstallDriveResult();
      assertEquals(
          StatusCode.IO_FAILURE,
          fixture.contract.drive(
              fixture.store,
              request(new byte[] {3, 2, 1}),
              progress,
              fixture.stepResult,
              8,
              driveResult),
          "boundary " + boundary);
      assertEquals(AtomicInstallPhase.RECOVERY_REQUIRED, progress.phase());
      assertEquals(StatusCode.OK, fixture.store.restart());
      StatusCode reopenStatus = fixture.reopenStatus("control");
      if (boundary <= 4) {
        assertEquals(StatusCode.CORRUPTION, reopenStatus, "boundary " + boundary);
      } else {
        assertEquals(StatusCode.OK, reopenStatus, "boundary " + boundary);
      }
    }
  }

  @Test
  void verifyCorruptionFailsClosedWithoutWeakeningDirectoryDurability() {
    Fixture fixture = new Fixture(1, 16);
    assertEquals(
        StatusCode.OK,
        fixture.controller.addRule(
            fixture.points.reopenVerifyBefore(),
            FaultOperation.REOPEN_VERIFY,
            1,
            1,
            FaultAction.CORRUPT_READ,
            1));
    AtomicInstallProgress progress = new AtomicInstallProgress();
    AtomicInstallDriveResult driveResult = new AtomicInstallDriveResult();

    assertEquals(
        StatusCode.CORRUPTION,
        fixture.contract.drive(
            fixture.store,
            request(new byte[] {0, 1}),
            progress,
            fixture.stepResult,
            8,
            driveResult));
    assertEquals(AtomicInstallPhase.DIRECTORY_FORCED, progress.phase());
    assertEquals(DirectoryDurability.DURABLE, progress.durability());
  }

  @Test
  void boundedTraceSaturatesWithoutChangingInstallOutcome() {
    Fixture fixture = new Fixture(0, 2);
    AtomicInstallProgress progress = new AtomicInstallProgress();
    AtomicInstallDriveResult driveResult = new AtomicInstallDriveResult();

    assertEquals(
        StatusCode.OK,
        fixture.contract.drive(
            fixture.store,
            request(new byte[] {5}),
            progress,
            fixture.stepResult,
            8,
            driveResult));
    assertTrue(progress.isComplete());
    assertEquals(2, fixture.trace.size());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, fixture.store.traceStatus());
  }

  private static AtomicInstallRequest request(byte[] content) {
    AtomicInstallRequest request = new AtomicInstallRequest();
    assertEquals(
        StatusCode.OK,
        request.configure("control.tmp", "control", ByteBuffer.wrap(content)));
    return request;
  }

  @FunctionalInterface
  private interface FaultPointSelector {
    FaultPoint select(Fixture fixture);
  }

  private static final class Fixture {
    private final CrashPointController controller;
    private final AtomicInstallFaultPoints points;
    private final FaultingAtomicFileStore store;
    private final AtomicInstallTrace trace;
    private final AtomicInstallResult stepResult = new AtomicInstallResult();
    private final AtomicFileInstallerContract contract = new AtomicFileInstallerContract();

    private Fixture(int rules, int traceCapacity) {
      FaultPointRegistry registry = new FaultPointRegistry(12);
      points = new AtomicInstallFaultPoints(
          point(registry, "install.temp-create.before"),
          point(registry, "install.temp-create.after"),
          point(registry, "install.temp-write.before"),
          point(registry, "install.temp-write.after"),
          point(registry, "install.temp-force.before"),
          point(registry, "install.temp-force.after"),
          point(registry, "install.replace.before"),
          point(registry, "install.replace.after"),
          point(registry, "install.directory-force.before"),
          point(registry, "install.directory-force.after"),
          point(registry, "install.reopen-verify.before"),
          point(registry, "install.reopen-verify.after"));
      controller = new CrashPointController(rules);
      trace = new AtomicInstallTrace(traceCapacity);
      store = new FaultingAtomicFileStore(
          4,
          64,
          4,
          controller,
          points,
          trace);
    }

    private StatusCode reopenStatus(String fileName) {
      DirectoryOperationResult openResult = new DirectoryOperationResult();
      StatusCode status = store.reopen(fileName, openResult);
      if (openResult.file() != null) {
        openResult.file().close();
      }
      return status;
    }

    private boolean hasPendingCompletion() {
      for (int index = 0; index < trace.size(); index++) {
        if (trace.completionPending(index)) {
          return true;
        }
      }
      return false;
    }

    private FaultPoint beforePoint(int boundary) {
      return switch (boundary) {
        case 0 -> points.tempCreateBefore();
        case 1 -> points.tempWriteBefore();
        case 2 -> points.tempForceBefore();
        case 3 -> points.replaceBefore();
        case 4 -> points.directoryForceBefore();
        case 5 -> points.reopenVerifyBefore();
        default -> throw new AssertionError(boundary);
      };
    }

    private FaultPoint afterPoint(int boundary) {
      return switch (boundary) {
        case 0 -> points.tempCreateAfter();
        case 1 -> points.tempWriteAfter();
        case 2 -> points.tempForceAfter();
        case 3 -> points.replaceAfter();
        case 4 -> points.directoryForceAfter();
        case 5 -> points.reopenVerifyAfter();
        default -> throw new AssertionError(boundary);
      };
    }

    private FaultOperation operation(int boundary) {
      return switch (boundary) {
        case 0 -> FaultOperation.TEMP_CREATE;
        case 1 -> FaultOperation.TEMP_WRITE;
        case 2 -> FaultOperation.TEMP_FORCE;
        case 3 -> FaultOperation.REPLACE;
        case 4 -> FaultOperation.DIRECTORY_FORCE;
        case 5 -> FaultOperation.REOPEN_VERIFY;
        default -> throw new AssertionError(boundary);
      };
    }

    private byte[] reopenAndRead(String fileName) {
      DirectoryOperationResult openResult = new DirectoryOperationResult();
      assertEquals(StatusCode.OK, store.reopen(fileName, openResult));
      DurableFile file = openResult.file();
      ByteBuffer target = ByteBuffer.allocate(64);
      IoResult ioResult = new IoResult();
      assertEquals(StatusCode.OK, file.read(0, target, ioResult));
      byte[] bytes = new byte[ioResult.bytesTransferred()];
      target.flip();
      target.get(bytes);
      assertEquals(StatusCode.OK, file.close());
      return bytes;
    }

    private static FaultPoint point(FaultPointRegistry registry, String name) {
      FaultPointSlot slot = new FaultPointSlot();
      assertEquals(StatusCode.OK, registry.register(name, slot));
      return slot.value();
    }
  }
}
