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
import io.riverdb.platform.file.DirectoryDurability;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
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
    assertEquals(StatusCode.OK, fixture.store.crash());
    assertEquals(StatusCode.OK, fixture.store.restart());
    assertArrayEquals(new byte[] {1, 2, 3, 4}, fixture.reopenAndRead("control"));
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
    assertEquals(AtomicInstallPhase.CONTENT_FORCED, fixture.stepResult.phaseAfter());

    assertEquals(StatusCode.OK, fixture.store.advance(request, progress, fixture.stepResult));
    assertEquals(AtomicInstallPhase.DESTINATION_REPLACED, progress.phase());
    assertFalse(progress.completionPending());
    assertEquals(StatusCode.OK, fixture.store.advance(request, progress, fixture.stepResult));
    assertEquals(StatusCode.OK, fixture.store.advance(request, progress, fixture.stepResult));
    assertTrue(progress.isComplete());
  }

  private static AtomicInstallRequest request(byte[] content) {
    AtomicInstallRequest request = new AtomicInstallRequest();
    assertEquals(
        StatusCode.OK,
        request.configure("control.tmp", "control", ByteBuffer.wrap(content)));
    return request;
  }

  private static final class Fixture {
    private final CrashPointController controller;
    private final AtomicInstallFaultPoints points;
    private final FaultingAtomicFileStore store;
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
      store = new FaultingAtomicFileStore(
          4,
          64,
          4,
          controller,
          points,
          new AtomicInstallTrace(traceCapacity));
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
