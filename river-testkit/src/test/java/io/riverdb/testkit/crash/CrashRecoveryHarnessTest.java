package io.riverdb.testkit.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultOperation;
import io.riverdb.platform.fault.FaultPoint;
import io.riverdb.platform.fault.FaultPointRegistry;
import io.riverdb.platform.fault.FaultPointSlot;
import io.riverdb.platform.fault.NoOpFaultInjector;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.OpenFileResult;
import io.riverdb.testkit.io.FaultingFileIoProvider;
import io.riverdb.testkit.io.FileFaultPoints;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class CrashRecoveryHarnessTest {
  @Test
  void repeatsWriteForceCrashReopenVerification() {
    FaultingFileIoProvider provider = provider();
    CrashRecoveryHarness harness = new CrashRecoveryHarness(provider);
    CrashRunReport report = new CrashRunReport();
    IoResult ioResult = new IoResult();

    StatusCode status = harness.run(
        "data-0001",
        8,
        (cycle, file) -> {
          ioResult.reset();
          StatusCode writeStatus = file.write(
              cycle,
              ByteBuffer.wrap(new byte[] {(byte) (cycle + 1)}),
              ioResult);
          if (!writeStatus.isOk()) {
            return writeStatus;
          }
          return file.force(ForceMode.CONTENT_AND_METADATA);
        },
        (cycle, reopened) -> {
          ByteBuffer target = ByteBuffer.allocate(cycle + 1);
          ioResult.reset();
          StatusCode readStatus = reopened.read(0, target, ioResult);
          if (!readStatus.isOk()) {
            return readStatus;
          }
          if (ioResult.bytesTransferred() != cycle + 1) {
            return StatusCode.CORRUPTION;
          }
          target.flip();
          for (int index = 0; index <= cycle; index++) {
            if (target.get() != (byte) (index + 1)) {
              return StatusCode.CORRUPTION;
            }
          }
          return StatusCode.OK;
        },
        report);

    assertEquals(StatusCode.OK, status);
    assertEquals(8, report.completedCycles());
    assertEquals(-1, report.failedCycle());
  }

  @Test
  void injectedCrashFailureStillRestartsReopensAndVerifies() {
    FaultPointRegistry registry = new FaultPointRegistry(9);
    FileFaultPoints points = points(registry);
    CrashPointController controller = new CrashPointController(1);
    assertEquals(
        StatusCode.OK,
        controller.addRule(
            points.write(),
            FaultOperation.WRITE,
            1,
            1,
            FaultAction.CRASH,
            0));
    FaultingFileIoProvider provider = new FaultingFileIoProvider(
        1, 64, 2, controller, points);
    CrashRecoveryHarness harness = new CrashRecoveryHarness(provider);
    CrashRunReport report = new CrashRunReport();

    StatusCode status = harness.run(
        "data-0001",
        1,
        (cycle, file) -> {
          IoResult result = new IoResult();
          return file.write(0, ByteBuffer.wrap(new byte[] {1}), result);
        },
        (cycle, reopened) -> {
          FileSizeResult result = new FileSizeResult();
          StatusCode sizeStatus = reopened.size(result);
          return sizeStatus.isOk() && result.sizeBytes() == 0
              ? StatusCode.OK
              : StatusCode.CORRUPTION;
        },
        report);

    assertEquals(StatusCode.OK, status);
    assertEquals(1, report.completedCycles());
    assertEquals(1, report.recoveredInjectedFailures());
    assertEquals(StatusCode.IO_FAILURE, report.observedWorkloadStatus());
  }

  @Test
  void ordinaryFailureClosesOwnedHandleWithoutRecovery() {
    FaultingFileIoProvider provider = provider();
    CrashRecoveryHarness harness = new CrashRecoveryHarness(provider);
    CrashRunReport report = new CrashRunReport();

    StatusCode status = harness.run(
        "data-0001",
        1,
        (cycle, file) -> StatusCode.IO_FAILURE,
        (cycle, reopened) -> StatusCode.INVARIANT_BROKEN,
        report);

    assertEquals(StatusCode.IO_FAILURE, status);
    assertEquals(0, report.completedCycles());
    assertEquals(StatusCode.OK, report.cleanupStatus());
    assertEquals(0, provider.openHandleCount());
    OpenFileResult result = new OpenFileResult();
    assertEquals(StatusCode.OK, provider.open("data-0001", result));
  }

  @Test
  void recoversCrashDuringInitialOpen() {
    HarnessFixture fixture = fixture(1);
    assertEquals(
        StatusCode.OK,
        fixture.controller.addRule(
            fixture.points.open(),
            FaultOperation.OPEN,
            1,
            1,
            FaultAction.CRASH,
            0));
    CrashRunReport report = new CrashRunReport();

    assertEquals(StatusCode.OK, runOneCycle(fixture.provider, report));
    assertEquals(1, report.recoveryTransitions());
    assertEquals(CrashPhase.COMPLETE, report.phase());
  }

  @Test
  void recoversCrashDuringRestart() {
    HarnessFixture fixture = fixture(1);
    assertEquals(
        StatusCode.OK,
        fixture.controller.addRule(
            fixture.points.restart(),
            FaultOperation.RESTART,
            1,
            1,
            FaultAction.CRASH,
            0));
    CrashRunReport report = new CrashRunReport();

    assertEquals(StatusCode.OK, runOneCycle(fixture.provider, report));
    assertEquals(1, report.recoveryTransitions());
    assertEquals(CrashPhase.COMPLETE, report.phase());
  }

  @Test
  void recoversCrashDuringReopen() {
    HarnessFixture fixture = fixture(1);
    assertEquals(
        StatusCode.OK,
        fixture.controller.addRule(
            fixture.points.open(),
            FaultOperation.OPEN,
            2,
            1,
            FaultAction.CRASH,
            0));
    CrashRunReport report = new CrashRunReport();

    assertEquals(StatusCode.OK, runOneCycle(fixture.provider, report));
    assertEquals(1, report.recoveryTransitions());
    assertEquals(CrashPhase.COMPLETE, report.phase());
  }

  @Test
  void recoversCrashDuringVerifier() {
    HarnessFixture fixture = fixture(1);
    assertEquals(
        StatusCode.OK,
        fixture.controller.addRule(
            fixture.points.read(),
            FaultOperation.READ,
            1,
            1,
            FaultAction.CRASH,
            0));
    CrashRunReport report = new CrashRunReport();

    assertEquals(StatusCode.OK, runOneCycle(fixture.provider, report));
    assertEquals(1, report.recoveryTransitions());
    assertEquals(CrashPhase.COMPLETE, report.phase());
  }

  @Test
  void repeatedRestartCrashStopsAtConfiguredRecoveryBound() {
    HarnessFixture fixture = fixture(1);
    assertEquals(
        StatusCode.OK,
        fixture.controller.addRule(
            fixture.points.restart(),
            FaultOperation.RESTART,
            1,
            100,
            FaultAction.CRASH,
            0));
    CrashRecoveryHarness harness = new CrashRecoveryHarness(fixture.provider, 2);
    CrashRunReport report = new CrashRunReport();

    StatusCode status = harness.run(
        "data-0001",
        1,
        (cycle, file) -> StatusCode.OK,
        (cycle, reopened) -> StatusCode.OK,
        report);

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, status);
    assertEquals(2, report.recoveryTransitions());
    assertEquals(CrashPhase.RESTART, report.phase());
  }

  private static FaultingFileIoProvider provider() {
    FaultPointRegistry registry = new FaultPointRegistry(9);
    return new FaultingFileIoProvider(
        1, 64, 4, NoOpFaultInjector.INSTANCE, points(registry));
  }

  private static HarnessFixture fixture(int ruleCapacity) {
    FaultPointRegistry registry = new FaultPointRegistry(9);
    FileFaultPoints points = points(registry);
    CrashPointController controller = new CrashPointController(ruleCapacity);
    FaultingFileIoProvider provider = new FaultingFileIoProvider(
        1, 64, 4, controller, points);
    return new HarnessFixture(provider, controller, points);
  }

  private static StatusCode runOneCycle(
      FaultingFileIoProvider provider,
      CrashRunReport report) {
    CrashRecoveryHarness harness = new CrashRecoveryHarness(provider);
    IoResult ioResult = new IoResult();
    return harness.run(
        "data-0001",
        1,
        (cycle, file) -> {
          StatusCode writeStatus = file.write(
              0, ByteBuffer.wrap(new byte[] {42}), ioResult);
          return writeStatus.isOk()
              ? file.force(ForceMode.CONTENT_AND_METADATA)
              : writeStatus;
        },
        (cycle, reopened) -> {
          ByteBuffer target = ByteBuffer.allocate(1);
          ioResult.reset();
          StatusCode readStatus = reopened.read(0, target, ioResult);
          if (!readStatus.isOk()) {
            return readStatus;
          }
          target.flip();
          return ioResult.bytesTransferred() == 1 && target.get() == 42
              ? StatusCode.OK
              : StatusCode.CORRUPTION;
        },
        report);
  }

  private static FileFaultPoints points(FaultPointRegistry registry) {
    return new FileFaultPoints(
        point(registry, "file.open"),
        point(registry, "file.read"),
        point(registry, "file.write"),
        point(registry, "file.force"),
        point(registry, "file.size"),
        point(registry, "file.truncate"),
        point(registry, "file.close"),
        point(registry, "process.crash"),
        point(registry, "process.restart"));
  }

  private static FaultPoint point(FaultPointRegistry registry, String name) {
    FaultPointSlot slot = new FaultPointSlot();
    assertEquals(StatusCode.OK, registry.register(name, slot));
    return slot.value();
  }

  private record HarnessFixture(
      FaultingFileIoProvider provider,
      CrashPointController controller,
      FileFaultPoints points) {}
}
