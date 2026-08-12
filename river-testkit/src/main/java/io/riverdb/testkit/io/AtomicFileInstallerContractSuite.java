package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultBoundary;
import io.riverdb.platform.file.AtomicInstallId;
import io.riverdb.platform.file.AtomicInstallPhase;
import io.riverdb.platform.file.AtomicInstallProgress;
import io.riverdb.platform.file.AtomicInstallRequest;
import io.riverdb.platform.file.AtomicInstallResult;
import io.riverdb.platform.file.AtomicInstallSnapshot;
import io.riverdb.platform.file.AtomicInstallStep;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;

/**
 * Provider-neutral deterministic contract suite for fake and qualified provider adapters.
 *
 * <p>The suite proves protocol ordering and fault-state semantics. A real NIO adapter must run it
 * in addition to, not instead of, the external filesystem/device power-loss qualification.
 */
public final class AtomicFileInstallerContractSuite {
  public static final int SCENARIO_HAPPY_ORDER = 1;
  public static final int SCENARIO_DELAY = 2;
  public static final int SCENARIO_CRASH = 3;
  public static final int SCENARIO_RESTORE_OLD = 4;
  public static final int SCENARIO_FORCE_FAILURE = 5;
  public static final int SCENARIO_BOUNDED_TRACE = 6;
  public static final int SCENARIO_INSTALL_IDENTITY = 7;

  private static final AtomicInstallStep[] STEPS = {
    AtomicInstallStep.TEMP_CREATE,
    AtomicInstallStep.TEMP_WRITE,
    AtomicInstallStep.TEMP_FORCE,
    AtomicInstallStep.DESTINATION_REPLACE,
    AtomicInstallStep.PARENT_DIRECTORY_FORCE,
    AtomicInstallStep.REOPEN_VERIFY
  };

  private final AtomicFileInstallerContract contract = new AtomicFileInstallerContract();
  private final AtomicInstallResult stepResult = new AtomicInstallResult();
  private final AtomicInstallDriveResult driveResult = new AtomicInstallDriveResult();
  private final AtomicInstallSnapshot snapshot = new AtomicInstallSnapshot();
  private final DirectoryOperationResult openResult = new DirectoryOperationResult();
  private final FileSizeResult sizeResult = new FileSizeResult();
  private final IoResult ioResult = new IoResult();
  private final ByteBuffer scratch = ByteBuffer.allocate(256);
  private int completedScenarios;

  public synchronized StatusCode run(
      AtomicInstallContractProviderFactory factory,
      AtomicInstallSuiteResult result) {
    result.reset();
    completedScenarios = 0;
    StatusCode status = happyOrder(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_HAPPY_ORDER);
    }
    completedScenarios++;
    status = delayedCompletion(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_DELAY);
    }
    completedScenarios++;
    status = crashMatrix(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_CRASH);
    }
    completedScenarios++;
    status = restoreOldDestination(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_RESTORE_OLD);
    }
    completedScenarios++;
    status = forceFailures(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_FORCE_FAILURE);
    }
    completedScenarios++;
    status = boundedTrace(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_BOUNDED_TRACE);
    }
    completedScenarios++;
    status = installIdentity(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_INSTALL_IDENTITY);
    }
    completedScenarios++;
    result.set(StatusCode.OK, 0, completedScenarios);
    return StatusCode.OK;
  }

  private StatusCode happyOrder(AtomicInstallContractProviderFactory factory) {
    AtomicInstallContractProvider provider = factory.create(0, 8);
    ByteBuffer content = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
    StatusCode status = install(provider, content, new AtomicInstallProgress(), 8);
    if (!status.isOk() || provider.traceSize() != STEPS.length) {
      return StatusCode.INVARIANT_BROKEN;
    }
    for (int index = 0; index < STEPS.length; index++) {
      if (provider.traceStep(index) != STEPS[index]
          || provider.traceOutcome(index) != StatusCode.OK) {
        return StatusCode.INVARIANT_BROKEN;
      }
    }
    if (!provider.crash().isOk() || !provider.restart().isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    return verify(provider, content);
  }

  private StatusCode delayedCompletion(AtomicInstallContractProviderFactory factory) {
    for (AtomicInstallStep step : STEPS) {
      AtomicInstallContractProvider provider = factory.create(1, 12);
      StatusCode status = provider.script(step, FaultBoundary.AFTER, FaultAction.DELAY, 0);
      if (!status.isOk()) {
        return status;
      }
      status = install(
          provider,
          ByteBuffer.wrap(new byte[] {2, 4, 6}),
          new AtomicInstallProgress(),
          9);
      if (!status.isOk() || !hasPendingTrace(provider)) {
        return StatusCode.INVARIANT_BROKEN;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode crashMatrix(AtomicInstallContractProviderFactory factory) {
    for (FaultBoundary boundary : FaultBoundary.values()) {
      for (int index = 0; index < STEPS.length; index++) {
        AtomicInstallContractProvider provider = factory.create(1, 12);
        StatusCode status = provider.script(STEPS[index], boundary, FaultAction.CRASH, 0);
        if (!status.isOk()) {
          return status;
        }
        AtomicInstallProgress progress = new AtomicInstallProgress();
        ByteBuffer content = ByteBuffer.wrap(new byte[] {7, 6, 5});
        status = install(provider, content, progress, 8);
        if (status != StatusCode.IO_FAILURE
            || !provider.installer().inspect(progress, snapshot).isOk()
            || snapshot.phase() != AtomicInstallPhase.RECOVERY_REQUIRED
            || !provider.restart().isOk()) {
          return StatusCode.INVARIANT_BROKEN;
        }
        boolean shouldSurvive = boundary == FaultBoundary.AFTER ? index >= 4 : index >= 5;
        StatusCode verifyStatus = verify(provider, content);
        if (shouldSurvive != verifyStatus.isOk()) {
          return StatusCode.INVARIANT_BROKEN;
        }
      }
    }
    return StatusCode.OK;
  }

  private StatusCode restoreOldDestination(AtomicInstallContractProviderFactory factory) {
    AtomicInstallContractProvider provider = factory.create(1, 20);
    ByteBuffer oldContent = ByteBuffer.wrap(new byte[] {1, 1});
    StatusCode status = install(provider, oldContent, new AtomicInstallProgress(), 8);
    if (!status.isOk()) {
      return status;
    }
    status = provider.script(
        AtomicInstallStep.DESTINATION_REPLACE,
        FaultBoundary.AFTER,
        FaultAction.CRASH,
        0);
    if (!status.isOk()) {
      return status;
    }
    status = install(
        provider,
        ByteBuffer.wrap(new byte[] {2, 2}),
        new AtomicInstallProgress(),
        8);
    if (status != StatusCode.IO_FAILURE || !provider.restart().isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    return verify(provider, oldContent);
  }

  private StatusCode forceFailures(AtomicInstallContractProviderFactory factory) {
    AtomicInstallStep[] forceSteps = {
      AtomicInstallStep.TEMP_FORCE,
      AtomicInstallStep.PARENT_DIRECTORY_FORCE
    };
    AtomicInstallPhase[] expected = {
      AtomicInstallPhase.CONTENT_WRITTEN,
      AtomicInstallPhase.DESTINATION_REPLACED
    };
    for (int index = 0; index < forceSteps.length; index++) {
      AtomicInstallContractProvider provider = factory.create(1, 12);
      StatusCode status = provider.script(
          forceSteps[index],
          FaultBoundary.BEFORE,
          FaultAction.FORCE_FAILURE,
          0);
      if (!status.isOk()) {
        return status;
      }
      AtomicInstallProgress progress = new AtomicInstallProgress();
      status = install(
          provider,
          ByteBuffer.wrap(new byte[] {3, 3}),
          progress,
          8);
      if (status != StatusCode.IO_FAILURE
          || !provider.installer().inspect(progress, snapshot).isOk()
          || snapshot.phase() != expected[index]) {
        return StatusCode.INVARIANT_BROKEN;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode boundedTrace(AtomicInstallContractProviderFactory factory) {
    AtomicInstallContractProvider provider = factory.create(0, 2);
    StatusCode status = install(
        provider,
        ByteBuffer.wrap(new byte[] {9}),
        new AtomicInstallProgress(),
        8);
    return status.isOk()
            && provider.traceSize() == 2
            && provider.traceStatus() == StatusCode.RESOURCE_EXHAUSTED
        ? StatusCode.OK
        : StatusCode.INVARIANT_BROKEN;
  }

  private StatusCode installIdentity(AtomicInstallContractProviderFactory factory) {
    AtomicInstallPhase[] reachedPhases = {
      AtomicInstallPhase.TEMP_CREATED,
      AtomicInstallPhase.CONTENT_WRITTEN,
      AtomicInstallPhase.CONTENT_FORCED,
      AtomicInstallPhase.DESTINATION_REPLACED,
      AtomicInstallPhase.DIRECTORY_FORCED,
      AtomicInstallPhase.VERIFIED
    };
    for (int advances = 1; advances <= reachedPhases.length; advances++) {
      AtomicInstallContractProvider provider = factory.create(0, 16);
      AtomicInstallId firstId = new AtomicInstallId();
      AtomicInstallId secondId = new AtomicInstallId();
      StatusCode status = provider.installer().issueInstallId(firstId);
      if (!status.isOk()) {
        return status;
      }
      status = provider.installer().issueInstallId(secondId);
      if (!status.isOk()) {
        return status;
      }
      AtomicInstallRequest first = new AtomicInstallRequest();
      AtomicInstallRequest second = new AtomicInstallRequest();
      status = first.configure(
          firstId,
          "first.tmp",
          "first",
          ByteBuffer.wrap(new byte[] {1, 1}));
      if (!status.isOk()) {
        return status;
      }
      status = second.configure(
          secondId,
          "second.tmp",
          "second",
          ByteBuffer.wrap(new byte[] {2, 2}));
      if (!status.isOk()) {
        return status;
      }
      AtomicInstallProgress progress = new AtomicInstallProgress();
      for (int advance = 0; advance < advances; advance++) {
        status = provider.installer().advance(first, progress, stepResult);
        if (!status.isOk()) {
          return status;
        }
      }
      status = provider.installer().inspect(progress, snapshot);
      if (!status.isOk() || snapshot.phase() != reachedPhases[advances - 1]) {
        return StatusCode.INVARIANT_BROKEN;
      }
      AtomicInstallPhase phaseBefore = snapshot.phase();
      int bytesBefore = snapshot.bytesWritten();
      status = provider.installer().advance(second, progress, stepResult);
      if (status != StatusCode.CONFLICT
          || !provider.installer().inspect(progress, snapshot).isOk()
          || snapshot.phase() != phaseBefore
          || snapshot.bytesWritten() != bytesBefore) {
        return StatusCode.INVARIANT_BROKEN;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode install(
      AtomicInstallContractProvider provider,
      ByteBuffer content,
      AtomicInstallProgress progress,
      int maxAdvances) {
    AtomicInstallRequest request = new AtomicInstallRequest();
    AtomicInstallId installId = new AtomicInstallId();
    StatusCode status = provider.installer().issueInstallId(installId);
    if (!status.isOk()) {
      return status;
    }
    status = request.configure(installId, "control.tmp", "control", content);
    if (!status.isOk()) {
      return status;
    }
    return contract.drive(
        provider.installer(),
        request,
        progress,
        stepResult,
        maxAdvances,
        driveResult);
  }

  private StatusCode verify(
      AtomicInstallContractProvider provider,
      ByteBuffer expected) {
    return contract.verifyInstalled(
        provider.directory(),
        "control",
        expected,
        scratch,
        openResult,
        sizeResult,
        ioResult);
  }

  private static boolean hasPendingTrace(AtomicInstallContractProvider provider) {
    for (int index = 0; index < provider.traceSize(); index++) {
      if (provider.traceCompletionPending(index)) {
        return true;
      }
    }
    return false;
  }

  private StatusCode fail(
      AtomicInstallSuiteResult result,
      StatusCode status,
      int scenario) {
    result.set(status, scenario, completedScenarios);
    return status;
  }
}
