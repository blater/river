package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.AtomicFileInstaller;
import io.riverdb.platform.file.AtomicInstallProgress;
import io.riverdb.platform.file.AtomicInstallRequest;
import io.riverdb.platform.file.AtomicInstallResult;

/** Reusable bounded driver for fake, NIO, mapped, or native installer contract suites. */
public final class AtomicFileInstallerContract {
  public StatusCode drive(
      AtomicFileInstaller installer,
      AtomicInstallRequest request,
      AtomicInstallProgress progress,
      AtomicInstallResult stepResult,
      int maxAdvances,
      AtomicInstallDriveResult result) {
    result.reset();
    if (maxAdvances < 1) {
      result.set(StatusCode.INVALID_EXTERNAL_INPUT, 0);
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int advance = 1; advance <= maxAdvances; advance++) {
      StatusCode status = installer.advance(request, progress, stepResult);
      if (progress.isComplete()) {
        result.set(status, advance);
        return status;
      }
      if (!status.isOk() && status != StatusCode.RETRY) {
        result.set(status, advance);
        return status;
      }
    }
    result.set(StatusCode.TIMEOUT, maxAdvances);
    return StatusCode.TIMEOUT;
  }
}
