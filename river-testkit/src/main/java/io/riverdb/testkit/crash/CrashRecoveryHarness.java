package io.riverdb.testkit.crash;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.OpenFileResult;
import io.riverdb.testkit.io.FaultingFileIoProvider;

/**
 * Drives bounded open/workload/crash/restart/reopen/verify/close phases. A failure is treated as an
 * injected process loss only when provider generation/lifecycle also changed. Recovery retries are
 * bounded explicitly; an ordinary failure closes its still-owned handle and terminates.
 */
public final class CrashRecoveryHarness {
  private final FaultingFileIoProvider provider;
  private final int maxRecoveryTransitions;
  private final OpenFileResult openResult = new OpenFileResult();

  public CrashRecoveryHarness(FaultingFileIoProvider provider) {
    this(provider, 8);
  }

  public CrashRecoveryHarness(
      FaultingFileIoProvider provider,
      int maxRecoveryTransitions) {
    this.provider = provider;
    this.maxRecoveryTransitions = maxRecoveryTransitions;
  }

  public synchronized StatusCode run(
      String fileName,
      int cycles,
      CrashWorkload workload,
      RecoveryVerifier verifier,
      CrashRunReport report) {
    report.reset();
    if (cycles < 0 || maxRecoveryTransitions < 0) {
      report.failed(-1, StatusCode.INVALID_EXTERNAL_INPUT);
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int cycle = 0; cycle < cycles; cycle++) {
      StatusCode status = openWithRecovery(
          fileName, cycle, CrashPhase.INITIAL_OPEN, report);
      if (!status.isOk()) {
        report.failed(cycle, status);
        return status;
      }
      DurableFile beforeCrash = openResult.file();
      report.enter(CrashPhase.WORKLOAD);
      long generationBeforeWorkload = provider.generation();
      status = workload.run(cycle, beforeCrash);
      boolean lifecycleChanged = provider.generation() != generationBeforeWorkload
          || !provider.isRunning();
      if (!status.isOk() && !lifecycleChanged) {
        StatusCode cleanupStatus = beforeCrash.close();
        report.failedWithCleanup(cycle, status, cleanupStatus);
        return status;
      }
      if (!status.isOk()) {
        if (!recordRecovery(status, cycle, report)) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        status = ensureRunning(cycle, report);
        if (!status.isOk()) {
          return status;
        }
      } else {
        report.enter(CrashPhase.CRASH);
        status = provider.crash();
        if (!status.isOk()) {
          StatusCode cleanupStatus = provider.isRunning()
              ? beforeCrash.close()
              : StatusCode.CANCELLED;
          report.failedWithCleanup(cycle, status, cleanupStatus);
          return status;
        }
      }
      status = ensureRunning(cycle, report);
      if (!status.isOk()) {
        return status;
      }
      status = openWithRecovery(fileName, cycle, CrashPhase.REOPEN, report);
      if (!status.isOk()) {
        report.failed(cycle, status);
        return status;
      }
      DurableFile reopened = openResult.file();
      while (true) {
        report.enter(CrashPhase.VERIFY);
        long generationBeforeVerify = provider.generation();
        status = verifier.verify(cycle, reopened);
        if (status.isOk()) {
          break;
        }
        boolean verifierCrash = provider.generation() != generationBeforeVerify
            || !provider.isRunning();
        if (!verifierCrash) {
          StatusCode cleanupStatus = reopened.close();
          report.failedWithCleanup(cycle, status, cleanupStatus);
          return status;
        }
        if (!recordRecovery(status, cycle, report)) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        status = ensureRunning(cycle, report);
        if (!status.isOk()) {
          return status;
        }
        status = openWithRecovery(fileName, cycle, CrashPhase.REOPEN, report);
        if (!status.isOk()) {
          report.failed(cycle, status);
          return status;
        }
        reopened = openResult.file();
      }
      report.enter(CrashPhase.CLOSE);
      long generationBeforeClose = provider.generation();
      StatusCode closeStatus = reopened.close();
      if (!closeStatus.isOk()) {
        boolean closeCrash = provider.generation() != generationBeforeClose
            || !provider.isRunning();
        if (!closeCrash) {
          report.failed(cycle, closeStatus);
          return closeStatus;
        }
        if (!recordRecovery(closeStatus, cycle, report)) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        status = ensureRunning(cycle, report);
        if (!status.isOk()) {
          return status;
        }
      }
      report.completed();
      report.enter(CrashPhase.COMPLETE);
    }
    return StatusCode.OK;
  }

  private StatusCode openWithRecovery(
      String fileName,
      int cycle,
      CrashPhase phase,
      CrashRunReport report) {
    while (true) {
      report.enter(phase);
      long generationBeforeOpen = provider.generation();
      StatusCode status = provider.open(fileName, openResult);
      if (status.isOk()) {
        return StatusCode.OK;
      }
      boolean lifecycleChanged = provider.generation() != generationBeforeOpen
          || !provider.isRunning();
      if (!lifecycleChanged) {
        return status;
      }
      if (!recordRecovery(status, cycle, report)) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      status = ensureRunning(cycle, report);
      if (!status.isOk()) {
        return status;
      }
    }
  }

  private StatusCode ensureRunning(int cycle, CrashRunReport report) {
    while (!provider.isRunning()) {
      report.enter(CrashPhase.RESTART);
      StatusCode status = provider.restart();
      if (status.isOk() && provider.isRunning()) {
        return StatusCode.OK;
      }
      if (provider.isRunning()) {
        report.failed(cycle, status);
        return status;
      }
      if (!recordRecovery(status, cycle, report)) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    return StatusCode.OK;
  }

  private boolean recordRecovery(
      StatusCode status,
      int cycle,
      CrashRunReport report) {
    if (!report.beginRecoveryTransition(maxRecoveryTransitions)) {
      report.failed(cycle, StatusCode.RESOURCE_EXHAUSTED);
      return false;
    }
    report.recoveredInjectedFailure(status);
    return true;
  }
}
