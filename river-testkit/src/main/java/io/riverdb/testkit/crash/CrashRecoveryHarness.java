package io.riverdb.testkit.crash;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.OpenFileResult;
import io.riverdb.testkit.io.FaultingFileIoProvider;

/**
 * Drives bounded workload/crash/restart/reopen/verify cycles against the file model. An operation
 * failure is treated as an injected process loss only when the provider generation/lifecycle also
 * changed; an ordinary failure closes its still-owned handle and terminates without recovery.
 */
public final class CrashRecoveryHarness {
  private final FaultingFileIoProvider provider;
  private final OpenFileResult openResult = new OpenFileResult();

  public CrashRecoveryHarness(FaultingFileIoProvider provider) {
    this.provider = provider;
  }

  public synchronized StatusCode run(
      String fileName,
      int cycles,
      CrashWorkload workload,
      RecoveryVerifier verifier,
      CrashRunReport report) {
    report.reset();
    if (cycles < 0) {
      report.failed(-1, StatusCode.INVALID_EXTERNAL_INPUT);
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int cycle = 0; cycle < cycles; cycle++) {
      StatusCode status = provider.open(fileName, openResult);
      if (!status.isOk()) {
        report.failed(cycle, status);
        return status;
      }
      DurableFile beforeCrash = openResult.file();
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
        report.recoveredInjectedFailure(status);
      } else {
        status = provider.crash();
        if (!status.isOk()) {
          StatusCode cleanupStatus = provider.isRunning()
              ? beforeCrash.close()
              : StatusCode.CANCELLED;
          report.failedWithCleanup(cycle, status, cleanupStatus);
          return status;
        }
      }
      if (!provider.isRunning()) {
        status = provider.restart();
        if (!status.isOk()) {
          report.failed(cycle, status);
          return status;
        }
      }
      status = provider.open(fileName, openResult);
      if (!status.isOk()) {
        report.failed(cycle, status);
        return status;
      }
      DurableFile reopened = openResult.file();
      status = verifier.verify(cycle, reopened);
      StatusCode closeStatus = reopened.close();
      if (!status.isOk()) {
        report.failed(cycle, status);
        return status;
      }
      if (!closeStatus.isOk()) {
        report.failed(cycle, closeStatus);
        return closeStatus;
      }
      report.completed();
    }
    return StatusCode.OK;
  }
}
