package io.riverdb.testkit.crash;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.OpenFileResult;
import io.riverdb.testkit.io.FaultingFileIoProvider;

/** Drives bounded workload/crash/restart/reopen/verify cycles against the file model. */
public final class CrashRecoveryHarness {
  private final FaultingFileIoProvider provider;
  private final OpenFileResult openResult = new OpenFileResult();

  public CrashRecoveryHarness(FaultingFileIoProvider provider) {
    this.provider = provider;
  }

  public StatusCode run(
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
      status = workload.run(cycle, beforeCrash);
      if (!status.isOk()) {
        report.failed(cycle, status);
        return status;
      }
      status = provider.crash();
      if (!status.isOk()) {
        report.failed(cycle, status);
        return status;
      }
      status = provider.restart();
      if (!status.isOk()) {
        report.failed(cycle, status);
        return status;
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
