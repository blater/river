package io.riverdb.platform.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class AtomicInstallStateMachineTest {
  @Test
  void callerSurfaceCannotPromoteProgress() {
    for (Method method : AtomicInstallProgress.class.getDeclaredMethods()) {
      if (!Modifier.isPublic(method.getModifiers())) {
        continue;
      }
      assertFalse(
          method.getName().equals("advance")
              || method.getName().equals("begin")
              || method.getName().equals("delayCompletion")
              || method.getName().equals("completePending")
              || method.getName().equals("requireRecovery"));
    }
  }

  @Test
  void rejectsCrossProviderReuseWithoutMutation() {
    AtomicInstallStateMachine first = new AtomicInstallStateMachine();
    AtomicInstallStateMachine second = new AtomicInstallStateMachine();
    AtomicInstallProgress progress = new AtomicInstallProgress();
    AtomicInstallRequest request = request(first, new byte[] {1, 2, 3, 4});
    assertEquals(StatusCode.OK, first.resume(progress, request, 1));

    assertEquals(StatusCode.NOT_OWNER, second.resume(progress, request, 1));
    AtomicInstallSnapshot snapshot = snapshot(first, progress);
    assertEquals(AtomicInstallPhase.NEW, snapshot.phase());
    assertEquals(0, snapshot.bytesWritten());
  }

  @Test
  void rejectsSkippedRegressionAndInvalidByteTransitionsWithoutMutation() {
    AtomicInstallStateMachine machine = new AtomicInstallStateMachine();
    AtomicInstallProgress progress = new AtomicInstallProgress();
    AtomicInstallRequest request = request(machine, new byte[] {1, 2, 3, 4});
    assertEquals(StatusCode.OK, machine.resume(progress, request, 1));
    assertEquals(
        StatusCode.INVARIANT_BROKEN,
        machine.transition(
            progress,
            AtomicInstallPhase.NEW,
            AtomicInstallPhase.VERIFIED,
            DirectoryDurability.DURABLE,
            4));
    assertEquals(AtomicInstallPhase.NEW, snapshot(machine, progress).phase());
    assertEquals(
        StatusCode.INVARIANT_BROKEN,
        machine.transition(
            progress,
            AtomicInstallPhase.NEW,
            AtomicInstallPhase.TEMP_CREATED,
            DirectoryDurability.DURABLE,
            0));
    assertEquals(AtomicInstallPhase.NEW, snapshot(machine, progress).phase());
    assertEquals(
        StatusCode.OK,
        machine.transition(
            progress,
            AtomicInstallPhase.NEW,
            AtomicInstallPhase.TEMP_CREATED,
            DirectoryDurability.VISIBLE_NOT_DURABLE,
            0));
    assertEquals(
        StatusCode.INVARIANT_BROKEN,
        machine.transition(
            progress,
            AtomicInstallPhase.TEMP_CREATED,
            AtomicInstallPhase.CONTENT_WRITTEN,
            DirectoryDurability.VISIBLE_NOT_DURABLE,
            3));
    assertEquals(AtomicInstallPhase.TEMP_CREATED, snapshot(machine, progress).phase());
    assertEquals(0, snapshot(machine, progress).bytesWritten());
  }

  @Test
  void activeResetRejectsWithoutMutationAndTerminalResetReleasesOwnership() {
    AtomicInstallStateMachine machine = new AtomicInstallStateMachine();
    AtomicInstallProgress progress = new AtomicInstallProgress();
    AtomicInstallRequest request = request(machine, new byte[] {7});
    assertEquals(StatusCode.OK, machine.resume(progress, request, 9));
    assertEquals(
        StatusCode.OK,
        machine.transition(
            progress,
            AtomicInstallPhase.NEW,
            AtomicInstallPhase.TEMP_CREATED,
            DirectoryDurability.VISIBLE_NOT_DURABLE,
            0));

    assertEquals(StatusCode.CONFLICT, progress.reset());
    assertEquals(AtomicInstallPhase.TEMP_CREATED, snapshot(machine, progress).phase());
    assertEquals(StatusCode.OK, machine.requireRecovery(progress));
    assertEquals(StatusCode.OK, progress.reset());
    assertEquals(StatusCode.NOT_OWNER, machine.snapshot(progress, new AtomicInstallSnapshot()));
  }

  @Test
  void issuedInstallIdAllowsEquivalentRequestReconstructionButRejectsRebinding() {
    AtomicInstallStateMachine machine = new AtomicInstallStateMachine();
    AtomicInstallId installId = new AtomicInstallId();
    assertEquals(StatusCode.OK, machine.issueInstallId(installId));
    assertEquals(StatusCode.CONFLICT, machine.issueInstallId(installId));
    AtomicInstallRequest original = new AtomicInstallRequest();
    AtomicInstallRequest reconstructed = new AtomicInstallRequest();
    AtomicInstallRequest conflicting = new AtomicInstallRequest();
    assertEquals(
        StatusCode.OK,
        original.configure(
            installId,
            "control.tmp",
            "control",
            ByteBuffer.wrap(new byte[] {1, 2})));
    assertEquals(
        StatusCode.OK,
        reconstructed.configure(
            installId,
            "control.tmp",
            "control",
            ByteBuffer.wrap(new byte[] {1, 2})));
    assertEquals(
        StatusCode.CONFLICT,
        conflicting.configure(
            installId,
            "control.tmp",
            "control",
            ByteBuffer.wrap(new byte[] {2, 1})));

    AtomicInstallProgress progress = new AtomicInstallProgress();
    assertEquals(StatusCode.OK, machine.resume(progress, original, 1));
    assertEquals(StatusCode.OK, machine.resume(progress, reconstructed, 1));
    assertEquals(StatusCode.NOT_OWNER, machine.resume(progress, conflicting, 1));
  }

  private static AtomicInstallSnapshot snapshot(
      AtomicInstallStateMachine machine,
      AtomicInstallProgress progress) {
    AtomicInstallSnapshot snapshot = new AtomicInstallSnapshot();
    assertEquals(StatusCode.OK, machine.snapshot(progress, snapshot));
    return snapshot;
  }

  private static AtomicInstallRequest request(
      AtomicInstallStateMachine machine,
      byte[] content) {
    AtomicInstallId installId = new AtomicInstallId();
    assertEquals(StatusCode.OK, machine.issueInstallId(installId));
    AtomicInstallRequest request = new AtomicInstallRequest();
    assertEquals(
        StatusCode.OK,
        request.configure(
            installId,
            "control.tmp",
            "control",
            ByteBuffer.wrap(content)));
    return request;
  }
}
